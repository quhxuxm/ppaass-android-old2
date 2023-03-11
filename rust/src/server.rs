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
use smoltcp::{socket::tcp::Socket as VirtualTcpSocket, time::Instant};

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

struct TcpConnectionCommunicator {
    device_data_sender: Sender<Vec<u8>>,
    dst_data_buffer: Arc<TokioMutex<Vec<u8>>>,
}

pub(crate) struct PpaassVpnServer
where
    Self: 'static,
{
    device_fd: i32,
}

impl Debug for PpaassVpnServer {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("PpaassVpnServer")
            .field("device_fd", &self.device_fd)
            .finish()
    }
}

impl PpaassVpnServer {
    pub(crate) fn new(device_fd: i32) -> Result<Self> {
        info!("Create ppaass vpn server instance");
        Ok(Self { device_fd })
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
            .worker_threads(64)
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

    fn find_tcp_connection_communicator<'a>(
        virtual_tcp_socket_handle: SocketHandle,
        virtial_tcp_socket_handle_mapping: &'a mut HashMap<SocketHandle, TcpConnectionId>,
        tcp_connections: &'a mut HashMap<TcpConnectionId, TcpConnectionCommunicator>,
    ) -> Option<&'a mut TcpConnectionCommunicator> {
        let Some(tcp_connection_id) = virtial_tcp_socket_handle_mapping.get(&virtual_tcp_socket_handle)else {
            return  None;
        };
        tcp_connections.get_mut(tcp_connection_id)
    }

    pub(crate) fn start(self) -> Result<()> {
        info!("Start ppaass vpn server instance");
        let device_fd = self.device_fd;
        let (device_file_read, device_file_write) = Self::split_device_file(device_fd);
        info!("Ready to prepare device file read and write");

        let (async_runtime, virtual_iface, virtual_device, virtual_sockets) = Self::init();
        let write_device_notifier = Arc::new(Notify::new());
        info!("Ready to prepare device write notifier");
        let poll_iface_notifier = Arc::new(Notify::new());
        info!("Ready to prepare iface poll notifier");
        let virtial_tcp_socket_handle_mapping: Arc<TokioMutex<HashMap<SocketHandle, TcpConnectionId>>> = Default::default();
        let tcp_connections: Arc<TokioMutex<HashMap<TcpConnectionId, TcpConnectionCommunicator>>> = Default::default();

        info!("Read device task prepared");
        async_runtime.block_on(async {
            info!("Start vpn service async runtime");

            let poll_iface_task = {
                let poll_iface_notifier = poll_iface_notifier.clone();
                let virtual_device = virtual_device.clone();
                let virtual_sockets = virtual_sockets.clone();
                let tcp_connections = tcp_connections.clone();
                let virtial_tcp_socket_handle_mapping = virtial_tcp_socket_handle_mapping.clone();
                let write_device_notifier = write_device_notifier.clone();
                async move {
                    info!("Start iface poll task");
                    loop {
                        trace!("Waiting for iface poll notify");
                        poll_iface_notifier.notified().await;
                        trace!("Iface poll notify received");
                        let mut virtual_iface = virtual_iface.lock().await;
                        let mut virtual_device = virtual_device.lock().await;
                        let mut virtual_sockets = virtual_sockets.lock().await;
                        let updated = virtual_iface.poll(
                            SmoltcpInstant::now(),
                            &mut *virtual_device,
                            &mut virtual_sockets,
                        );
                        if !updated {
                            debug!("No sockets update on iface continue next loop");
                            continue;
                        }
                        trace!("Some sockets update on iface, begine handle them");
                        for (virtual_socket_handle, virtual_socket) in virtual_sockets.iter_mut() {
                            match virtual_socket {
                                Socket::Raw(virtual_socket) => todo!(),
                                Socket::Icmp(virtual_socket) => todo!(),
                                Socket::Udp(virtual_socket) => todo!(),
                                Socket::Tcp(virtual_socket) => {
                                    if virtual_socket.may_recv() {
                                        let mut device_data = vec![0u8; virtual_socket.recv_queue()];
                                        let size = match virtual_socket.recv_slice(&mut device_data) {
                                            Ok(size) => size,
                                            Err(e) => {
                                                error!(">>>> Fail to receive data from device because of error: {e:?}");
                                                continue;
                                            }
                                        };
                                        let device_data = &device_data[..size];
                                        let mut virtial_tcp_socket_handle_mapping = virtial_tcp_socket_handle_mapping.lock().await;
                                        let mut tcp_connections = tcp_connections.lock().await;
                                        let Some(tcp_connection_communicator) = Self::find_tcp_connection_communicator(
                                        virtual_socket_handle,
                                        &mut virtial_tcp_socket_handle_mapping,
                                        &mut tcp_connections,
                                    )else{
                                        continue;
                                    };
                                        if let Err(e) = tcp_connection_communicator
                                            .device_data_sender
                                            .send(device_data.to_vec())
                                            .await
                                        {
                                            error!(">>>> Fail to send device data to tcp connection because of error: {e:?}");
                                            continue;
                                        };
                                    }
                                    if virtual_socket.may_send() {
                                        let mut virtial_tcp_socket_handle_mapping = virtial_tcp_socket_handle_mapping.lock().await;
                                        let mut tcp_connections = tcp_connections.lock().await;
                                        let Some(tcp_connection_communicator) = Self::find_tcp_connection_communicator(
                                        virtual_socket_handle,
                                        &mut virtial_tcp_socket_handle_mapping,
                                        &mut tcp_connections,
                                    )else{
                                        error!("Can not find tcp connection communicator because of not exist.");
                                        continue;
                                    };
                                        let mut dst_data_buffer = tcp_connection_communicator.dst_data_buffer.lock().await;
                                        let send_data = dst_data_buffer
                                            .drain(..virtual_socket.send_queue())
                                            .collect::<Vec<u8>>();
                                        if let Err(e) = virtual_socket.send_slice(&send_data) {
                                            error!("<<<< Fail to write destination data to device because of error: {e:?}")
                                        };
                                        poll_iface_notifier.notify_one();
                                        write_device_notifier.notify_one();
                                    }
                                }
                                Socket::Dhcpv4(virtual_socket) => todo!(),
                                Socket::Dns(virtual_socket) => todo!(),
                            }
                        }
                    }
                }
            };
            info!("Poll iface task prepared");
            let write_device_task = {
                let virtual_device = virtual_device.clone();
                async move {
                    info!("Start write device task");
                    loop {
                        trace!("Waiting for device write notify");
                        write_device_notifier.notified().await;
                        trace!("Device write notify received");
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
            info!("Write device task prepared");
            let read_device_task = async move {
                info!("Start read device task");
                loop {
                    trace!("Begin the loop to read device data");

                    let device_data = {
                        let mut device_data = [0u8; 65535];
                        let mut device_file_read = device_file_read.lock().await;
                        let size = match device_file_read.read(&mut device_data) {
                            Ok(size) => {
                                trace!(">>>> Receive device data, size={size}");
                                size
                            }
                            Err(e) => match e.kind() {
                                ErrorKind::WouldBlock => {
                                    trace!(">>>> No data in device, wait for a momoent ");
                                    tokio::time::sleep(TokioDuration::from_millis(100)).await;
                                    continue;
                                }
                                _ => {
                                    error!(">>>> Fail to read device data because of error: {e:?}");
                                    continue;
                                }
                            },
                        };

                        device_data[..size].to_vec()
                    };

                    if let Err(e) = Self::handle_device_data(
                        &device_data,
                        virtual_sockets.clone(),
                        tcp_connections.clone(),
                        virtial_tcp_socket_handle_mapping.clone(),
                    )
                    .await
                    {
                        error!(">>>> Fail to handle device data because of error: {e:?}")
                    };
                    let mut virtual_device = virtual_device.lock().await;
                    virtual_device.push_rx(device_data);
                    poll_iface_notifier.notify_one();
                }
            };
            tokio::join!(poll_iface_task, write_device_task, read_device_task);
            info!("Vpn service async runtime shutdown")
        });

        Ok(())
    }

    async fn handle_device_data(
        device_data: &[u8],
        virtual_sockets: Arc<TokioMutex<SocketSet<'static>>>,
        tcp_connections: Arc<TokioMutex<HashMap<TcpConnectionId, TcpConnectionCommunicator>>>,
        virtual_tcp_socket_handle_mapping: Arc<TokioMutex<HashMap<SocketHandle, TcpConnectionId>>>,
    ) -> Result<()> {
        trace!(">>>> Begin to handle device data");
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

        trace!("Success to parse ipv4 packet: {ipv4_packet:?}");
        let transport_protocol = ipv4_packet.next_header();
        match transport_protocol {
            IpProtocol::Tcp => {
                let ipv4_packet_payload = ipv4_packet.payload();
                let tcp_packet = TcpPacket::new_checked(ipv4_packet_payload).map_err(|e| {
                    error!(">>>> Fail to parse ip v4 packet payload to tcp packet because of error: {e:?}");
                    anyhow!("Fail to parse ip v4 packet payload to tcp packet because of error: {e:?}")
                })?;

                debug!(
                    ">>>> Receive tcp packet from device:\n{}\n",
                    print_packet(&ipv4_packet)
                );

                let tcp_connection_id = TcpConnectionId::new(
                    ipv4_packet.src_addr().into(),
                    tcp_packet.src_port(),
                    ipv4_packet.dst_addr().into(),
                    tcp_packet.dst_port(),
                );

                let mut tcp_connections = tcp_connections.lock().await;
                if let Entry::Vacant(entry) = tcp_connections.entry(tcp_connection_id) {
                    debug!("Create a new tcp connection: {tcp_connection_id}");
                    let (device_data_sender, device_data_receiver) = channel(1024);

                    let virtual_socket_listen_addr = SocketAddr::new(
                        IpAddr::V4(tcp_connection_id.dst_addr),
                        tcp_connection_id.dst_port,
                    );
                    let mut virtual_tcp_socket = VirtualTcpSocket::new(
                        SocketBuffer::new(vec![0; 65535]),
                        SocketBuffer::new(vec![0; 65535]),
                    );
                    if let Err(e) = virtual_tcp_socket.listen::<SocketAddr>(virtual_socket_listen_addr) {
                        error!(">>>> Tcp connection [{tcp_connection_id}] fail to listen vpn tcp socket because of error: {e:?}");
                        return Err(anyhow!(
                            ">>>> Tcp connection [{tcp_connection_id}] fail to listen vpn tcp socket because of error: {e:?}"
                        ));
                    }
                    let virtual_socket_handle = {
                        let mut virtual_sockets = virtual_sockets.lock().await;
                        virtual_sockets.add(virtual_tcp_socket)
                    };
                    debug!(">>>> Success create tcp connection [{tcp_connection_id}]");
                    let (tcp_connection, dst_data_buffer) = TcpConnection::new(tcp_connection_id, device_data_receiver)?;
                    entry.insert(TcpConnectionCommunicator {
                        device_data_sender,
                        dst_data_buffer,
                    });
                    let mut virtial_socket_handle_mapping = virtual_tcp_socket_handle_mapping.lock().await;
                    virtial_socket_handle_mapping.insert(virtual_socket_handle, tcp_connection_id);
                    tokio::spawn(tcp_connection.run());
                };
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
}
