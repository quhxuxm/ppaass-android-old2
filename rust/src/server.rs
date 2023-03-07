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
        Mutex as TokioMutex, Notify,
    },
    time::Duration as TokioDuration,
    time::Instant as TokioInstant,
};
use tokio::{runtime::Runtime as TokioRuntime, sync::mpsc::Sender};
use uuid::Uuid;

use crate::{
    device::VirtualDevice,
    tcp::{TcpConnection, TcpConnectionId},
    util::{print_packet, print_packet_bytes},
};

struct TcpConnectionWrapper {
    tcp_connection: TcpConnection,
    device_data_sender: Sender<Vec<u8>>,
}

pub(crate) struct PpaassVpnServer
where
    Self: 'static,
{
    id: String,
    device_fd: i32,
    tcp_connections: Arc<TokioMutex<HashMap<TcpConnectionId, TcpConnectionWrapper>>>,
}

impl Debug for PpaassVpnServer {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("PpaassVpnServer")
            .field("id", &self.id)
            .field("device_fd", &self.device_fd)
            .finish()
    }
}

impl PpaassVpnServer {
    pub(crate) fn new(device_fd: i32) -> Result<Self> {
        let id = Uuid::new_v4().to_string();
        let id = id.replace('-', "");

        info!("Create ppaass vpn server instance [{id}]");
        let tcp_connections: Arc<TokioMutex<HashMap<TcpConnectionId, TcpConnectionWrapper>>> = Default::default();
        Ok(Self {
            id,
            device_fd,
            tcp_connections,
        })
    }

    fn init() -> (
        TokioRuntime,
        Arc<TokioMutex<Interface>>,
        Arc<TokioMutex<VirtualDevice>>,
        Arc<TokioMutex<SocketSet<'static>>>,
    ) {
        let mut virtual_device = VirtualDevice::new();
        let mut virtual_iface_config = Config::default();
        virtual_iface_config.random_seed = rand::random::<u64>();
        let mut virtual_iface = Interface::new(virtual_iface_config, &mut virtual_device);
        virtual_iface.set_any_ip(true);
        virtual_iface.update_ip_addrs(|ip_addrs| {
            ip_addrs
                .push(IpCidr::new(IpAddress::v4(0, 0, 0, 1), 24))
                .unwrap();
        });
        virtual_iface
            .routes_mut()
            .add_default_ipv4_route(Ipv4Address::new(0, 0, 0, 1))
            .unwrap();
        let mut async_runtime_builder = TokioRuntimeBuilder::new_multi_thread();
        async_runtime_builder
            .worker_threads(256)
            .enable_all()
            .thread_name("PPAASS-RUST-THREAD");
        let async_rutime = async_runtime_builder
            .build()
            .expect("Fail to start vpn runtime.");

        (
            async_rutime,
            Arc::new(TokioMutex::new(virtual_iface)),
            Arc::new(TokioMutex::new(virtual_device)),
            Arc::new(TokioMutex::new(SocketSet::new(vec![]))),
        )
    }

    fn split_device_file(device_fd: i32) -> (Arc<TokioMutex<File>>, Arc<TokioMutex<File>>) {
        let device_file_read = Arc::new(TokioMutex::new(unsafe { File::from_raw_fd(device_fd) }));
        let device_file_write = device_file_read.clone();
        (device_file_read, device_file_write)
    }

