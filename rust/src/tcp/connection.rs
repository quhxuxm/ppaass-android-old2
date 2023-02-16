use std::{
    net::{IpAddr, SocketAddr},
    os::fd::AsRawFd,
    sync::Arc,
    time::Duration,
};

use anyhow::anyhow;
use anyhow::Result;

use log::{debug, error};
use pretty_hex::pretty_hex;
use smoltcp::{
    iface::{SocketHandle, SocketSet},
    socket::tcp::{Socket, SocketBuffer},
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::tcp::OwnedReadHalf,
    sync::{
        mpsc::{channel, Receiver, Sender},
        Mutex,
    },
    time::timeout,
};

use crate::protect_socket;

use super::VpnTcpConnectionKey;

#[derive(Debug, Default)]
pub enum VpnTcpConnectionState {
    #[default]
    New,
    Initialized,
}

#[derive(Debug)]
pub(crate) struct VpnTcpConnection {
    vpn_tcp_connection_key: VpnTcpConnectionKey,
    vpn_tcp_socket_handle: SocketHandle,
    vpn_tcp_socketset: Arc<Mutex<SocketSet<'static>>>,
    vpn_tcp_connection_state: Arc<Mutex<VpnTcpConnectionState>>,
    tun_rx_sender: Sender<Vec<u8>>,
    tun_tx_sender: Sender<Vec<u8>>,
}

impl VpnTcpConnection {
    pub async fn new(
        vpn_tcp_connection_key: VpnTcpConnectionKey, vpn_tcp_socketset: Arc<Mutex<SocketSet<'static>>>, tun_tx_sender: Sender<Vec<u8>>,
    ) -> Result<(Self, SocketHandle)> {
        let listen_addr = SocketAddr::new(IpAddr::V4(vpn_tcp_connection_key.dst_addr), vpn_tcp_connection_key.dst_port);

        let vpn_tcp_socket_handle = {
            let mut vpn_tcp_socket = Socket::new(SocketBuffer::new(vec![0; 655350]), SocketBuffer::new(vec![0; 655350]));

            vpn_tcp_socket
                .listen::<SocketAddr>(listen_addr)
                .map_err(|e| anyhow!(">>>> Tcp connection [{vpn_tcp_connection_key}] fail to listen vpn tcp socket because of error: {e:?}"))?;
            let mut vpn_tcp_socketset = vpn_tcp_socketset.lock().await;
            vpn_tcp_socketset.add(vpn_tcp_socket)
        };
        let (tun_rx_sender, tun_rx_receiver) = channel::<Vec<u8>>(1024);

        let vpn_tcp_connection_state = Arc::new(Mutex::new(VpnTcpConnectionState::New));

        {
            let vpn_tcp_connection_state = vpn_tcp_connection_state.clone();
            let tun_tx_sender = tun_tx_sender.clone();
            tokio::spawn(async move {
                debug!(">>>> Tcp connection [{vpn_tcp_connection_key}] start state machine.");
                if let Err(e) = Self::start_state_machine(vpn_tcp_connection_key, vpn_tcp_connection_state, tun_tx_sender, tun_rx_receiver).await {
                    error!(">>>> Tcp connection [{vpn_tcp_connection_key}] fail to handle state machine because of error: {e:?}");
                };
            });
        }

        let vpn_tcp_connection = Self {
            vpn_tcp_connection_key,
            vpn_tcp_socket_handle,
            vpn_tcp_socketset,
            vpn_tcp_connection_state,
            tun_rx_sender,
            tun_tx_sender,
        };
        Ok((vpn_tcp_connection, vpn_tcp_socket_handle))
    }

    pub fn get_vpn_tcp_socket_handle(&self) -> SocketHandle {
        self.vpn_tcp_socket_handle
    }

    pub async fn tun_inbound(&self, data: &[u8]) -> Result<()> {
        debug!(
            ">>>> Tcp connection [{}] receive tun inbound: {}",
            self.vpn_tcp_connection_key,
            pretty_hex(&data)
        );
        self.tun_rx_sender.send(data.to_vec()).await?;
        Ok(())
    }

