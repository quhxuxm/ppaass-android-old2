use std::{
    net::{IpAddr, SocketAddr},
    os::fd::AsRawFd,
    time::Duration,
};

use anyhow::anyhow;
use anyhow::Result;

use log::{debug, error};
use pretty_hex::pretty_hex;
use smoltcp::{
    iface::{SocketHandle, SocketSet},
    socket::tcp::{Socket, SocketBuffer, State},
};
use tokio::{
    io::AsyncReadExt,
    net::tcp::OwnedWriteHalf,
    sync::mpsc::{channel, Receiver, Sender},
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

    pub async fn notify(&self, notification: VpnTcpConnectionNotification) -> Result<()> {
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
}