    pub(crate) fn start(self) -> Result<()> {
        let server_id = self.id.clone();
        info!("Start ppaass vpn server instance [{server_id}]");
        let device_fd = self.device_fd;
        let (device_file_read, device_file_write) = Self::split_device_file(device_fd);
        info!("Ready to prepare device file read and write");

        let (async_runtime, virtual_iface, virtual_device, virtual_sockets) = Self::init();
        let write_device_notifier = Arc::new(Notify::new());
        let poll_iface_notifier = Arc::new(Notify::new());

        let virtial_socket_handle_mapping: Arc<TokioMutex<HashMap<SocketHandle, &TcpConnectionWrapper>>> = Default::default();

        let poll_iface_task = {
            let poll_iface_notifier = poll_iface_notifier.clone();
            let virtual_iface = virtual_iface.clone();
            let virtual_device = virtual_device.clone();
            let virtual_sockets = virtual_sockets.clone();
            async move {
                loop {
                    poll_iface_notifier.notified().await;
                    let mut virtual_iface = virtual_iface.lock().await;
                    let mut virtual_device = virtual_device.lock().await;
                    let mut virtual_sockets = virtual_sockets.lock().await;
                    let updated = virtual_iface.poll(
                        SmoltcpInstant::now(),
                        &mut *virtual_device,
                        &mut virtual_sockets,
                    );
                    if !updated {
                        continue;
                    }
                    for (virtual_socket_handle, virtual_socket) in virtual_sockets.iter_mut() {
                        match virtual_socket {
                            Socket::Raw(virtual_socket) => todo!(),
                            Socket::Icmp(virtual_socket) => todo!(),
                            Socket::Udp(virtual_socket) => todo!(),
                            Socket::Tcp(virtual_socket) => {
                                if !virtual_socket.may_recv() {
                                    continue;
                                }
                                loop {
                                    let mut device_data = [0u8; 65535];
                                    let size = match virtual_socket.recv_slice(&mut device_data) {
                                        Ok(size) => size,
                                        Err(e) => {
                                            error!(">>>> Fail to receive data from device because of error: {e:?}");
                                            break;
                                        }
                                    };
                                    let device_data = &device_data[..size];
                                }
                            }
                            Socket::Dhcpv4(virtual_socket) => todo!(),
                            Socket::Dns(virtual_socket) => todo!(),
                        }
                    }
                }
            }
        };
        let write_device_task = {
            let write_device_notifier = write_device_notifier.clone();
            let server_id = server_id.clone();
            let virtual_device = virtual_device.clone();
            async move {
                let server_id = server_id.clone();

                info!("Start task for write data to tun device on server [{server_id}]");
                loop {
                    write_device_notifier.notified().await;
                    while let Some(data) = {
                        let mut virtual_device = virtual_device.lock().await;
                        virtual_device.pop_tx()
                    } {
                        let mut device_file_write = device_file_write.lock().await;
                        debug!(
                            "<<<< Write data to tun:\n{}\n",
                            print_packet_bytes::<Ipv4Packet<&'static [u8]>>(&data)
                        );
                        if let Err(e) = device_file_write.write(&data) {
                            error!("<<<< Fail to write data to tun because of error: {e:?}");
                            continue;
                        };
                        if let Err(e) = device_file_write.flush() {
                            error!("<<<< Fail to flush data to tun because of error: {e:?}");
                            continue;
                        };
                    }
                }
            }
        };

        let read_device_task = {
            let tcp_connections = self.tcp_connections.clone();
            let server_id = server_id.clone();
            let virtual_device = virtual_device.clone();
            let write_device_notifier = write_device_notifier.clone();
            async move {
                info!("Start task for read data from tun device on server [{server_id}]");
                loop {
                    let device_data = {
                        let mut device_file_read = device_file_read.lock().await;
                        let mut device_data = [0u8; 65535];
                        let size = match device_file_read.read(&mut device_data) {
                            Ok(size) => size,
                            Err(e) => match e.kind() {
                                ErrorKind::WouldBlock => {
                                    drop(device_file_read);
                                    trace!(">>>> No data in tun, wait for a momoent... ");
                                    tokio::time::sleep(TokioDuration::from_millis(100)).await;
                                    continue;
                                }
                                _ => {
                                    error!(">>>> Fail to read tun data because of error: {e:?}");
                                    return;
                                }
                            },
                        };
                        device_data[..size].to_vec()
                    };
                    if let Err(e) = Self::handle_device_data(
                        &device_data,
                        virtual_iface.clone(),
                        virtual_device.clone(),
                        virtual_sockets.clone(),
                        tcp_connections.clone(),
                        virtial_socket_handle_mapping.clone(),
                    )
                    .await
                    {
                        error!(">>>> Fail to handle device data because of error: {e:?}")
                    };
                }
            }
        };

        async_runtime.block_on(async move {
            tokio::join!(poll_iface_task, write_device_task, read_device_task);
        });

        Ok(())
    }

