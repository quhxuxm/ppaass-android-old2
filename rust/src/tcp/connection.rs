use std::{
    collections::{HashMap, VecDeque},
    net::{IpAddr, SocketAddr},
    os::fd::AsRawFd,
    sync::Arc,
    task::Waker,
    time::Duration,
};

use anyhow::anyhow;
use anyhow::Result;

use futures::TryFutureExt;
use log::{debug, error};
use pretty_hex::pretty_hex;
use smoltcp::{
    iface::{SocketHandle, SocketSet},
    socket::tcp::{Socket, SocketBuffer, State},
    time::Instant,
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{
        tcp::{OwnedReadHalf, OwnedWriteHalf},
        TcpStream,
    },
    sync::{
        mpsc::{channel, Receiver, Sender},
        Mutex,
    },
    time::timeout,
};

use crate::{protect_socket, IP_MTU};

use super::VpnTcpConnectionKey;

pub(crate) struct VpnTcpConnectionNotification {
    pub tcp_socket_state: State,
    pub can_receive: bool,
    pub can_send: bool,
}

#[derive(Debug)]
pub(crate) struct VpnTcpConnectionHandle {
    vpn_tcp_connection_key: VpnTcpConnectionKey,
    notifier_sender: Sender<VpnTcpConnectionNotification>,
    vpn_tcp_socket_handle: SocketHandle,
}

impl VpnTcpConnectionHandle {
    pub fn get_key(&self) -> VpnTcpConnectionKey {
        self.vpn_tcp_connection_key
    }

    pub fn get_socket_handle(&self) -> SocketHandle {
        self.vpn_tcp_socket_handle
    }

    pub async fn poll(&self, notification: VpnTcpConnectionNotification) -> Result<()> {
        let vpn_tcp_connection_key = self.vpn_tcp_connection_key;
        self.notifier_sender.send(notification).await.map_err(|_| {
            error!(">>>> Fail to notify tcp connection [{vpn_tcp_connection_key}] because of error.");
            anyhow!("Fail to notify tcp connection [{vpn_tcp_connection_key}] because of error.")
        })
    }
}

#[derive(Debug)]
enum VpnTcpConnectionInnerState {
    New,
    DstConnected(OwnedWriteHalf),
}

#[derive(Debug)]
pub(crate) struct VpnTcpConnection {
    vpn_tcp_connection_key: VpnTcpConnectionKey,
    notifier_sender: Sender<VpnTcpConnectionNotification>,
    notifier_receiver: Receiver<VpnTcpConnectionNotification>,
    inner_state: VpnTcpConnectionInnerState,
}

impl VpnTcpConnection {
    pub fn new(vpn_tcp_connection_key: VpnTcpConnectionKey, vpn_tcp_socket_set: &mut SocketSet<'_>) -> Result<(Self, VpnTcpConnectionHandle)> {
        let listen_addr = SocketAddr::new(IpAddr::V4(vpn_tcp_connection_key.dst_addr), vpn_tcp_connection_key.dst_port);

        let vpn_tcp_socket_handle = {
            let mut vpn_tcp_socket = Socket::new(SocketBuffer::new(vec![0; 655350]), SocketBuffer::new(vec![0; 655350]));

            vpn_tcp_socket
                .listen::<SocketAddr>(listen_addr)
                .map_err(|e| anyhow!(">>>> Fail to listen vpn tcp socket because of error: {e:?}"))?;
            vpn_tcp_socket_set.add(vpn_tcp_socket)
        };
        let (notifier_sender, notifier_receiver) = channel::<VpnTcpConnectionNotification>(1024);

        Ok((
            Self {
                vpn_tcp_connection_key,
                notifier_sender: notifier_sender.clone(),
                notifier_receiver,
                inner_state: VpnTcpConnectionInnerState::New,
            },
            VpnTcpConnectionHandle {
                vpn_tcp_connection_key,
                notifier_sender,
                vpn_tcp_socket_handle,
            },
        ))
    }

