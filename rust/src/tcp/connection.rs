use std::{
    net::{IpAddr, SocketAddr},
    os::fd::AsRawFd,
    sync::Arc,
    time::Duration,
};

use anyhow::Result;

use log::{debug, error};

use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    sync::{mpsc::Receiver, Mutex as TokioMutex},
    time::timeout,
};

use crate::protect_socket;

use super::TcpConnectionId;

pub(crate) struct TcpConnection {
    id: TcpConnectionId,
    device_data_receiver: Receiver<Vec<u8>>,
    dst_data_buffer: Arc<TokioMutex<Vec<u8>>>,
}

impl TcpConnection {
    pub fn new(id: TcpConnectionId, device_data_receiver: Receiver<Vec<u8>>) -> Result<(Self, Arc<TokioMutex<Vec<u8>>>)> {
        let dst_data_buffer: Arc<TokioMutex<Vec<u8>>> = Default::default();
        Ok((
            Self {
                id,
                device_data_receiver,
                dst_data_buffer: dst_data_buffer.clone(),
            },
            dst_data_buffer,
        ))
    }

    pub async fn run(mut self) {
        tokio::spawn(async move {
            let tcp_connection_id = self.id;
            let dst_socket = match tokio::net::TcpSocket::new_v4() {
                Ok(dst_socket) => dst_socket,
                Err(e) => {
                    error!(">>>> Tcp connection [{tcp_connection_id}] fail to generate tokio tcp socket because of error: {e:?}");
                    return;
                }
            };
            let dst_socket_raw_fd = dst_socket.as_raw_fd();
            if let Err(e) = protect_socket(dst_socket_raw_fd) {
                error!(">>>> Tcp connection [{tcp_connection_id}] fail to protect tokio tcp socket because of error: {e:?}");
                return;
            };
            let dst_socket_addr = SocketAddr::new(
                IpAddr::V4(tcp_connection_id.dst_addr),
                tcp_connection_id.dst_port,
            );
            let dst_tcp_stream = match timeout(Duration::from_secs(5), dst_socket.connect(dst_socket_addr)).await {
                Ok(Ok(dst_tcp_stream)) => dst_tcp_stream,
                Ok(Err(e)) => {
                    error!(">>>> Tcp connection [{tcp_connection_id}] fail to connect to destination because of error: {e:?}");
                    return;
                }
                Err(_) => {
                    error!(">>>> Tcp connection [{tcp_connection_id}] fail to connect to destination because of timeout");
                    return;
                }
            };
            let (mut dst_tcp_read, mut dst_tcp_write) = dst_tcp_stream.into_split();

            tokio::spawn(async move {
                debug!("<<<< Tcp connection [{tcp_connection_id}] start destination to device relay.");
                loop {
                    let mut dst_data = vec![0u8; 65535];
                    let size = match dst_tcp_read.read(&mut dst_data).await {
                        Ok(0) => {
                            error!("<<<< Tcp connection [{tcp_connection_id}] read destination data complete.");
                            return;
                        }
                        Ok(size) => size,
                        Err(e) => {
                            error!("<<<< Tcp connection [{tcp_connection_id}] fail to read destination data because of error: {e:?}");
                            return;
                        }
                    };
                    let dst_data = &dst_data[..size];
                    let mut dst_data_buffer = self.dst_data_buffer.lock().await;
                    dst_data_buffer.extend_from_slice(dst_data);
                }
            });

            loop {
                let device_data = self.device_data_receiver.recv().await;
                let device_data = match device_data {
                    None => {
                        debug!(">>>> Tcp connection [{tcp_connection_id}] complete read device data");
                        break;
                    }
                    Some(device_data) => device_data,
                };

                debug!(
                    ">>>> Tcp connection [{tcp_connection_id}] receive tun data:\n{}\n",
                    pretty_hex::pretty_hex(&device_data)
                );
                if let Err(e) = dst_tcp_write.write(&device_data).await {
                    error!(">>>> Tcp connection [{tcp_connection_id}] fail to write tun data to destination because of error: {e:?}")
                };
                if let Err(e) = dst_tcp_write.flush().await {
                    error!(">>>> Tcp connection [{tcp_connection_id}] fail to flush tun data to destination because of error: {e:?}")
                };
            }
        });
    }
}