    async fn handle_device_data(
        device_data: &[u8],
        virtual_iface: Arc<TokioMutex<Interface>>,
        virtual_device: Arc<TokioMutex<VirtualDevice>>,
        virtual_sockets: Arc<TokioMutex<SocketSet<'static>>>,
        tcp_connections: Arc<TokioMutex<HashMap<TcpConnectionId, TcpConnectionWrapper>>>,
        virtual_socket_handle_mapping: Arc<TokioMutex<HashMap<SocketHandle, &TcpConnectionWrapper>>>,
        poll_ifrace_notifier: Arc<Notify>,
    ) -> Result<()> {
        let ip_version = IpVersion::of_packet(device_data).map_err(|e| {
            error!(">>>> Fail to parse ip version from tun rx data because of error: {e:?}");
            anyhow!("Fail to parse ip version from tun rx data because of error: {e:?}")
        })?;
        if IpVersion::Ipv6 == ip_version {
            trace!(">>>> Do not support ip v6");
            return Ok(());
        }
        let ipv4_packet = Ipv4Packet::new_checked(device_data).map_err(|e| {
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

                let tcp_connection_id = TcpConnectionId::new(
                    ipv4_packet.src_addr().into(),
                    tcp_packet.src_port(),
                    ipv4_packet.dst_addr().into(),
                    tcp_packet.dst_port(),
                );

                {
                    let mut tcp_connections = tcp_connections.lock().await;
                    if let Entry::Vacant(entry) = tcp_connections.entry(tcp_connection_id) {
                        debug!("Create a new tcp connection: {tcp_connection_id}");
                        let (device_data_sender, device_data_receiver) = channel(1024);
                        let tcp_connection = TcpConnection::new(
                            tcp_connection_id,
                            virtual_iface,
                            virtual_device,
                            virtual_sockets,
                            device_data_receiver,
                            poll_ifrace_notifier.clone(),
                        )
                        .await?;
                        let virtual_socket_handle = tcp_connection.get_socket_handle();
                        let entry = entry.insert(TcpConnectionWrapper {
                            tcp_connection,
                            device_data_sender,
                        });
                        let mut virtial_socket_handle_mapping = virtual_socket_handle_mapping.lock().await;
                        virtial_socket_handle_mapping.insert(virtual_socket_handle, entry);

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
        tcp_connections: Arc<TokioMutex<HashMap<TcpConnectionId, TcpConnectionWrapper>>>,
        socket_handle_mapping: Arc<TokioMutex<HashMap<SocketHandle, TcpConnectionId>>>,
        tcp_socket: &mut SmoltcpTcpSocket<'static>,
        handle: SocketHandle,
    ) -> Result<()> {
        let tcp_connection_id = {
            let socket_handle_mapping = socket_handle_mapping.lock().await;
            let tcp_connection_id = socket_handle_mapping.get(&handle);
            let Some(tcp_connection_id) = tcp_connection_id else {
                error!(">>>> Can not find tcp connection socket mapping by handle: {handle}");
                return Err(anyhow!("Can not find tcp connection socket mapping by handle: {handle}"));
            };
            *tcp_connection_id
        };
        if !tcp_socket.is_active() {
            let mut socket_handle_mapping = socket_handle_mapping.lock().await;
            let tcp_connection_id = socket_handle_mapping.remove(&handle);
            if let Some(tcp_connection_id) = tcp_connection_id {
                let mut tcp_connections = tcp_connections.lock().await;
                tcp_connections.remove(&tcp_connection_id);
                debug!(">>>> Tcp connection [{tcp_connection_id}] is inactive, remove it.");
            }
            return Ok(());
        }
        while tcp_socket.can_recv() {
            debug!(">>>> Tcp connection [{tcp_connection_id}] become can receive.");
            let mut receive_data = [0u8; 65535];
            let size = match tcp_socket.recv_slice(&mut receive_data) {
                Ok(size) => size,
                Err(e) => {
                    error!(">>>> Tcp connection [{tcp_connection_id}] fail to receive tun input because of error: {e:?}");
                    return Err(anyhow!(
                        "Tcp connection [{tcp_connection_id}] fail to receive tun input because of error: {e:?}"
                    ));
                }
            };
            let receive_data = &receive_data[0..size];
            let tcp_connections = tcp_connections.lock().await;
            let tcp_connection_wrapper = tcp_connections.get(&tcp_connection_id);
            let Some(tcp_connection_wrapper) = tcp_connection_wrapper else {
                error!(">>>> Tcp connection [{tcp_connection_id}] can not found form repository.");
               return Err(anyhow!("Tcp connection [{tcp_connection_id}] can not found form repository."));
            };
            if let Err(e) = tcp_connection_wrapper
                .tun_input_sender
                .send(receive_data.to_vec())
                .await
            {
                error!(">>>> Tcp connection [{tcp_connection_id}] fail to send tun input to tcp connection because of error: {e:?}");
                return Err(anyhow!(
                    "Tcp connection [{tcp_connection_id}] fail to send tun input to tcp connection because of error: {e:?}"
                ));
            }
        }
        Ok(())
    }
}
