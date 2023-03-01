use std::{
    collections::{
        hash_map::Entry::{self, Vacant},
        HashMap,
    },
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

use smoltcp::socket::tcp::Socket as SmoltcpTcpSocket;

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
    tcp::{TcpConnection, TcpConnectionKey},
    util::{print_packet, print_packet_bytes},
};

struct TcpConnectionWrapper {
    tcp_connection: TcpConnection,
    tun_input_sender: Sender<Vec<u8>>,
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
        f.debug_struct("PpaassVpnServer")
            .field("id", &self.id)
            .field("tun_fd", &self.tun_fd)
            .finish()
    }
}

impl PpaassVpnServer {
    pub(crate) fn new(tun_fd: i32) -> Result<Self> {
        let id = Uuid::new_v4().to_string();
        let id = id.replace('-', "");

        info!("Create ppaass vpn server instance [{id}]");

        Ok(Self { id, tun_fd })
    }

    fn init() -> (
        TokioRuntime,
        Arc<TokioMutex<Interface>>,
        Arc<TokioMutex<PpaassVpnDevice>>,
        Arc<TokioMutex<SocketSet<'static>>>,
    ) {
        let mut device = PpaassVpnDevice::new();
        let mut ifrace_config = Config::default();
        ifrace_config.random_seed = rand::random::<u64>();
        let mut iface = Interface::new(ifrace_config, &mut device);
        iface.set_any_ip(true);
        iface.update_ip_addrs(|ip_addrs| {
            ip_addrs
                .push(IpCidr::new(IpAddress::v4(0, 0, 0, 1), 24))
                .unwrap();
        });
        iface
            .routes_mut()
            .add_default_ipv4_route(Ipv4Address::new(0, 0, 0, 1))
            .unwrap();
        let mut runtime_builder = TokioRuntimeBuilder::new_multi_thread();
        runtime_builder
            .worker_threads(256)
            .enable_all()
            .thread_name("PPAASS-RUST-THREAD");
        // .unhandled_panic(UnhandledPanic::ShutdownRuntime);
        let tokio_rutime = runtime_builder.build().expect("Fail to start vpn runtime.");

        (
            tokio_rutime,
            Arc::new(TokioMutex::new(iface)),
            Arc::new(TokioMutex::new(device)),
            Arc::new(TokioMutex::new(SocketSet::new(vec![]))),
        )
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
        let socket_handle_mapping: Arc<TokioMutex<HashMap<SocketHandle, TcpConnectionKey>>> = Default::default();

        tokio_runtime.block_on(async move {
            let tx_guard = {
                let server_id = server_id.clone();
                let device = device.clone();
                tokio::spawn(async move {
                    info!("Start task for write data to tun device on server [{server_id}]");
                    loop {
                        let data = {
                            let mut device = device.lock().await;
                            match device.pop_tx() {
                                Some(data) => data,
                                None => {
                                    drop(device);
                                    trace!("<<<< No data in device tx queue, wait for a momoent... ");
                                    tokio::time::sleep(TokioDuration::from_millis(100)).await;
                                    continue;
                                }
                            }
                        };
                        let mut tun_write = tun_write.lock().await;
                        debug!(
                            "<<<< Write data to tun:\n{}\n",
                            print_packet_bytes::<Ipv4Packet<&'static [u8]>>(&data)
                        );
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
                tokio::spawn(async move {
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
                                        trace!(">>>> No data in tun, wait for a momoent... ");
                                        tokio::time::sleep(TokioDuration::from_millis(100)).await;
                                        continue;
                                    }
                                    _ => {
                                        error!(">>>> Fail to read tun data because of error: {e:?}");
                                        return Err(anyhow!(
                                            ">>>> Fail to read tun data because of error: {e:?}"
                                        ));
                                    }
                                },
                            };
                            tun_read_buf[..size].to_vec()
                        };
                        Self::pre_poll_handle_tun_input(
                            &tun_data,
                            iface.clone(),
                            device.clone(),
                            sockets.clone(),
                            tcp_connections.clone(),
                            socket_handle_mapping.clone(),
                        )
                        .await?;
                        let mut device = device.lock().await;
                        device.push_rx(tun_data);
                        let mut iface = iface.lock().await;
                        let mut sockets = sockets.lock().await;
                        let socket_update = iface.poll(SmoltcpInstant::now(), &mut *device, &mut sockets);
                        if !socket_update {
                            return Ok(());
                        }
                        for (handle, socket) in sockets.iter_mut() {
                            match socket {
                                Socket::Raw(_) => todo!(),
                                Socket::Icmp(_) => todo!(),
                                Socket::Udp(_) => todo!(),
                                Socket::Tcp(tcp_socket) => {
                                    if let Err(e) = Self::post_poll_handle_tcp_socket(
                                        tcp_connections.clone(),
                                        socket_handle_mapping.clone(),
                                        tcp_socket,
                                        handle,
                                    )
                                    .await
                                    {
                                        error!(">>>> Fail to post poll handle [handle={handle}] tcp socket because of error: {e:?}");
                                    };
                                }
                                Socket::Dhcpv4(_) => todo!(),
                                Socket::Dns(_) => todo!(),
                            }
                        }
                    }
                    Ok(())
                })
            };
            tokio::join!(rx_guard, tx_guard);
        });

        Ok(())
    }

    async fn pre_poll_handle_tun_input(
        tun_data: &[u8],
        iface: Arc<TokioMutex<Interface>>,
        device: Arc<TokioMutex<PpaassVpnDevice>>,
        sockets: Arc<TokioMutex<SocketSet<'static>>>,
        tcp_connections: Arc<TokioMutex<HashMap<TcpConnectionKey, TcpConnectionWrapper>>>,
        socket_handle_mapping: Arc<TokioMutex<HashMap<SocketHandle, TcpConnectionKey>>>,
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

                debug!(
                    ">>>> Receive tcp packet from tun:\n{}\n",
                    print_packet(&ipv4_packet)
                );

                let tcp_connection_key = TcpConnectionKey::new(
                    ipv4_packet.src_addr().into(),
                    tcp_packet.src_port(),
                    ipv4_packet.dst_addr().into(),
                    tcp_packet.dst_port(),
                );

                {
                    let mut tcp_connections = tcp_connections.lock().await;
                    if let Entry::Vacant(entry) = tcp_connections.entry(tcp_connection_key) {
                        debug!("Create a new tcp connection: {tcp_connection_key}");
                        let (tun_input_sender, tun_input_receiver) = channel(1024);
                        let tcp_connection = TcpConnection::new(
                            tcp_connection_key,
                            iface,
                            device,
                            sockets,
                            tun_input_receiver,
                        )
                        .await?;
                        let mut socket_handle_mapping = socket_handle_mapping.lock().await;
                        socket_handle_mapping.insert(tcp_connection.get_socket_handle(), tcp_connection_key);

                        let entry = entry.insert(TcpConnectionWrapper {
                            tcp_connection,
                            tun_input_sender,
                        });
                        entry.tcp_connection.start().await;
                    };
                }
            }
            IpProtocol::Udp => {
                let ipv4_packet_payload = ipv4_packet.payload();
                let udp_packet = UdpPacket::new_checked(ipv4_packet_payload).map_err(|e| {
                    error!(">>>> Fail to parse ip v4 packet payload to udp packet because of error: {e:?}");
                    anyhow!("Fail to parse ip v4 packet payload to udp packet because of error: {e:?}")
                })?;
                debug!(
                    ">>>> Receive udp packet from tun:\n{}\n",
                    print_packet(&ipv4_packet)
                );
            }
            IpProtocol::Icmp => {
                let ipv4_packet_payload = ipv4_packet.payload();
                let icmpv4_packet = Icmpv4Packet::new_checked(ipv4_packet_payload).map_err(|e| {
                    error!(">>>> Fail to parse ip v4 packet payload to icmpv4 packet because of error: {e:?}");
                    anyhow!("Fail to parse ip v4 packet payload to icmpv4 packet because of error: {e:?}")
                })?;
                debug!(
                    ">>>> Receive icmpv4 packet from tun:\n{}\n",
                    print_packet(&ipv4_packet)
                );
            }
            other => {
                trace!(">>>> Unsupport protocol: {other}");
            }
        };

        Ok(())
    }

    async fn post_poll_handle_tcp_socket(
        tcp_connections: Arc<TokioMutex<HashMap<TcpConnectionKey, TcpConnectionWrapper>>>,
        socket_handle_mapping: Arc<TokioMutex<HashMap<SocketHandle, TcpConnectionKey>>>,
        tcp_socket: &mut SmoltcpTcpSocket<'static>,
        handle: SocketHandle,
    ) -> Result<()> {
        let tcp_connection_key = {
            let socket_handle_mapping = socket_handle_mapping.lock().await;
            let tcp_connection_key = socket_handle_mapping.get(&handle);
            let Some(tcp_connection_key) = tcp_connection_key else {
                error!(">>>> Can not find tcp connection socket mapping by handle: {handle}");
                return Err(anyhow!("Can not find tcp connection socket mapping by handle: {handle}"));
            };
            *tcp_connection_key
        };
        if !tcp_socket.is_active() {
            let mut socket_handle_mapping = socket_handle_mapping.lock().await;
            let tcp_connection_key = socket_handle_mapping.remove(&handle);
            if let Some(tcp_connection_key) = tcp_connection_key {
                let mut tcp_connections = tcp_connections.lock().await;
                tcp_connections.remove(&tcp_connection_key);
                debug!(">>>> Tcp connection [{tcp_connection_key}] is inactive, remove it.");
            }
            return Ok(());
        }
        while tcp_socket.can_recv() {
            debug!(">>>> Tcp connection [{tcp_connection_key}] become can receive.");
            let mut receive_data = [0u8; 65535];
            let size = match tcp_socket.recv_slice(&mut receive_data) {
                Ok(size) => size,
                Err(e) => {
                    error!(">>>> Tcp connection [{tcp_connection_key}] fail to receive tun input because of error: {e:?}");
                    return Err(anyhow!(
                        "Tcp connection [{tcp_connection_key}] fail to receive tun input because of error: {e:?}"
                    ));
                }
            };
            let receive_data = &receive_data[0..size];
            let tcp_connections = tcp_connections.lock().await;
            let tcp_connection_wrapper = tcp_connections.get(&tcp_connection_key);
            let Some(tcp_connection_wrapper) = tcp_connection_wrapper else {
                error!(">>>> Tcp connection [{tcp_connection_key}] can not found form repository.");
               return Err(anyhow!("Tcp connection [{tcp_connection_key}] can not found form repository."));
            };
            if let Err(e) = tcp_connection_wrapper
                .tun_input_sender
                .send(receive_data.to_vec())
                .await
            {
                error!(">>>> Tcp connection [{tcp_connection_key}] fail to send tun input to tcp connection because of error: {e:?}");
                return Err(anyhow!(
                    "Tcp connection [{tcp_connection_key}] fail to send tun input to tcp connection because of error: {e:?}"
                ));
            }
        }
        Ok(())
    }
}
