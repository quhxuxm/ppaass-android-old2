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

use pretty_hex::pretty_hex;
use smoltcp::{
    iface::{Config, Interface, SocketHandle, SocketSet},
    socket::{
        tcp::{self, State},
        Socket,
    },
    time::Instant as SmoltcpInstant,
    wire::{
        Icmpv4Packet, IpAddress, IpCidr, IpProtocol, IpVersion, Ipv4Address, Ipv4Packet, TcpPacket,
        UdpPacket,
    },
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
use tokio::{
    runtime::{Runtime as TokioRuntime, UnhandledPanic},
    sync::mpsc::Sender,
};
use uuid::Uuid;

use crate::{
    device::PpaassVpnDevice,
    tcp::{TcpConnection, TcpConnectionKey},
    util::{print_packet, print_packet_bytes},
};

struct VpnTcpConnectionRepositoryEntry {
    vpn_tcp_connection: TcpConnection,
    tun_read_sender: Sender<Vec<u8>>,
    dst_read_receiver: Receiver<Vec<u8>>,
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

    fn init_interface(device: &mut PpaassVpnDevice) -> Interface {
        let mut ifrace_config = Config::default();
        ifrace_config.random_seed = rand::random::<u64>();
        let mut iface = Interface::new(ifrace_config, device);
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
        iface
    }

    fn init_device(tun_write_sender: Sender<Vec<u8>>) -> PpaassVpnDevice {
        PpaassVpnDevice::new(tun_write_sender)
    }

    fn init_async_runtime() -> TokioRuntime {
        let mut runtime_builder = TokioRuntimeBuilder::new_multi_thread();
        runtime_builder
            .worker_threads(32)
            .enable_all()
            .thread_name("PPAASS-RUST-THREAD");
        // .unhandled_panic(UnhandledPanic::ShutdownRuntime);
        runtime_builder.build().expect("Fail to start vpn runtime.")
    }

    fn init_tun_read_write(tun_fd: i32) -> (Arc<TokioMutex<File>>, Arc<TokioMutex<File>>) {
        let tun_read = Arc::new(TokioMutex::new(unsafe { File::from_raw_fd(tun_fd) }));
        let tun_write = tun_read.clone();
        (tun_read, tun_write)
    }

    pub(crate) fn start(self) -> Result<()> {
        let server_id = self.id.clone();
        info!("Start ppaass vpn server instance [{server_id}]");
        let tun_fd = self.tun_fd;
        let (tun_read, tun_write) = Self::init_tun_read_write(tun_fd);
        info!("Ready to prepare tun read & write");
        let async_runtime = Self::init_async_runtime();
        async_runtime.block_on(async move {
            let mut vpn_tcp_connection_repository: HashMap<TcpConnectionKey, VpnTcpConnectionRepositoryEntry> = Default::default();
            let mut socket_handle_and_vpn_tcp_connection_mapping: HashMap<SocketHandle, TcpConnectionKey> = Default::default();
            let (tun_write_sender, mut tun_write_receiver) = channel::<Vec<u8>>(1024);
            let mut device = Self::init_device(tun_write_sender);
            let mut iface = Self::init_interface(&mut device);
            let mut sockets = SocketSet::new(vec![]);

            let server_id_for_tx=server_id.clone();
            tokio::spawn(async move{
                info!("Start task for write data to tun device on server [{server_id_for_tx}]");
                loop {
                  let data_write_to_tun = match tun_write_receiver.recv().await{
                    Some(data_write_to_tun) => data_write_to_tun,
                    None => {
                        error!("<<<< Tun writer receiver closed already.");
                        break;
                    },
                  };
                  let mut tun_write= tun_write.lock().await;
                  if let Err(e)= tun_write.write(&data_write_to_tun){
                    error!("<<<< Fail to write data to tun because of error: {e:?}");
                    continue;
                  };
                  if let Err(e)= tun_write.flush(){
                     error!("<<<< Fail to flush data to tun because of error: {e:?}");
                     continue;
                  };
                }
            });
            info!("Start task for read data from tun device on server [{server_id}]");
            loop {
                let mut tun_read = tun_read.lock().await;
                let mut tun_read_buf = [0u8; 65535];
                let tun_read_buf_size = match tun_read.read(&mut tun_read_buf) {
                    Ok(tun_read_buf_size) => tun_read_buf_size,
                    Err(e) => {
                        match e.kind() {
                            ErrorKind::WouldBlock=>{
                                continue;
                            }
                            _=>{
                                error!(">>>> Fail to read tun data because of error: {e:?}");
                                break;
                            }
                        }
                    },
                };
                drop(tun_read);
                let tun_read_buf = &tun_read_buf[..tun_read_buf_size];
                if let Err(e) = Self::handle_tun_input(
                    tun_read_buf,
                    &mut vpn_tcp_connection_repository,
                    &mut socket_handle_and_vpn_tcp_connection_mapping,
                    &mut sockets,
                ) {
                    error!(">>>> Fail to handle tun data because of error: {e:?}");
                    continue;
                };
                device.push_rx(tun_read_buf.to_vec());
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
                let mut socket_handles_to_remove = vec![];
                for (socket_handle, socket) in sockets.iter() {
                    if let Socket::Tcp(tcp_socket) = socket {
                        if tcp_socket.state() == State::Closed {
                            socket_handles_to_remove.push(socket_handle);
                        }
                    }
                }
                for socket_handle in socket_handles_to_remove {
                    sockets.remove(socket_handle);
                }

                for (socket_handle, socket) in sockets.iter_mut() {
                    match socket {
                        Socket::Udp(_) => {
                            error!(">>>> Udp socket still not support.");
                            continue;
                        },
                        Socket::Tcp(tcp_socket) => {
                            let tcp_connection_key = socket_handle_and_vpn_tcp_connection_mapping.get(&socket_handle);
                            let Some(tcp_connection_key) = tcp_connection_key else {
                                continue;
                            };
                            let vpn_tcp_connection_repository_entry = vpn_tcp_connection_repository.get_mut(tcp_connection_key);
                            let Some( vpn_tcp_connection_repository_entry) = vpn_tcp_connection_repository_entry else {
                                continue;
                            };

                            while tcp_socket.can_recv() {
                                let mut tun_read_data = [0u8;65535];
                               let size= match tcp_socket.recv_slice(&mut tun_read_data){
                                    Ok(size) => size,
                                    Err(e) => {
                                         error!(">>>> Fail to send received tun data to vpn tcp connection [{tcp_connection_key}] and close tcp socket because of error: {e:?}");
                                     tcp_socket.close();
                                     continue;
                                    },
                                };
                                if size ==0{
                                    break;
                                }
                                let tun_read_data=&tun_read_data[..size];
                                   debug!(">>>> Tcp connection [{tcp_connection_key}] going to send tun data to destination:\n{}\n", pretty_hex(&tun_read_data));
                                 if let Err(e) = vpn_tcp_connection_repository_entry.tun_read_sender.send(tun_read_data.to_vec()).await {
                                        error!(">>>> Fail to send received tun data to vpn tcp connection [{tcp_connection_key}] because of error: {e:?}");
                                    };
                            }
                            while tcp_socket.can_send() {
                                 let data= match vpn_tcp_connection_repository_entry.dst_read_receiver.try_recv(){
                                    Ok(data) => data,
                                    Err(TryRecvError::Empty) => {
                                        break;
                                    },
                                    Err(TryRecvError::Disconnected) => {
                                        error!(">>>> Fail to send destination data from vpn tcp connection [{tcp_connection_key}] to tcp socket because of receiver disconnected.");
                                        break;
                                    },
                                };
                                debug!("<<<< Tcp connection [{tcp_connection_key}] going to send destination data to tun:\n{}\n", pretty_hex(&data));
                                if let Err(e)= tcp_socket.send_slice(&data){
                                      error!(">>>> Fail to send destination data from vpn tcp connection [{tcp_connection_key}] to tcp socket because of error: {e:?}");
                                };
                            }
                        },

                        _ => {
                            error!(">>>> Other socket still not support.");
                            continue;
                        }
                    }
                }
                tokio::time::sleep_until(wait_until).await;
            }
        });
        Ok(())
    }

    fn handle_tun_input(
        tun_read_buf: &[u8],
        vpn_tcp_connection_repository: &mut HashMap<
            TcpConnectionKey,
            VpnTcpConnectionRepositoryEntry,
        >,
        socket_handle_and_vpn_tcp_connection_mapping: &mut HashMap<SocketHandle, TcpConnectionKey>,
        sockets: &mut SocketSet<'static>,
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

                if let Vacant(entry) = vpn_tcp_connection_repository.entry(tcp_connection_key) {
                    let (tun_read_sender, tun_read_receiver) = channel::<Vec<u8>>(1024);

                    let (vpn_tcp_connection, dst_read_receiver) =
                        TcpConnection::new(tcp_connection_key, sockets, tun_read_receiver)?;
                    let socket_handle = vpn_tcp_connection.get_socket_handle();
                    entry.insert(VpnTcpConnectionRepositoryEntry {
                        vpn_tcp_connection,
                        tun_read_sender,
                        dst_read_receiver,
                    });
                    socket_handle_and_vpn_tcp_connection_mapping
                        .insert(socket_handle, tcp_connection_key);
                };

                debug!(
                    ">>>> Tcp connection [{tcp_connection_key}] push tun data into vpn device:\n{}\n",
                    print_packet(&ipv4_packet)
                );
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
