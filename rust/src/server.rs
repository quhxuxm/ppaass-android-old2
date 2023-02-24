use std::{
    collections::{hash_map::Entry::Vacant, HashMap},
    fmt::Debug,
    fs::File,
    io::{ErrorKind, Read, Write},
    net::{IpAddr, SocketAddr},
    os::fd::FromRawFd,
    sync::Arc,
};

use anyhow::{anyhow, Result};

use log::{debug, error, info, trace};

use pretty_hex::pretty_hex;
use smoltcp::{
    iface::{Config, Interface, SocketHandle, SocketSet},
    socket::{
        self,
        tcp::{SocketBuffer, State},
        Socket,
    },
    time::Instant as SmoltcpInstant,
    wire::{Icmpv4Packet, IpAddress, IpCidr, IpProtocol, IpVersion, Ipv4Address, Ipv4Packet, TcpPacket, UdpPacket},
};

use tokio::{
    runtime::Builder as TokioRuntimeBuilder,
    sync::{
        mpsc::{channel, error::TryRecvError, Receiver},
        Mutex as TokioMutex,
    },
    time::Duration as TokioDuration,
    time::Instant as TokioInstant,
};
use tokio::{runtime::Runtime as TokioRuntime, sync::mpsc::Sender};
use uuid::Uuid;

use crate::{
    device::PpaassVpnDevice,
    tcp::{TcpConnection, TcpConnectionKey, TcpConnectionToTunCommand},
    util::{print_packet, print_packet_bytes},
};

struct TcpConnectionWrapper {
    tcp_connection: TcpConnection,
}

pub(crate) struct PpaassVpnServer
where
    Self: 'static,
{
    id: String,
    tun_fd: i32,
}

impl Debug for PpaassVpnServer {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("PpaassVpnServer").field("id", &self.id).field("tun_fd", &self.tun_fd).finish()
    }
}

impl PpaassVpnServer {
    pub(crate) fn new(tun_fd: i32) -> Result<Self> {
        let id = Uuid::new_v4().to_string();
        let id = id.replace('-', "");

        info!("Create ppaass vpn server instance [{id}]");

        Ok(Self { id, tun_fd })
    }

    fn init() -> (TokioRuntime, Arc<TokioMutex<Interface>>, Arc<TokioMutex<PpaassVpnDevice>>, Arc<TokioMutex<SocketSet<'static>>>) {
        let mut device = PpaassVpnDevice::new();
        let mut ifrace_config = Config::default();
        ifrace_config.random_seed = rand::random::<u64>();
        let mut iface = Interface::new(ifrace_config, &mut device);
        iface.set_any_ip(true);
        iface.update_ip_addrs(|ip_addrs| {
            ip_addrs.push(IpCidr::new(IpAddress::v4(0, 0, 0, 1), 24)).unwrap();
        });
        iface.routes_mut().add_default_ipv4_route(Ipv4Address::new(0, 0, 0, 1)).unwrap();
        let mut runtime_builder = TokioRuntimeBuilder::new_multi_thread();
        runtime_builder.worker_threads(32).enable_all().thread_name("PPAASS-RUST-THREAD");
        // .unhandled_panic(UnhandledPanic::ShutdownRuntime);
        let tokio_rutime = runtime_builder.build().expect("Fail to start vpn runtime.");
        let sockets = SocketSet::new(vec![]);
        (tokio_rutime, Arc::new(TokioMutex::new(iface)), Arc::new(TokioMutex::new(device)), Arc::new(TokioMutex::new(sockets)))
    }

    fn split_tun(tun_fd: i32) -> (Arc<TokioMutex<File>>, Arc<TokioMutex<File>>) {
        let tun_read = Arc::new(TokioMutex::new(unsafe { File::from_raw_fd(tun_fd) }));
        let tun_write = tun_read.clone();
        (tun_read, tun_write)
    }

