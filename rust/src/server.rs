use std::{
    cell::RefCell,
    collections::{
        hash_map::Entry::{Occupied, Vacant},
        HashMap,
    },
    fmt::Debug,
    fs::File,
    io::{ErrorKind, Read, Write},
    os::fd::FromRawFd,
    sync::Arc,
    time::{Duration, SystemTime},
};

use anyhow::{anyhow, Result};

use log::{debug, error, info, trace};

use smoltcp::{
    iface::{Config, Interface, SocketHandle, SocketSet},
    socket::{tcp, Socket},
    time::Instant as SmoltcpInstant,
    wire::{Icmpv4Packet, IpAddress, IpCidr, IpProtocol, IpVersion, Ipv4Address, Ipv4Packet, TcpPacket, UdpPacket},
};
use std::sync::Mutex as StdMutex;
use tokio::{
    runtime::Builder as TokioRuntimeBuilder,
    sync::mpsc::{channel, Receiver},
    time::Duration as TokioDuration,
    time::Instant as TokioInstant,
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

    fn init_tun_read_write(tun_fd: i32) -> (Arc<StdMutex<File>>, Arc<StdMutex<File>>) {
        let tun_file = Arc::new(StdMutex::new(unsafe { File::from_raw_fd(tun_fd) }));
        let tun_write = tun_file.clone();
        let tun_read = tun_file;
        (tun_read, tun_write)
    }

    pub(crate) fn start(self) -> Result<()> {
        info!("Start ppaass vpn server instance [{}]", self.id);
        let tun_fd = self.tun_fd;
        let async_runtime = Self::init_async_runtime();
        let (tun_read, tun_write) = Self::init_tun_read_write(tun_fd);
        async_runtime.block_on(async move {
            let tcp_connection_repository: HashMap<VpnTcpConnectionKey, VpnTcpConnection> = Default::default();

            let mut device = Self::init_device();
            let mut iface = Self::init_interface(&mut device);
            let mut sockets = SocketSet::new(vec![]);

            loop {
                let poll_time = SmoltcpInstant::now();
                let sockets_updated = iface.poll(poll_time, &mut device, &mut sockets);
                let wait_until = match iface.poll_delay(poll_time, &sockets) {
                    Some(delay) => TokioInstant::now() + TokioDuration::from_millis(delay.total_millis()),
                    None => TokioInstant::now(),
                };
                if !sockets_updated {
                    tokio::time::sleep_until(wait_until).await;
                    continue;
                }
                //Do something
                tokio::time::sleep_until(wait_until).await;
            }
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
