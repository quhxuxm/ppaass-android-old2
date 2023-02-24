use std::{
    net::{IpAddr, SocketAddr},
    os::fd::AsRawFd,
    time::Duration,
};

use anyhow::anyhow;
use anyhow::Result;

use futures::Future;
use log::{debug, error, info};

use smoltcp::{
    iface::{SocketHandle, SocketSet},
    socket::tcp::{Socket, SocketBuffer},
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    sync::mpsc::{channel, Receiver},
    time::timeout,
};

use crate::protect_socket;

use super::TcpConnectionKey;

#[derive(Debug)]
pub(crate) enum TcpConnectionToTunCommand {
    ConnectDestinationFail,
    ReadDestinationComplete,
    ReadDestinationFail,
    ForwardTunDataToDestinationFail,
    DestinationData(Vec<u8>),
}

#[derive(Debug)]
pub(crate) struct TcpConnection {
    connection_key: TcpConnectionKey,
}

impl TcpConnection {
    pub fn new<F, R>(
        connection_key: TcpConnectionKey,
        mut tun_read_receiver: Receiver<Vec<u8>>,
        connected_callback: F,
    ) -> Result<(Self, Receiver<TcpConnectionToTunCommand>)>
    where
        F: FnOnce(bool) -> R,
        F: Send + Sync + 'static,
        R: Future<Output = ()> + Send + 'static,
    {
        let (connection_to_tun_socket_command_sender, connection_to_tun_socket_command_receiver) = channel::<TcpConnectionToTunCommand>(1024);

        info!("Create vpn tcp connection [{connection_key}]");
        tokio::spawn(async move {
            debug!(">>>> Tcp connection [{connection_key}] going to connect to destination.");
            let dst_socket = match tokio::net::TcpSocket::new_v4() {
                | Ok(dst_socket) => dst_socket,
                | Err(e) => {
                    error!(">>>> Tcp connection [{connection_key}] fail to generate tokio tcp socket because of error: {e:?}");
                    return;
                },
            };
            let dst_socket_raw_fd = dst_socket.as_raw_fd();
            if let Err(e) = protect_socket(dst_socket_raw_fd) {
                error!(">>>> Tcp connection [{connection_key}] fail to protect tokio tcp socket because of error: {e:?}");
                return;
            };
            let dst_socket_addr = SocketAddr::new(IpAddr::V4(connection_key.dst_addr), connection_key.dst_port);
            let dst_tcp_stream = match timeout(Duration::from_secs(5), dst_socket.connect(dst_socket_addr)).await {
                | Ok(Ok(dst_tcp_stream)) => dst_tcp_stream,
                | Ok(Err(e)) => {
                    error!(">>>> Tcp connection [{connection_key}] fail to connect to destination because of error: {e:?}");
                    connected_callback(false).await;
                    if let Err(e) = connection_to_tun_socket_command_sender.send(TcpConnectionToTunCommand::ConnectDestinationFail).await {
                        error!("<<<< Tcp connection [{connection_key}] fail to send close socket command to tun because of error: {e:?}");
                    };
                    return;
                },
                | Err(_) => {
                    error!(">>>> Tcp connection [{connection_key}] fail to connect to destination because of timeout");
                    connected_callback(false).await;
                    if let Err(e) = connection_to_tun_socket_command_sender.send(TcpConnectionToTunCommand::ConnectDestinationFail).await {
                        error!("<<<< Tcp connection [{connection_key}] fail to send close socket command to tun because of error: {e:?}");
                    };
                    return;
                },
            };
            let (mut dst_tcp_read, mut dst_tcp_write) = dst_tcp_stream.into_split();
            {
                let connection_to_tun_socket_command_sender = connection_to_tun_socket_command_sender.clone();
                tokio::spawn(async move {
                    debug!("<<<< Tcp connection [{connection_key}] start destination relay task.");
                    loop {
                        let mut dst_read_buf = [0u8; 65535];
                        let size = match dst_tcp_read.read(&mut dst_read_buf).await {
                            | Ok(0) => {
                                if let Err(e) = connection_to_tun_socket_command_sender.send(TcpConnectionToTunCommand::ReadDestinationComplete).await {
                                    error!("<<<< Tcp connection [{connection_key}] fail to send close socket command to tun because of error: {e:?}");
                                };
                                break;
                            },
                            | Ok(size) => size,
                            | Err(e) => {
                                error!("<<<< Tcp connection [{connection_key}] fail to read destination data because of error: {e:?}");
                                if let Err(e) = connection_to_tun_socket_command_sender.send(TcpConnectionToTunCommand::ReadDestinationFail).await {
                                    error!("<<<< Tcp connection [{connection_key}] fail to send close socket command to tun because of error: {e:?}");
                                };
                                break;
                            },
                        };
                        let dst_read_buf = &dst_read_buf[..size];
                        if let Err(e) = connection_to_tun_socket_command_sender.send(TcpConnectionToTunCommand::DestinationData(dst_read_buf.to_vec())).await {
                            error!("<<<< Fail to send destination data to socket because of error: {e:?}");
                            break;
                        };
                    }
                });
            }
            connected_callback(true);
            // let tun_read_receiver = &mut tun_read_receiver;
            loop {
                let tun_read_data = match tun_read_receiver.recv().await {
                    | Some(tun_read_data) => tun_read_data,
                    | None => {
                        error!(">>>> Tcp connection [{connection_key}] closed from input side.");
                        if let Err(e) = connection_to_tun_socket_command_sender.send(TcpConnectionToTunCommand::ForwardTunDataToDestinationFail).await {
                            error!("<<<< Tcp connection [{connection_key}] fail to send close socket command to tun because of error: {e:?}");
                        };
                        break;
                    },
                };
                if let Err(e) = dst_tcp_write.write(&tun_read_data).await {
                    error!(">>>> Tcp connection [{connection_key}] fail to write tun date to destination because of error: {e:?}");
                    if let Err(e) = connection_to_tun_socket_command_sender.send(TcpConnectionToTunCommand::ForwardTunDataToDestinationFail).await {
                        error!("<<<< Tcp connection [{connection_key}] fail to send close socket command to tun because of error: {e:?}");
                    };
                    continue;
                };
                if let Err(e) = dst_tcp_write.flush().await {
                    error!(">>>> Tcp connection [{connection_key}] fail to flush tun date to destination because of error: {e:?}");
                    if let Err(e) = connection_to_tun_socket_command_sender.send(TcpConnectionToTunCommand::ForwardTunDataToDestinationFail).await {
                        error!("<<<< Tcp connection [{connection_key}] fail to send close socket command to tun because of error: {e:?}");
                    };
                    continue;
                };
            }
        });

        Ok((Self { connection_key }, connection_to_tun_socket_command_receiver))
    }
}
