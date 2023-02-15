use std::{
    collections::VecDeque,
    net::{IpAddr, SocketAddr},
    os::fd::AsRawFd,
    sync::Arc,
    time::Duration,
};

use anyhow::anyhow;
use anyhow::Result;

use log::{debug, error};
use smoltcp::{
    iface::{SocketHandle, SocketSet},
    socket::tcp::{Socket, SocketBuffer},
};
use tokio::{
    sync::{
        mpsc::{channel, Sender},
        Mutex,
    },
    task::JoinHandle,
    time::timeout,
};

use crate::protect_socket;

use super::VpnTcpConnectionKey;

#[derive(Debug)]
pub(crate) struct VpnTcpConnection {
    vpn_tcp_connection_key: VpnTcpConnectionKey,
    vpn_tcp_socket_handle: SocketHandle,
    vpn_tcp_socketset: Arc<Mutex<SocketSet<'static>>>,
    tun_to_dst_guard: JoinHandle<()>,
    dst_to_tun_guard: JoinHandle<()>,
    tun_rx_sender: Sender<Vec<u8>>,
    tun_tx_sender: Sender<Vec<u8>>,
}

impl VpnTcpConnection {
    pub async fn new(
        vpn_tcp_connection_key: VpnTcpConnectionKey, vpn_tcp_socketset: Arc<Mutex<SocketSet<'static>>>, tun_tx_sender: Sender<Vec<u8>>,
    ) -> Result<(Self, SocketHandle)> {
        let listen_addr = SocketAddr::new(IpAddr::V4(vpn_tcp_connection_key.dst_addr), vpn_tcp_connection_key.dst_port);

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

        let vpn_tcp_socket_handle = {
            let mut vpn_tcp_socket = Socket::new(SocketBuffer::new(vec![0; 655350]), SocketBuffer::new(vec![0; 655350]));

            vpn_tcp_socket
                .listen::<SocketAddr>(listen_addr)
                .map_err(|e| anyhow!(">>>> Fail to listen vpn tcp socket because of error: {e:?}"))?;
            let mut vpn_tcp_socketset = vpn_tcp_socketset.lock().await;
            vpn_tcp_socketset.add(vpn_tcp_socket)
        };
        let (tun_rx_sender, mut tun_rx_receiver) = channel::<Vec<u8>>(1024);

        let tun_to_dst_guard = tokio::spawn(async move {
            loop {
                let tun_rx_data = tun_rx_receiver.recv().await;
                let Some(tun_rx_data) = tun_rx_data else{
                    continue;
                };
            }
        });
        let dst_to_tun_guard = tokio::spawn(async move {});

        Ok((
            Self {
                vpn_tcp_connection_key,
                vpn_tcp_socket_handle,
                vpn_tcp_socketset,
                tun_to_dst_guard,
                dst_to_tun_guard,
                tun_rx_sender,
                tun_tx_sender,
            },
            vpn_tcp_socket_handle,
        ))
    }

    pub fn get_vpn_tcp_socket_handle(&self) -> SocketHandle {
        self.vpn_tcp_socket_handle
    }

    pub async fn tun_inbound(&self, data: &[u8]) -> Result<()> {
        self.tun_rx_sender.send(data.to_vec()).await?;
        Ok(())
    }
}
