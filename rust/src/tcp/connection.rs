use std::{
    net::{IpAddr, SocketAddr},
    os::fd::AsRawFd,
    sync::Arc,
    time::Duration,
};

use anyhow::anyhow;
use anyhow::Result;

use log::{debug, error};

use smoltcp::{
    iface::{Interface, SocketHandle, SocketSet},
    socket::tcp::{Socket as SmolTcpSocket, SocketBuffer},
    time::Instant,
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    sync::{mpsc::Receiver, Mutex as TokioMutex},
    time::timeout,
};

use crate::{device::PpaassVpnDevice, protect_socket};

use super::TcpConnectionKey;

pub(crate) struct TcpConnection {
    connection_key: TcpConnectionKey,
    tub_input_receiver: Arc<TokioMutex<Receiver<Vec<u8>>>>,
    iface: Arc<TokioMutex<Interface>>,
    device: Arc<TokioMutex<PpaassVpnDevice>>,
    sockets: Arc<TokioMutex<SocketSet<'static>>>,
    socket_handle: SocketHandle,
}

impl TcpConnection {
    pub async fn new(
        connection_key: TcpConnectionKey,
        iface: Arc<TokioMutex<Interface>>,
        device: Arc<TokioMutex<PpaassVpnDevice>>,
        sockets: Arc<TokioMutex<SocketSet<'static>>>,
        tub_input_receiver: Receiver<Vec<u8>>,
    ) -> Result<Self> {
        let listen_addr = SocketAddr::new(IpAddr::V4(connection_key.dst_addr), connection_key.dst_port);
        let mut socket = SmolTcpSocket::new(
            SocketBuffer::new(vec![0; 65535]),
            SocketBuffer::new(vec![0; 65535]),
        );
        if let Err(e) = socket.listen::<SocketAddr>(listen_addr) {
            error!(">>>> Tcp connection [{connection_key}] fail to listen vpn tcp socket because of error: {e:?}");
            return Err(anyhow!(
                ">>>> Tcp connection [{connection_key}] fail to listen vpn tcp socket because of error: {e:?}"
            ));
        }
        let socket_handle = {
            let mut sockets = sockets.lock().await;
            sockets.add(socket)
        };

        Ok(Self {
            connection_key,
            tub_input_receiver: Arc::new(TokioMutex::new(tub_input_receiver)),
            sockets,
            socket_handle,
            iface,
            device,
        })
    }

    pub fn get_socket_handle(&self) -> SocketHandle {
        self.socket_handle
    }

    pub async fn start(&self) {
        let iface = self.iface.clone();
        let device = self.device.clone();
        let sockets = self.sockets.clone();
        let tub_input_receiver = self.tub_input_receiver.clone();
        let connection_key = self.connection_key;
        let socket_handle = self.socket_handle;

        tokio::spawn(async move {
            debug!("Tcp connection [{connection_key}] start to serve...");
            let dst_socket = match tokio::net::TcpSocket::new_v4() {
                Ok(dst_socket) => dst_socket,
                Err(e) => {
                    error!(">>>> Tcp connection [{connection_key}] fail to generate tokio tcp socket because of error: {e:?}");
                    return;
                }
            };
            let dst_socket_raw_fd = dst_socket.as_raw_fd();
            if let Err(e) = protect_socket(dst_socket_raw_fd) {
                error!(">>>> Tcp connection [{connection_key}] fail to protect tokio tcp socket because of error: {e:?}");
                return;
            };
            let dst_socket_addr = SocketAddr::new(IpAddr::V4(connection_key.dst_addr), connection_key.dst_port);
            let dst_tcp_stream = match timeout(Duration::from_secs(10), dst_socket.connect(dst_socket_addr)).await {
                Ok(Ok(dst_tcp_stream)) => dst_tcp_stream,
                Ok(Err(e)) => {
                    error!(">>>> Tcp connection [{connection_key}] fail to connect to destination because of error: {e:?}");
                    return;
                }
                Err(_) => {
                    error!(">>>> Tcp connection [{connection_key}] fail to connect to destination because of timeout");
                    return;
                }
            };
            let (mut dst_tcp_read, mut dst_tcp_write) = dst_tcp_stream.into_split();
            tokio::spawn(async move {
                let mut dst_data = vec![0u8; 65535];
                loop {
                    let iface = iface.lock().await;
                    let device = device.lock().await;
                    
                    let mut sockets = sockets.lock().await;
                    
                    let socket = sockets.get_mut::<SmolTcpSocket>(socket_handle);

                    if socket.can_send() {
                        let min_data_size = if socket.send_queue() < dst_data.len() {
                            socket.send_queue()
                        } else {
                            dst_data.len()
                        };
                        if min_data_size > 0 {
                            let send_data = dst_data.drain(..min_data_size).collect::<Vec<u8>>();
                            if let Err(e) = socket.send_slice(&send_data) {
                                error!("<<<< Tcp connection [{connection_key}] fail to semd destination data to vpn device because of error: {e:?}");
                                return;
                            };
                            continue;
                        }
                    }
                    dst_data = vec![0u8; 65535];
                    let size = match dst_tcp_read.read(&mut dst_data).await {
                        Ok(0) => {
                            return;
                        }
                        Ok(size) => size,
                        Err(e) => {
                            error!("<<<< Tcp connection [{connection_key}] fail to read destination data because of error: {e:?}");
                            return;
                        }
                    };
                    dst_data = dst_data[..size].to_vec();
                }
            });
            loop {
                let mut tub_input_receiver = tub_input_receiver.lock().await;
                let tun_input = tub_input_receiver.recv().await;
                let tun_input = match tun_input {
                    None => {
                        break;
                    }
                    Some(tun_input) => tun_input,
                };
                drop(tub_input_receiver);
                debug!(
                    ">>>> Tcp connection [{connection_key}] receive tun data:\n{}\n",
                    pretty_hex::pretty_hex(&tun_input)
                );
                if let Err(e) = dst_tcp_write.write(&tun_input).await {
                    error!(">>>> Tcp connection [{connection_key}] fail to write tun data to destination because of error: {e:?}")
                };
                if let Err(e) = dst_tcp_write.flush().await {
                    error!(">>>> Tcp connection [{connection_key}] fail to flush tun data to destination because of error: {e:?}")
                };
            }
        });
    }
}