    pub fn exec(mut self) -> Result<()> {
        tokio::spawn(async move {
            let vpn_tcp_connection_key = self.vpn_tcp_connection_key;
            loop {
                let Some(VpnTcpConnectionNotification {
                    can_receive,
                    can_send,
                    tcp_socket_state,
                }) = self.notifier_receiver.recv().await else{
                    debug!(">>>> Tcp connection [{vpn_tcp_connection_key}] closed because of the reveicer closed.");
                    return ;
                };
                match self.inner_state {
                    VpnTcpConnectionInnerState::New => match tcp_socket_state {
                        State::Listen => {
                            debug!(">>>> Tcp connection [{vpn_tcp_connection_key}] continue loop because of tcp socket state: Listen.");
                            continue;
                        },
                        State::Closed => {
                            debug!(">>>> Tcp connection [{vpn_tcp_connection_key}] closed because of tcp socket status: Closed.");
                            return;
                        },
                        State::Established => {
                            debug!(">>>> Tcp connection [{vpn_tcp_connection_key}] closed because of no dst tcp stream and tcp socket state: Established.");
                            return;
                        },
                        State::CloseWait => {
                            debug!(">>>> Tcp connection [{vpn_tcp_connection_key}] closed because of no dst tcp stream and tcp socket state: CloseWait.");
                            continue;
                        },
                        State::LastAck => {
                            debug!(">>>> Tcp connection [{vpn_tcp_connection_key}] closed because of no dst tcp stream and tcp socket state: LastAck.");
                            continue;
                        },
                        State::SynReceived => {
                            debug!(">>>> Tcp connection [{vpn_tcp_connection_key}] going to connect to destination.");
                            let dst_socket = match tokio::net::TcpSocket::new_v4() {
                                Ok(dst_socket) => dst_socket,
                                Err(e) => {
                                    error!(">>>> Tcp connection [{vpn_tcp_connection_key}] fail to generate tokio tcp socket because of error: {e:?}");
                                    return;
                                },
                            };
                            let dst_socket_raw_fd = dst_socket.as_raw_fd();
                            if let Err(e) = protect_socket(dst_socket_raw_fd) {
                                error!(">>>> Tcp connection [{vpn_tcp_connection_key}] fail to protect tokio tcp socket because of error: {e:?}");
                                return;
                            };
                            let dst_socket_addr = SocketAddr::new(IpAddr::V4(self.vpn_tcp_connection_key.dst_addr), self.vpn_tcp_connection_key.dst_port);
                            let dst_tcp_stream = match timeout(Duration::from_secs(5), dst_socket.connect(dst_socket_addr)).await {
                                Ok(Ok(concrete_dst_tcp_stream)) => concrete_dst_tcp_stream,
                                Ok(Err(e)) => {
                                    error!(">>>> Tcp connection [{vpn_tcp_connection_key}] fail to connect to destination because of error: {e:?}");
                                    return;
                                },
                                Err(e) => {
                                    error!(">>>> Tcp connection [{vpn_tcp_connection_key}] fail to connect to destination because of timeout");
                                    return;
                                },
                            };
                            let (mut dst_tcp_read, dst_tcp_write) = dst_tcp_stream.into_split();
                            self.inner_state = VpnTcpConnectionInnerState::DstConnected(dst_tcp_write);
                            tokio::spawn(async move {
                                loop {
                                    let mut dst_read_buf = [0u8; 65535];
                                    let size = match dst_tcp_read.read(&mut dst_read_buf).await {
                                        Ok(0) => {
                                            debug!("<<<< Tcp connection [{vpn_tcp_connection_key}] read destination data complete.");
                                            return;
                                        },
                                        Ok(size) => size,
                                        Err(e) => {
                                            error!("<<<< Tcp connection [{vpn_tcp_connection_key}] fail to read destination data because of error: {e:?}");
                                            return;
                                        },
                                    };
                                    let dst_read_buf = &dst_read_buf[..size];
                                    debug!(
                                        "<<<< Tcp connection [{vpn_tcp_connection_key}] receive destination data:\n{}\n",
                                        pretty_hex(&dst_read_buf)
                                    );
                                }
                            });
                            continue;
                        },
                        _ => {
                            debug!(">>>> Tcp connection [{vpn_tcp_connection_key}] closed because invalid tcp socket state.");
                            return;
                        },
                    },
                    VpnTcpConnectionInnerState::DstConnected(ref mut dst_tcp_write) => match tcp_socket_state {
                        State::Listen => {
                            debug!(">>>> Tcp connection [{vpn_tcp_connection_key}] closed because of dst tcp connected but tcp socket state: Listen.");
                            continue;
                        },
                        State::Closed => {
                            debug!(">>>> Tcp connection [{vpn_tcp_connection_key}] closed because of dst tcp connected but tcp socket status: Closed.");
                            return;
                        },

                        State::CloseWait => {
                            debug!(">>>> Tcp connection [{vpn_tcp_connection_key}] continue because of dst tcp connected and tcp socket state: CloseWait.");
                            continue;
                        },
                        State::LastAck => {
                            debug!(">>>> Tcp connection [{vpn_tcp_connection_key}] continue because of dst tcp connected and tcp socket state: LastAck.");
                            continue;
                        },
                        State::SynReceived => {
                            debug!(">>>> Tcp connection [{vpn_tcp_connection_key}] continue loop because of dst connected but tcp socket status: SynReceived.");
                            continue;
                        },
                        State::Established => {
                            debug!(">>>> Tcp connection [{vpn_tcp_connection_key}] continue because of dst tcp connected and tcp socket state: Established.");

                            return;
                        },
                        _ => {
                            debug!(">>>> Tcp connection [{vpn_tcp_connection_key}] closed because of dst connected invalid tcp socket state.");
                            return;
                        },
                    },
                }
            }
        });
        Ok(())
    }