    pub(crate) fn start(self) -> Result<()> {
        let server_id = self.id.clone();
        info!("Start ppaass vpn server instance [{server_id}]");
        let tun_fd = self.tun_fd;
        let (tun_read, tun_write) = Self::split_tun(tun_fd);
        info!("Ready to prepare tun read & write");

        let (tokio_runtime, iface, device, sockets) = Self::init();
        let tcp_connections: Arc<TokioMutex<HashMap<TcpConnectionKey, TcpConnectionWrapper>>> = Default::default();
        let socket_handles_mapping: Arc<TokioMutex<HashMap<SocketHandle, TcpConnectionKey>>> = Default::default();
        let tx_guard = {
            let server_id = server_id.clone();
            let device = device.clone();
            tokio_runtime.spawn(async move {
                info!("Start task for write data to tun device on server [{server_id}]");
                loop {
                    let data = {
                        let mut device = device.lock().await;
                        match device.pop_tx() {
                            Some(data) => data,
                            None => {
                                drop(device);
                                debug!("<<<< No data in device tx queue, wait for a momoent... ");
                                tokio::time::sleep(TokioDuration::from_millis(100)).await;
                                continue;
                            },
                        }
                    };
                    let mut tun_write = tun_write.lock().await;
                    debug!("<<<< Write data to tun:\n{}\n", print_packet_bytes::<Ipv4Packet<&'static [u8]>>(&data));
                    if let Err(e) = tun_write.write(&data) {
                        error!("<<<< Fail to write data to tun because of error: {e:?}");
                        continue;
                    };
                    if let Err(e) = tun_write.flush() {
                        error!("<<<< Fail to flush data to tun because of error: {e:?}");
                        continue;
                    };
                }
            })
        };
        let rx_guard = {
            tokio_runtime.spawn(async move {
                info!("Start task for read data from tun device on server [{server_id}]");
                loop {
                    let tun_data = {
                        let mut tun_read = tun_read.lock().await;
                        let mut tun_read_buf = [0u8; 65535];
                        let size = match tun_read.read(&mut tun_read_buf) {
                            Ok(size) => size,
                            Err(e) => match e.kind() {
                                ErrorKind::WouldBlock => {
                                    drop(tun_read);
                                    debug!(">>>> No data in tun, wait for a momoent... ");
                                    tokio::time::sleep(TokioDuration::from_millis(100)).await;
                                    continue;
                                },
                                _ => {
                                    error!(">>>> Fail to read tun data because of error: {e:?}");
                                    return Err(anyhow!(">>>> Fail to read tun data because of error: {e:?}"));
                                },
                            },
                        };
                        tun_read_buf[..size].to_vec()
                    };
                    let mut tcp_connections = tcp_connections.lock().await;
                    let mut socket_handles_mapping = socket_handles_mapping.lock().await;
                    let mut sockets = sockets.lock().await;
                    Self::check_and_prepare_tcp_connection(&tun_data, &mut tcp_connections, &mut socket_handles_mapping, &mut sockets)?;
                    let mut device = device.lock().await;
                    device.push_rx(tun_data);
                }
                Ok(())
            })
        };

        Ok(())
    }

    fn check_and_prepare_tcp_connection(
        tun_data: &[u8],
        tcp_connections: &mut HashMap<TcpConnectionKey, TcpConnectionWrapper>,
        socket_handles_mapping: &mut HashMap<SocketHandle, TcpConnectionKey>,
        sockets: &mut SocketSet<'static>,
    ) -> Result<()> {
        let ip_version = IpVersion::of_packet(tun_data).map_err(|e| {
            error!(">>>> Fail to parse ip version from tun rx data because of error: {e:?}");
            anyhow!("Fail to parse ip version from tun rx data because of error: {e:?}")
        })?;
        if IpVersion::Ipv6 == ip_version {
            trace!(">>>> Do not support ip v6");
            return Ok(());
        }
        let ipv4_packet = Ipv4Packet::new_checked(tun_data).map_err(|e| {
            error!(">>>> Fail to parse ip v4 packet from tun rx data because of error: {e:?}");
            anyhow!("Fail to parse ip v4 packet from tun rx data because of error: {e:?}")
        })?;

        let transport_protocol = ipv4_packet.next_header();
        match transport_protocol {
            IpProtocol::Tcp => {
                let ipv4_packet_payload = ipv4_packet.payload();
                let tcp_packet = TcpPacket::new_checked(ipv4_packet_payload).map_err(|e| {
                    error!(">>>> Fail to parse ip v4 packet payload to tcp packet because of error: {e:?}");
                    anyhow!("Fail to parse ip v4 packet payload to tcp packet because of error: {e:?}")
                })?;

                debug!(">>>> Receive tcp packet from tun:\n{}\n", print_packet(&ipv4_packet));

                let tcp_connection_key =
                    TcpConnectionKey::new(ipv4_packet.src_addr().into(), tcp_packet.src_port(), ipv4_packet.dst_addr().into(), tcp_packet.dst_port());

                if let Vacant(entry) = tcp_connections.entry(tcp_connection_key) {
                    let tcp_connection = TcpConnection::new(tcp_connection_key)?;
                    entry.insert(TcpConnectionWrapper { tcp_connection });
                };
            },
            IpProtocol::Udp => {
                let ipv4_packet_payload = ipv4_packet.payload();
                let udp_packet = UdpPacket::new_checked(ipv4_packet_payload).map_err(|e| {
                    error!(">>>> Fail to parse ip v4 packet payload to udp packet because of error: {e:?}");
                    anyhow!("Fail to parse ip v4 packet payload to udp packet because of error: {e:?}")
                })?;
                debug!(">>>> Receive udp packet from tun:\n{}\n", print_packet(&ipv4_packet));
            },
            IpProtocol::Icmp => {
                let ipv4_packet_payload = ipv4_packet.payload();
                let icmpv4_packet = Icmpv4Packet::new_checked(ipv4_packet_payload).map_err(|e| {
                    error!(">>>> Fail to parse ip v4 packet payload to icmpv4 packet because of error: {e:?}");
                    anyhow!("Fail to parse ip v4 packet payload to icmpv4 packet because of error: {e:?}")
                })?;
                debug!(">>>> Receive icmpv4 packet from tun:\n{}\n", print_packet(&ipv4_packet));
            },
            other => {
                trace!(">>>> Unsupport protocol: {other}");
            },
        };

        Ok(())
    }
}
