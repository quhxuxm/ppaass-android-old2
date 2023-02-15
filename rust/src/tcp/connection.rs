use std::{
    collections::VecDeque,
    net::{IpAddr, SocketAddr},
    sync::Arc,
};

use anyhow::anyhow;
use anyhow::Result;

use smoltcp::{
    iface::{SocketHandle, SocketSet},
    socket::tcp::{Socket, SocketBuffer},
};
use tokio::{sync::Mutex, task::JoinHandle};

use super::VpnTcpConnectionKey;

#[derive(Debug)]
pub(crate) struct VpnTcpConnection {
    vpn_tcp_connection_key: VpnTcpConnectionKey,
    vpn_tcp_socket_handle: SocketHandle,
    vpn_tcp_socketset: Arc<Mutex<SocketSet<'static>>>,
    state_machine_guard: JoinHandle<()>,
    buffer: Arc<Mutex<VecDeque<Vec<u8>>>>,
}

impl VpnTcpConnection {
    pub async fn new(vpn_tcp_connection_key: VpnTcpConnectionKey, vpn_tcp_socketset: Arc<Mutex<SocketSet<'static>>>) -> Result<(Self, SocketHandle)> {
        let listen_addr = SocketAddr::new(IpAddr::V4(vpn_tcp_connection_key.dst_addr), vpn_tcp_connection_key.dst_port);

        let vpn_tcp_socket_handle = {
            let mut vpn_tcp_socket = Socket::new(SocketBuffer::new(vec![0; 655350]), SocketBuffer::new(vec![0; 655350]));

            vpn_tcp_socket
                .listen::<SocketAddr>(listen_addr)
                .map_err(|e| anyhow!(">>>> Fail to listen vpn tcp socket because of error: {e:?}"))?;
            let mut vpn_tcp_socketset = vpn_tcp_socketset.lock().await;
            vpn_tcp_socketset.add(vpn_tcp_socket)
        };

        let state_machine_guard = tokio::spawn(async move {});

        Ok((
            Self {
                vpn_tcp_connection_key,
                vpn_tcp_socket_handle,
                vpn_tcp_socketset,
                state_machine_guard,
                buffer: Default::default(),
            },
            vpn_tcp_socket_handle,
        ))
    }

    pub fn get_vpn_tcp_socket_handle(&self) -> SocketHandle {
        self.vpn_tcp_socket_handle
    }

    pub async fn inbound(&mut self, inbound: &[u8]) {
        let mut buffer = self.buffer.lock().await;
        buffer.push_back(inbound.to_vec());
    }

    pub async fn outbound(&mut self) -> Option<Vec<u8>> {
        let mut buffer = self.buffer.lock().await;
        buffer.pop_front()
    }
}
