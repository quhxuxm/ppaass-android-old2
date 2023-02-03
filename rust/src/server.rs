use std::{
    collections::{
        hash_map::Entry::{Occupied, Vacant},
        HashMap,
    },
    fmt::Debug,
    fs::File,
    io::{ErrorKind, Read, Write},
    os::fd::FromRawFd,
    sync::Arc,
    time::Duration,
};

use anyhow::{anyhow, Result};

use etherparse::{
    InternetSlice::{Ipv4, Ipv6},
    TransportSlice::{Icmpv4, Icmpv6, Tcp, Udp, Unknown},
};

use log::{debug, error, info, trace};

use tokio::{runtime::Builder as TokioRuntimeBuilder, sync::mpsc::channel};
use tokio::{runtime::Runtime as TokioRuntime, sync::Mutex};

use uuid::Uuid;

use crate::{
    tcp::{TcpConnection, TcpConnectionKey, TcpConnectionTunHandle},
    udp::{handle_udp_packet, UdpPacketInfo},
};

pub(crate) struct PpaassVpnServer {
    id: String,
    runtime: Option<TokioRuntime>,
    tun_fd: i32,
    tcp_connection_tun_handle_repository: Arc<Mutex<HashMap<TcpConnectionKey, TcpConnectionTunHandle>>>,
}

impl Debug for PpaassVpnServer {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("PpaassVpnServer")
            .field("id", &self.id)
            .field("runtime", &self.runtime)
            .field("tun_fd", &self.tun_fd)
            .field("tcp_connection_tun_handle_repository", &self.tcp_connection_tun_handle_repository)
            .finish()
    }
}

impl PpaassVpnServer {
    pub(crate) fn new(tun_fd: i32) -> Result<Self> {
        let mut runtime_builder = TokioRuntimeBuilder::new_multi_thread();
        runtime_builder.worker_threads(32).enable_all().thread_name("PPAASS-RUST-THREAD");
        let runtime = runtime_builder.build().expect("Fail to start vpn runtime.");
        let id = Uuid::new_v4().to_string();
        let id = id.replace('-', "");

        info!("Create ppaass vpn server");

        Ok(Self {
            id,
            runtime: Some(runtime),
            tun_fd,
            tcp_connection_tun_handle_repository: Arc::new(Mutex::new(HashMap::<TcpConnectionKey, TcpConnectionTunHandle>::new())),
        })
    }

    pub(crate) fn stop(&mut self) -> Result<()> {
        if let Some(runtime) = self.runtime.take() {
            runtime.shutdown_background();
        }
        info!("Ppaass vpn server stopped.");
        Ok(())
    }