    // pub async fn process(&mut self) {
    //     let vpn_socketset = self.vpn_socketset.clone();
    //     let vpn_socket_handle = self.vpn_socket_handle;
    //     let tcp_connection_key = self.tcp_connection_key;
    //     let dst_write = self.dst_write.clone();
    //     let tcp_connection_repository = self.tcp_connection_repository.clone();
    //     let tcp_connection_notifier_repository = self.tcp_connection_notifier_repository.clone();
    //     let dst_relay_data_buf = self.dst_relay_data_buf.clone();
    //     let Some(mut notifier_receiver)=self.notifier_receiver.take() else{
    //         return;
    //     };

    //     tokio::spawn(async move {
    //         loop {
    //             notifier_receiver.recv().await;
    //             if let Err(e) = Self::handle_connection(
    //                 vpn_socketset.clone(),
    //                 vpn_socket_handle,
    //                 tcp_connection_key,
    //                 dst_write.clone(),
    //                 dst_relay_data_buf.clone(),
    //             )
    //             .await
    //             {
    //                 error!("Error happen when handle connection, shutdown it: {e:?}");
    //                 let mut vpn_socketset = vpn_socketset.lock().await;
    //                 let vpn_socket = vpn_socketset.get_mut::<Socket>(vpn_socket_handle);
    //                 vpn_socket.close();
    //                 vpn_socketset.remove(vpn_socket_handle);
    //                 let mut tcp_connection_repository = tcp_connection_repository.lock().await;
    //                 tcp_connection_repository.remove(&tcp_connection_key);
    //                 let mut tcp_connection_notifier_repository = tcp_connection_notifier_repository.lock().await;
    //                 tcp_connection_notifier_repository.remove(&vpn_socket_handle);
    //                 return;
    //             };
    //         }
    //     });
    // }

