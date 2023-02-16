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

use log::{debug, error, info, trace};

use smoltcp::{
    iface::{Config, Interface, SocketHandle, SocketSet},
    socket::{tcp, Socket},
    time::Instant,
    wire::{Icmpv4Packet, IpAddress, IpCidr, IpProtocol, IpVersion, Ipv4Address, Ipv4Packet, TcpPacket, UdpPacket},
};
use tokio::{
    runtime::Builder as TokioRuntimeBuilder,
    sync::mpsc::{channel, Receiver},
};
use tokio::{runtime::Runtime as TokioRuntime, sync::Mutex};

use uuid::Uuid;

use crate::{
    device::PpaassVpnDevice,
    tcp::{VpnTcpConnection, VpnTcpConnectionKey},
    util::{print_packet, print_packet_bytes},
};

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

    fn init_interface(device: &mut PpaassVpnDevice) -> Interface {
        let mut ifrace_config = Config::default();
        ifrace_config.random_seed = rand::random::<u64>();
        let mut iface = Interface::new(ifrace_config, device);
        iface.set_any_ip(true);
        iface.update_ip_addrs(|ip_addrs| {
            ip_addrs.push(IpCidr::new(IpAddress::v4(0, 0, 0, 1), 24)).unwrap();
        });
        iface.routes_mut().add_default_ipv4_route(Ipv4Address::new(0, 0, 0, 1)).unwrap();
        iface
    }

    fn init_device() -> PpaassVpnDevice {
        PpaassVpnDevice::new()
    }

    fn init_async_runtime() -> TokioRuntime {
        let mut runtime_builder = TokioRuntimeBuilder::new_multi_thread();
        runtime_builder.worker_threads(32).enable_all().thread_name("PPAASS-RUST-THREAD");
        runtime_builder.build().expect("Fail to start vpn runtime.")
    }

    fn init_tun(tun_fd: i32) -> (Arc<Mutex<File>>, Arc<Mutex<File>>) {
        let tun_file = Arc::new(Mutex::new(unsafe { File::from_raw_fd(tun_fd) }));
        let tun_write = tun_file.clone();
        let tun_read = tun_file;
        (tun_read, tun_write)
    }

    pub(crate) fn start(self) -> Result<()> {
        info!("Start ppaass vpn server instance [{}]", self.id);
        let tun_fd = self.tun_fd;
        let async_runtime = Self::init_async_runtime();
        async_runtime.block_on(async move {
            let (tun_read, tun_write) = Self::init_tun(tun_fd);
            let mut device = Self::init_device();
            let iface = Arc::new(Mutex::new(Self::init_interface(&mut device)));
            let device = Arc::new(Mutex::new(device));
            let vpn_tcp_connection_repository: Arc<Mutex<HashMap<VpnTcpConnectionKey, (VpnTcpConnection, Receiver<Vec<u8>>)>>> = Default::default();
            let vpn_tcp_socket_handle_repository: Arc<Mutex<HashMap<SocketHandle, VpnTcpConnectionKey>>> = Default::default();
            let vpn_tcp_socketset = Arc::new(Mutex::new(SocketSet::new(vec![])));

            let iface_rx_guard = {
                let device = device.clone();
                let vpn_tcp_socketset = vpn_tcp_socketset.clone();
                let vpn_tcp_connection_repository = vpn_tcp_connection_repository.clone();
                let iface = iface.clone();
                tokio::spawn(async move {
                    loop {
                        let tun_read_buf = {
                            let mut tun_read_buf = vec![0; 65535];
                            let mut tun_read = tun_read.lock().await;
                            match tun_read.read(&mut tun_read_buf) {
                                Ok(0) => {
                                    return Ok(());
                                },
                                Ok(size) => &tun_read_buf[..size],
                                Err(e) => {
                                    if e.kind() == ErrorKind::WouldBlock {
                                        drop(tun_read);
                                        tokio::time::sleep(Duration::from_millis(100)).await;
                                        continue;
                                    }
                                    error!(">>>> Fail to read data from tun because of error: {e:?}");
                                    return Err(anyhow!("Fail to read data from tun because of error: {e:?}"));
                                },
                            };
                            tun_read_buf
                        };
                        let mut device = device.lock().await;

                        let mut iface = iface.lock().await;
                        if let Err(e) = Self::handle_tun_input(
                            &tun_read_buf,
                            &mut device,
                            &mut iface,
                            vpn_tcp_socketset.clone(),
                            vpn_tcp_connection_repository.clone(),
                        )
                        .await
                        {
                            error!(">>>> Fail to handle tun input because of error: {e:?}")
                        };
                        drop(device);
                        drop(iface);
                        tokio::time::sleep(Duration::from_millis(100)).await;
                    }
                })
            };

            let iface_tx_guard = {
                let device = device.clone();

                tokio::spawn(async move {
                    loop {
                        let mut device = device.lock().await;
                        let Some(ipv4_packet_bytes) = device.pop_tx() else {
                            drop(device);
                            tokio::time::sleep(Duration::from_millis(100)).await;
                            continue;
                        };
                        let mut tun_write = tun_write.lock().await;
                        let ipv4_packet = match Ipv4Packet::new_checked(&ipv4_packet_bytes) {
                            Ok(ipv4_packet) => ipv4_packet,
                            Err(e) => {
                                drop(tun_write);
                                drop(device);
                                tokio::time::sleep(Duration::from_millis(100)).await;
                                continue;
                            },
                        };

                        let transport_protocol = ipv4_packet.next_header();
                        match transport_protocol {
                            IpProtocol::Udp => {
                                debug!("<<<< Ignore udp packet to tun:\n{}\n", print_packet(&ipv4_packet));
                                continue;
                            },
                            IpProtocol::Tcp => {
                                let ipv4_packet_payload = ipv4_packet.payload();
                                let tcp_packet = match TcpPacket::new_checked(ipv4_packet_payload) {
                                    Ok(tcp_packet) => tcp_packet,
                                    Err(e) => {
                                        drop(tun_write);
                                        drop(device);
                                        tokio::time::sleep(Duration::from_millis(100)).await;
                                        continue;
                                    },
                                };
                                let vpn_tcp_connection_key = VpnTcpConnectionKey::new(
                                    ipv4_packet.dst_addr().into(),
                                    tcp_packet.dst_port(),
                                    ipv4_packet.src_addr().into(),
                                    tcp_packet.src_port(),
                                );
                                debug!(
                                    "<<<< Tcp connection [{vpn_tcp_connection_key}] transmit tcp packet to tun:\n{}\n",
                                    print_packet(&ipv4_packet)
                                );
                            },
                            _ => {
                                drop(tun_write);
                                drop(device);
                                tokio::time::sleep(Duration::from_millis(100)).await;
                                continue;
                            },
                        }

                        if let Err(e) = tun_write.write_all(&ipv4_packet_bytes) {
                            error!("<<<< Fail to write device transmit data to tun because of error: {e:?}");
                            drop(device);
                            drop(tun_write);
                            tokio::time::sleep(Duration::from_millis(100)).await;
                            continue;
                        };
                        if let Err(e) = tun_write.flush() {
                            error!("<<<< Fail to flush device transmit data to tun because of error: {e:?}");
                            drop(device);
                            drop(tun_write);
                            tokio::time::sleep(Duration::from_millis(100)).await;
                            continue;
                        };
                        tokio::time::sleep(Duration::from_millis(100)).await;
                    }
                })
            };

            let relay_guard = {
                tokio::spawn(async move {
                    loop {
                        let mut device = device.lock().await;
                        let mut vpn_tcp_socketset = vpn_tcp_socketset.lock().await;
                        let mut iface = iface.lock().await;
                        let current_instant = Instant::now();
                        if !iface.poll(current_instant, &mut *device, &mut vpn_tcp_socketset) {
                            drop(device);
                            drop(vpn_tcp_socketset);
                            drop(iface);
                            tokio::time::sleep(Duration::from_millis(100)).await;
                            continue;
                        };
                        debug!(">>>> Poll iface complete going to check vpn tcp socket read and write");
                        for (vpn_tcp_sockethandle, vpn_tcp_socket) in vpn_tcp_socketset.iter_mut() {
                            let mut vpn_tcp_socket_handle_repository = vpn_tcp_socket_handle_repository.lock().await;
                            let vpn_tcp_connection_key = vpn_tcp_socket_handle_repository.get_mut(&vpn_tcp_sockethandle);
                            let Some(vpn_tcp_connection_key) = vpn_tcp_connection_key else {
                                continue;
                            };
                            let mut vpn_tcp_connection_repository = vpn_tcp_connection_repository.lock().await;
                            let vpn_tcp_connection = vpn_tcp_connection_repository.get_mut(vpn_tcp_connection_key);
                            let Some((vpn_tcp_connection, tun_tx_receiver)) = vpn_tcp_connection else {
                                continue;
                            };
                            match vpn_tcp_socket {
                                Socket::Udp(vpn_udp_socket) => todo!(),
                                Socket::Tcp(vpn_tcp_socket) => {
                                    while vpn_tcp_socket.can_recv() {
                                        debug!(">>>> Tcp connection [{vpn_tcp_connection_key}] can do receive.");
                                        let mut rx_data = [0u8; 65535];
                                        let rx_data_size = match vpn_tcp_socket.recv_slice(&mut rx_data) {
                                            Ok(rx_data_size) => rx_data_size,
                                            Err(e) => {
                                                error!(">>>> Fail to receive tun data because of error: {e:?}");
                                                continue;
                                            },
                                        };
                                        let rx_data = &rx_data[..rx_data_size];
                                        if let Err(e) = vpn_tcp_connection.tun_inbound(rx_data).await {
                                            error!(">>>> Fail to receive forward tun data to connection inbound because of error: {e:?}");
                                            continue;
                                        };
                                    }
                                    while vpn_tcp_socket.can_send() {
                                        debug!("<<<< Tcp connection [{vpn_tcp_connection_key}] can do send.");
                                        let tun_tx_data = match tun_tx_receiver.try_recv() {
                                            Ok(data) => data,
                                            Err(Empty) => {
                                                continue;
                                            },
                                            Err(Disconnect) => {
                                                break;
                                            },
                                        };
                                        if let Err(e) = vpn_tcp_socket.send_slice(&tun_tx_data) {
                                            error!("<<<< Fail to forward destination data to vpn device because of error: {e:?}")
                                        };
                                    }
                                },
                                _ => continue,
                            };
                        }
                        drop(device);
                        drop(vpn_tcp_socketset);
                        drop(iface);
                        tokio::time::sleep(Duration::from_millis(100)).await;
                    }
                })
            };
            let _ = tokio::join!(iface_rx_guard, iface_tx_guard, relay_guard);
        });
        Ok(())
    }

    async fn handle_tun_input(
        tun_read_buf: &[u8], device: &mut PpaassVpnDevice, iface: &mut Interface, vpn_tcp_socketset: Arc<Mutex<SocketSet<'static>>>,
        vpn_tcp_connection_repository: Arc<Mutex<HashMap<VpnTcpConnectionKey, (VpnTcpConnection, Receiver<Vec<u8>>)>>>,
    ) -> Result<()> {
        let ip_version = IpVersion::of_packet(tun_read_buf).map_err(|e| {
            error!(">>>> Fail to parse ip version from tun rx data because of error: {e:?}");
            anyhow!("Fail to parse ip version from tun rx data because of error: {e:?}")
        })?;
        if IpVersion::Ipv6 == ip_version {
            trace!(">>>> Do not support ip v6");
            return Ok(());
        }
        let ipv4_packet = Ipv4Packet::new_checked(tun_read_buf).map_err(|e| {
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

                let vpn_tcp_connection_key = VpnTcpConnectionKey::new(
                    ipv4_packet.src_addr().into(),
                    tcp_packet.src_port(),
                    ipv4_packet.dst_addr().into(),
                    tcp_packet.dst_port(),
                );

                let mut vpn_tcp_connection_repository = vpn_tcp_connection_repository.lock().await;
                let (vpn_tcp_connection, tun_tx_receiver) = match vpn_tcp_connection_repository.entry(vpn_tcp_connection_key) {
                    Occupied(entry) => entry.into_mut(),
                    Vacant(entry) => {
                        let (tun_tx_sender, tun_tx_receiver) = channel::<Vec<u8>>(1024);
                        let (vpn_tcp_connection, socket_handle) =
                            VpnTcpConnection::new(vpn_tcp_connection_key, vpn_tcp_socketset.clone(), tun_tx_sender).await?;
                        entry.insert((vpn_tcp_connection, tun_tx_receiver))
                    },
                };

                debug!(
                    ">>>> Tcp connection [{vpn_tcp_connection_key}] push tun data into vpn device:\n{}\n",
                    print_packet(&ipv4_packet)
                );
                device.push_rx(tun_read_buf.to_vec());

                let poll_time = Instant::now();
                let mut vpn_tcp_socketset = vpn_tcp_socketset.lock().await;
                iface.poll(poll_time, device, &mut vpn_tcp_socketset);
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