    pub(crate) fn start(&mut self) -> Result<()> {
        info!("Start ppaass vpn server [{}]", self.id);

        let Some(ref runtime) = self.runtime else{
            return Err(anyhow!("Fail to start ppaass vpn server [{}] becuase of no runtime.", self.id));
        };

        let tcp_connection_handle_repository = self.tcp_connection_tun_handle_repository.clone();

        let (tun_write_sender, mut tun_write_receiver) = channel::<Vec<u8>>(1024);
        let tun_fd = self.tun_fd;
        let tun_file = Arc::new(Mutex::new(unsafe { File::from_raw_fd(tun_fd) }));

        let tun_write = tun_file.clone();
        let tun_read = tun_file;
        runtime.block_on(async move {
            let tun_write_guard = tokio::spawn(async move {
                loop {
                    let ip_packet_bytes = match tun_write_receiver.recv().await {
                        Some(value) => value,
                        None => {
                            debug!("<<<< Nothing write to tun, stop wrtier task.");
                            return;
                        },
                    };
                    let ip_packet = etherparse::SlicedPacket::from_ip(&ip_packet_bytes);

                    trace!("<<<< Write ip packet to tun: {ip_packet:?}");

                    let mut tun_write = tun_write.lock().await;
                    if let Err(e) = tun_write.write(&ip_packet_bytes) {
                        error!("<<<< Fail to write ip packet to tun because of error: {e:?}")
                    };
                    if let Err(e) = tun_write.flush() {
                        error!("<<<< Fail to flush ip packet to tun because of error: {e:?}")
                    };
                }
            });
            let tun_read_guard = tokio::spawn(async move {
                let tun_write_sender = tun_write_sender;
                loop {
                    let mut tun_read_buf = vec![0; 65535];
                    let mut tun_read = tun_read.lock().await;

                    let tun_read_buf = match tun_read.read(&mut tun_read_buf) {
                        Ok(0) => {
                            tokio::time::sleep(Duration::from_millis(100)).await;
                            continue;
                        },
                        Ok(size) => &tun_read_buf[..size],
                        Err(e) => {
                            if e.kind() == ErrorKind::WouldBlock {
                                tokio::time::sleep(Duration::from_millis(100)).await;
                                continue;
                            }
                            error!(">>>> Fail to read data from tun because of error: {e:?}");
                            break;
                        },
                    };
                    drop(tun_read);
                    let ip_packet = match etherparse::SlicedPacket::from_ip(tun_read_buf) {
                        Ok(ip_packet) => ip_packet,
                        Err(e) => {
                            error!(">>>> Fail to read ip packet from tun because of error: {e:?}");
                            continue;
                        },
                    };
                    trace!(">>>> Read ip packet from tun: {ip_packet:?}");
                    let ip_header = match ip_packet.ip {
                        Some(ip_header) => {
                            trace!(">>>> Header in ip packet from tun, header: {ip_header:?}");
                            ip_header
                        },
                        None => {
                            error!(">>>> No header in ip packet, skip and read next");
                            continue;
                        },
                    };
                    let transport = match ip_packet.transport {
                        Some(transport) => transport,
                        None => {
                            error!(">>>> No transport in ip packet, skip and read next");
                            continue;
                        },
                    };
                    let (ipv4_header, _ipv4_extension) = match ip_header {
                        Ipv6(_, _) => {
                            trace!(">>>> Can not support ip v6, skip");
                            continue;
                        },

                        Ipv4(header, extension) => (header, extension),
                    };

                    match transport {
                        Icmpv4(icmp_header) => {
                            trace!("Receive icmp v4 packet: {icmp_header:?}");
                            continue;
                        },
                        Icmpv6(icmp_header) => {
                            trace!(">>>> Receive icmp v6 packet: {icmp_header:?}");
                            continue;
                        },
                        Udp(udp_header) => {
                            trace!(">>>> Receive udp packet: {udp_header:?}");

                            let udp_packet_info = UdpPacketInfo {
                                src_addr: ipv4_header.source_addr(),
                                src_port: udp_header.source_port(),
                                dst_addr: ipv4_header.destination_addr(),
                                dst_port: udp_header.destination_port(),
                                payload: ip_packet.payload.to_vec(),
                            };
                            let udp_packet_key = format!("{udp_packet_info}");
                            if let Err(e) = handle_udp_packet(udp_packet_info, tun_write_sender.clone()).await {
                                error!(">>>> Udp socket [{udp_packet_key}] fail to handle tun input because of error: {e:?}");
                            };
                            continue;
                        },
                        Tcp(tcp_header) => {
                            let tcp_connection_key = TcpConnectionKey::new(
                                ipv4_header.source_addr(),
                                tcp_header.source_port(),
                                ipv4_header.destination_addr(),
                                tcp_header.destination_port(),
                            );

                            let mut tcp_connection_handle_repository_lock = tcp_connection_handle_repository.lock().await;
                            let tcp_connection_handle = match tcp_connection_handle_repository_lock.entry(tcp_connection_key) {
                                Occupied(entry) => {
                                    debug!(">>>> Get existing tcp connection [{tcp_connection_key}]");
                                    entry.into_mut()
                                },
                                Vacant(entry) => {
                                    debug!(">>>> Create new tcp connection [{tcp_connection_key}]");
                                    let mut tcp_connection =
                                        TcpConnection::new(tcp_connection_key, tun_write_sender.clone(), tcp_connection_handle_repository.clone());
                                    let handle = entry.insert(tcp_connection.clone_tun_handle());
                                    let tcp_connection_handle_repository = tcp_connection_handle_repository.clone();
                                    tokio::spawn(async move {
                                        if let Err(e) = tcp_connection.process().await {
                                            error!(">>>> Fail to process tcp connection [{tcp_connection_key}] because of error: {e:?}");
                                            let mut tcp_connection_handle_repository_lock = tcp_connection_handle_repository.lock().await;
                                            tcp_connection_handle_repository_lock.remove(&tcp_connection_key);
                                        }
                                    });
                                    handle
                                },
                            };
                            if let Err(e) = tcp_connection_handle.handle_tun_input(tcp_header.to_header(), ip_packet.payload).await {
                                error!(">>>> Tcp connection [{tcp_connection_key}] fail to handle tun input because of error: {e:?}");
                                tcp_connection_handle_repository_lock.remove(&tcp_connection_key);
                            };
                        },
                        Unknown(unknown_protocol) => {
                            error!(">>>> Receive unknown protocol: {unknown_protocol}");
                            continue;
                        },
                    }
                }
            });
            let _ = tokio::join!(tun_read_guard, tun_write_guard);
        });
        info!("Shutdown ppaass vpn server.");
        Ok(())
    }
}