    pub async fn start_state_machine(
        vpn_tcp_connection_key: VpnTcpConnectionKey, vpn_tcp_connection_state: Arc<Mutex<VpnTcpConnectionState>>, tun_tx_sender: Sender<Vec<u8>>,
        mut tun_rx_receiver: Receiver<Vec<u8>>,
    ) -> Result<()> {
        let mut vpn_tcp_connection_state = vpn_tcp_connection_state.lock().await;
        let mut dst_tcp_read: Option<OwnedReadHalf> = None;
        if let VpnTcpConnectionState::New = *vpn_tcp_connection_state {
            debug!(">>>> Tcp connection [{vpn_tcp_connection_key}] going to connect to destination.");
            let dst_socket = tokio::net::TcpSocket::new_v4().map_err(|e| {
                error!(">>>> Tcp connection [{vpn_tcp_connection_key}] fail to generate tokio tcp socket because of error: {e:?}");
                anyhow!("Tcp connection [{vpn_tcp_connection_key}] fail to generate tokio tcp socket because of error: {e:?}")
            })?;
            let dst_socket_raw_fd = dst_socket.as_raw_fd();
            protect_socket(dst_socket_raw_fd).map_err(|e| {
                error!(">>>> Tcp connection [{vpn_tcp_connection_key}] fail to protect tokio tcp socket because of error: {e:?}");
                anyhow!("Tcp connection [{vpn_tcp_connection_key}] fail to protect tokio tcp socket because of error: {e:?}")
            })?;
            let dst_socket_addr = SocketAddr::new(IpAddr::V4(vpn_tcp_connection_key.dst_addr), vpn_tcp_connection_key.dst_port);
            let dst_tcp_stream = timeout(Duration::from_secs(5), dst_socket.connect(dst_socket_addr))
                .await
                .map_err(|e| {
                    error!(">>>> Tcp connection [{vpn_tcp_connection_key}] fail to connect to destination because of error: {e:?}");
                    anyhow!("Tcp connection [{vpn_tcp_connection_key}] fail to connect to destination because of error: {e:?}")
                })?
                .map_err(|e| {
                    error!(">>>> Tcp connection [{vpn_tcp_connection_key}] fail to connect to destination because of error: {e:?}");
                    anyhow!("Tcp connection [{vpn_tcp_connection_key}] fail to connect to destination because of error: {e:?}")
                })?;
            debug!(">>>> Tcp connection [{vpn_tcp_connection_key}] connect to destination success");
            let (dst_tcp_read_tmp, mut dst_tcp_write) = dst_tcp_stream.into_split();
            dst_tcp_read = Some(dst_tcp_read_tmp);
            *vpn_tcp_connection_state = VpnTcpConnectionState::Initialized;
            tokio::spawn(async move {
                loop {
                    let tun_rx_data = match tun_rx_receiver.recv().await {
                        Some(tun_rx_data) => tun_rx_data,
                        None => continue,
                    };
                    debug!(
                        ">>>> Tcp connection [{vpn_tcp_connection_key}] write data to destination:\n{}\n",
                        pretty_hex(&tun_rx_data)
                    );
                    if let Err(e) = dst_tcp_write.write(&tun_rx_data).await {
                        error!(">>>> Tcp connection [{vpn_tcp_connection_key}] fail to write tun data to destination because of error: {e:?}");
                    };
                    if let Err(e) = dst_tcp_write.flush().await {
                        error!(">>>> Tcp connection [{vpn_tcp_connection_key}] fail to flush tun data to destination because of error: {e:?}");
                    };
                }
            });
        };
        if let Some(mut dst_tcp_read) = dst_tcp_read {
            loop {
                let mut dst_buf = vec![0u8; 65535];
                let size = match dst_tcp_read.read(&mut dst_buf).await {
                    Ok(size) => size,
                    Err(e) => {
                        error!("<<<< Fail to read destination data because of error: {e:?}");
                        break;
                    },
                };
                let dst_buf = &dst_buf[..size];
                debug!(
                    "<<<< Tcp connection [{vpn_tcp_connection_key}] read destination data to tun:\n{}\n",
                    pretty_hex(&dst_buf)
                );
                if let Err(e) = tun_tx_sender.send(dst_buf.to_vec()).await {
                    error!("<<<< Fail to relay destination data to tun because of error: {e:?}");
                    break;
                }
            }
        }

        Ok(())
    }
}