    // async fn handle_connection(
    //     vpn_socketset: Arc<Mutex<SocketSet<'static>>>, vpn_socket_handle: SocketHandle, tcp_connection_key: TcpConnectionKey,
    //     dst_write: Arc<Mutex<Option<OwnedWriteHalf>>>, dst_relay_data_buf: Arc<Mutex<VecDeque<Vec<u8>>>>,
    // ) -> Result<()> {
    //     let mut vpn_socketset = vpn_socketset.lock().await;
    //     let vpn_socket = vpn_socketset.get_mut::<Socket>(vpn_socket_handle);
    //     debug!(">>>> Tcp connection [{}] current status: [{}].", tcp_connection_key, vpn_socket.state());
    //     if vpn_socket.state() == State::SynReceived {
    //         debug!(">>>> Tcp connection [{}] going to connect to destination.", tcp_connection_key);
    //         let dst_socket = tokio::net::TcpSocket::new_v4()?;
    //         let dst_socket_raw_fd = dst_socket.as_raw_fd();
    //         protect_socket(dst_socket_raw_fd)?;
    //         let dst_socket_addr = SocketAddr::new(IpAddr::V4(tcp_connection_key.dst_addr), tcp_connection_key.dst_port);
    //         let concrete_dst_tcp_stream = timeout(Duration::from_secs(3), dst_socket.connect(dst_socket_addr)).await??;
    //         let (mut concrete_dst_read, concrete_dst_write) = concrete_dst_tcp_stream.into_split();
    //         let mut dst_write = dst_write.lock().await;
    //         *dst_write = Some(concrete_dst_write);
    //         debug!(">>>> Tcp connection [{tcp_connection_key}] connect to destination success.");
    //         let dst_relay_data_buf = dst_relay_data_buf.clone();
    //         tokio::spawn(async move {
    //             debug!("<<<< Tcp connection [{tcp_connection_key}] begin to relay destination data.");
    //             loop {
    //                 let mut dst_read_buf = [0u8; 65535];
    //                 let size = match concrete_dst_read.read(&mut dst_read_buf).await {
    //                     Ok(0) => {
    //                         debug!("<<<< Tcp connection [{tcp_connection_key}] read destination data complete.");
    //                         return;
    //                     },
    //                     Ok(size) => size,
    //                     Err(e) => {
    //                         error!("<<<< Tcp connection [{tcp_connection_key}] fail to read destination data because of error: {e:?}");
    //                         return;
    //                     },
    //                 };
    //                 let dst_read_buf = &dst_read_buf[..size];
    //                 debug!(
    //                     "<<<< Tcp coneection [{tcp_connection_key}] receive destination data:\n{}\n",
    //                     pretty_hex(&dst_read_buf)
    //                 );
    //                 let mut dst_relay_data_buf = dst_relay_data_buf.lock().await;
    //                 dst_relay_data_buf.push_back(dst_read_buf.to_vec());
    //             }
    //         });
    //         return Ok(());
    //     }

    //     if vpn_socket.can_recv() {
    //         debug!(">>>> Tcp connection [{}] can receive on state: {}.", tcp_connection_key, vpn_socket.state());
    //         let mut data = [0u8; 65536];
    //         let size = match vpn_socket.recv_slice(&mut data) {
    //             Ok(size) => size,
    //             Err(e) => {
    //                 error!(">>>> Tcp connection [{tcp_connection_key}] fail to receive data because of error: {e:?}");
    //                 return Err(anyhow!("Tcp connection [{tcp_connection_key}] fail to receive data because of error: {e:?}"));
    //             },
    //         };
    //         let data = &data[..size];

    //         let mut dst_write = dst_write.lock().await;
    //         if let Some(dst_write) = dst_write.as_mut() {
    //             debug!(
    //                 ">>>> Tcp connection [{tcp_connection_key}] receive tun data going to write to destination:\n{}\n",
    //                 pretty_hex::pretty_hex(&data)
    //             );
    //             dst_write.write_all(data).await?;
    //             dst_write.flush().await?;
    //         }
    //     }

    //     let mut dst_relay_data_buf = dst_relay_data_buf.lock().await;
    //     if vpn_socket.can_send() && dst_relay_data_buf.len() > 0 {
    //         debug!(">>>> Tcp connection [{}] can send on state: {}.", tcp_connection_key, vpn_socket.state());
    //         if let Some(dst_data) = dst_relay_data_buf.pop_front() {
    //             dst_data.chunks(IP_MTU).for_each(|data| {
    //                 if let Err(e) = vpn_socket.send_slice(data) {
    //                     error!("<<<< Tcp connection [{tcp_connection_key}] fail to send destination data to tun because of error: {e:?}");
    //                 };
    //             });
    //         }
    //     }

    //     if vpn_socket.state() == State::CloseWait && dst_relay_data_buf.len() == 0 {
    //         vpn_socket.close();
    //         vpn_socketset.remove(vpn_socket_handle);
    //         error!("<<<< Tcp connection [{tcp_connection_key}] invoke close.");
    //     }

    //     Ok(())
    // }
}
