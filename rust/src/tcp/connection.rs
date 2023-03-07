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
    sync::{mpsc::Receiver, Mutex as TokioMutex, Notify},
    time::timeout,
};

use crate::{device::VirtualDevice, protect_socket};

use super::TcpConnectionId;

pub(crate) struct TcpConnection {
    id: TcpConnectionId,
    device_input_receiver: Arc<TokioMutex<Receiver<Vec<u8>>>>,
    virtual_iface: Arc<TokioMutex<Interface>>,
    virtual_device: Arc<TokioMutex<VirtualDevice>>,
    virtual_sockets: Arc<TokioMutex<SocketSet<'static>>>,
    virtual_socket_handle: SocketHandle,
    poll_iface_notifier: Arc<Notify>,
}

impl TcpConnection {
    pub async fn new(
        id: TcpConnectionId,
        virtual_iface: Arc<TokioMutex<Interface>>,
        virtual_device: Arc<TokioMutex<VirtualDevice>>,
        virtual_sockets: Arc<TokioMutex<SocketSet<'static>>>,
        device_input_receiver: Receiver<Vec<u8>>,
        poll_iface_notifier: Arc<Notify>,
    ) -> Result<Self> {
        let listen_addr = SocketAddr::new(IpAddr::V4(id.dst_addr), id.dst_port);
        let mut virtual_socket = SmolTcpSocket::new(
            SocketBuffer::new(vec![0; 65535]),
            SocketBuffer::new(vec![0; 65535]),
        );
        if let Err(e) = virtual_socket.listen::<SocketAddr>(listen_addr) {
            error!(">>>> Tcp connection [{id}] fail to listen vpn tcp socket because of error: {e:?}");
            return Err(anyhow!(
                ">>>> Tcp connection [{id}] fail to listen vpn tcp socket because of error: {e:?}"
            ));
        }
        let virtual_socket_handle = {
            let mut virtual_sockets = virtual_sockets.lock().await;
            virtual_sockets.add(virtual_socket)
        };
        debug!(">>>> Success create tcp connection [{id}]");
        poll_iface_notifier.notify_one();
        Ok(Self {
            id,
            device_input_receiver: Arc::new(TokioMutex::new(device_input_receiver)),
            virtual_sockets,
            virtual_socket_handle,
            virtual_iface,
            virtual_device,
            poll_iface_notifier,
        })
    }

    pub fn get_socket_handle(&self) -> SocketHandle {
        self.virtual_socket_handle
    }

    pub async fn start(&self) {
        let iface = self.virtual_iface.clone();
        let device = self.virtual_device.clone();
        let sockets = self.virtual_sockets.clone();
        let device_input_receiver = self.device_input_receiver.clone();
        let id = self.id;
        let socket_handle = self.virtual_socket_handle;
        debug!("Tcp connection [{id}] start to serve...");
        tokio::spawn(async move {
            let dst_socket = match tokio::net::TcpSocket::new_v4() {
                Ok(dst_socket) => dst_socket,
                Err(e) => {
                    error!(">>>> Tcp connection [{id}] fail to generate tokio tcp socket because of error: {e:?}");
                    return;
                }
            };
            let dst_socket_raw_fd = dst_socket.as_raw_fd();
            if let Err(e) = protect_socket(dst_socket_raw_fd) {
                error!(">>>> Tcp connection [{id}] fail to protect tokio tcp socket because of error: {e:?}");
                return;
            };
            let dst_socket_addr = SocketAddr::new(IpAddr::V4(id.dst_addr), id.dst_port);
            let sockets_for_connect = sockets.clone();
            let mut sockets_for_connect = sockets_for_connect.lock().await;
            let dst_tcp_stream = match timeout(Duration::from_secs(5), dst_socket.connect(dst_socket_addr)).await {
                Ok(Ok(dst_tcp_stream)) => dst_tcp_stream,
                Ok(Err(e)) => {
                    error!(">>>> Tcp connection [{id}] fail to connect to destination because of error: {e:?}");
                    sockets_for_connect.remove(socket_handle);
                    return;
                }
                Err(_) => {
                    error!(">>>> Tcp connection [{id}] fail to connect to destination because of timeout");
                    sockets_for_connect.remove(socket_handle);
                    return;
                }
            };
            drop(sockets_for_connect);
            debug!(">>>> Tcp connection [{id}] success connect to destination.");
            let (mut dst_tcp_read, mut dst_tcp_write) = dst_tcp_stream.into_split();
            tokio::spawn(async move {
                debug!("<<<< Tcp connection [{id}] start destination to device relay.");
                loop {
                    let mut iface = iface.lock().await;
                    let mut device = device.lock().await;

                    let mut sockets = sockets.lock().await;

                    let socket = sockets.get_mut::<SmolTcpSocket>(socket_handle);

                    if socket.can_send() {
                        let initial_data_size = socket.send_queue();
                        let mut dst_data = vec![0u8; initial_data_size];
                        let size = match dst_tcp_read.read(&mut dst_data).await {
                            Ok(0) => {
                                error!("<<<< Tcp connection [{id}] read destination data complete.");
                                return;
                            }
                            Ok(size) => size,
                            Err(e) => {
                                error!("<<<< Tcp connection [{id}] fail to read destination data because of error: {e:?}");
                                sockets.remove(socket_handle);
                                return;
                            }
                        };

                        if let Err(e) = socket.send_slice(&dst_data[..size]) {
                            error!("<<<< Tcp connection [{id}] fail to semd destination data to vpn device because of error: {e:?}");
                            return;
                        };
                    }
                    iface.poll(Instant::now(), &mut *device, &mut sockets);
                }
            });
            debug!(">>>> Tcp connection [{id}] start device to destination relay.");
            loop {
                let mut device_input_receiver = device_input_receiver.lock().await;
                let tun_input = device_input_receiver.recv().await;
                let tun_input = match tun_input {
                    None => {
                        debug!(">>>> Tcp connection [{id}] complete read tun data");
                        break;
                    }
                    Some(tun_input) => tun_input,
                };
                drop(device_input_receiver);
                debug!(
                    ">>>> Tcp connection [{id}] receive tun data:\n{}\n",
                    pretty_hex::pretty_hex(&tun_input)
                );
                if let Err(e) = dst_tcp_write.write(&tun_input).await {
                    error!(">>>> Tcp connection [{id}] fail to write tun data to destination because of error: {e:?}")
                };
                if let Err(e) = dst_tcp_write.flush().await {
                    error!(">>>> Tcp connection [{id}] fail to flush tun data to destination because of error: {e:?}")
                };
            }
        });
    }
}
