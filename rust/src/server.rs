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

use anyhow::{anyhow, Context, Result};

use log::{debug, error, info, trace};

use smoltcp::{
    iface::{Config, Interface, SocketHandle, SocketSet, SocketStorage},
    socket::{self, tcp},
    time::Instant,
    wire::{Icmpv4Packet, IpAddress, IpCidr, IpProtocol, IpVersion, Ipv4Address, Ipv4Packet, TcpPacket, UdpPacket},
};
use tokio::runtime::Builder as TokioRuntimeBuilder;
use tokio::{runtime::Runtime as TokioRuntime, sync::Mutex};

use uuid::Uuid;

use crate::{
    device::PpaassVpnDevice,
    tcp::{VpnTcpConnection, VpnTcpConnectionHandle, VpnTcpConnectionKey, VpnTcpConnectionNotification},
    util::print_packet,
};

pub(crate) struct PpaassVpnServer
where
    Self: 'static,
{
    id: String,
    tun_fd: i32,
    vpn_socketset: Arc<Mutex<SocketSet<'static>>>,
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

        Ok(Self {
            id,
            tun_fd,

            vpn_socketset: Arc::new(Mutex::new(SocketSet::new(Vec::new()))),
        })
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
            let mut iface = Self::init_interface(&mut device);

            let mut vpn_tcp_connection_handle_repository: HashMap<VpnTcpConnectionKey, VpnTcpConnectionHandle> = Default::default();
            let mut vpn_tcp_socket_set = SocketSet::new(vec![]);
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
                                return Ok(());
                            }
                            error!(">>>> Fail to read data from tun because of error: {e:?}");
                            return Err(anyhow!("Fail to read data from tun because of error: {e:?}"));
                        },
                    };
                    tun_read_buf
                };
                if let Err(e) = Self::handle_tun_input(
                    &tun_read_buf,
                    &mut vpn_tcp_connection_handle_repository,
                    &mut device,
                    &mut iface,
                    &mut vpn_tcp_socket_set,
                )
                .await
                {
                    error!(">>>> Fail to handle tun input because of error: {e:?}")
                };
            }
        })
    }

    async fn handle_tun_input(
        tun_read_buf: &[u8], vpn_tcp_connection_handle_repository: &mut HashMap<VpnTcpConnectionKey, VpnTcpConnectionHandle>, device: &mut PpaassVpnDevice,
        iface: &mut Interface, vpn_tcp_socket_set: &mut SocketSet<'_>,
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

                let vpn_tcp_connection_handle = match vpn_tcp_connection_handle_repository.entry(vpn_tcp_connection_key) {
                    Occupied(entry) => entry.into_mut(),
                    Vacant(entry) => {
                        let (vpn_tcp_connection, vpn_tcp_connection_handle) = VpnTcpConnection::new(vpn_tcp_connection_key, vpn_tcp_socket_set)?;
                        entry.insert(vpn_tcp_connection_handle);
                        if let Err(e) = vpn_tcp_connection.exec() {
                            error!(">>>> Tcp connection [{vpn_tcp_connection_key}] fail to execute because of error: {e:?}")
                        };
                        vpn_tcp_connection_handle_repository.get_mut(&vpn_tcp_connection_key).unwrap()
                    },
                };

                debug!(
                    ">>>> Tcp connection [{vpn_tcp_connection_key}] push tun data into vpn device:\n{}\n",
                    print_packet(&ipv4_packet)
                );
                device.push_rx(tun_read_buf.to_vec());

                let poll_time = Instant::now();
                if !iface.poll(poll_time, device, vpn_tcp_socket_set) {
                    debug!(">>>> Tcp connection [{vpn_tcp_connection_key}] no tcp socket updated do next loop.");
                    return Ok(());
                };
                debug!(">>>> Tcp connection [{vpn_tcp_connection_key}] socket updated, poll connection.");
                let vpn_tcp_socket = vpn_tcp_socket_set.get::<tcp::Socket>(vpn_tcp_connection_handle.get_socket_handle());

                if let Err(e) = vpn_tcp_connection_handle
                    .poll(VpnTcpConnectionNotification {
                        tcp_socket_state: vpn_tcp_socket.state(),
                        can_receive: vpn_tcp_socket.can_recv(),
                        can_send: vpn_tcp_socket.can_recv(),
                    })
                    .await
                {
                    error!("Fail to poll connection [{vpn_tcp_connection_key}] because of error: {e:?}");
                    return Err(anyhow!("Fail to poll connection [{vpn_tcp_connection_key}] because of error: {e:?}"));
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
