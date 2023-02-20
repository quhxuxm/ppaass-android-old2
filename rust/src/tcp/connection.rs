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
    connection_key: VpnTcpConnectionKey,
    socket_handle: SocketHandle,
    tun_read_receiver: Receiver<Vec<u8>>,
    tun_write_sender: Sender<Vec<u8>>,
}

impl VpnTcpConnection {
    pub fn new(
        connection_key: VpnTcpConnectionKey, sockets: &mut SocketSet<'static>, tun_read_receiver: Receiver<Vec<u8>>, tun_write_sender: Sender<Vec<u8>>,
    ) -> Result<Self> {
        let listen_addr = SocketAddr::new(IpAddr::V4(connection_key.dst_addr), connection_key.dst_port);

        let socket_handle = {
            let mut socket = Socket::new(SocketBuffer::new(vec![0; 655350]), SocketBuffer::new(vec![0; 655350]));

            socket
                .listen::<SocketAddr>(listen_addr)
                .map_err(|e| anyhow!(">>>> Tcp connection [{connection_key}] fail to listen vpn tcp socket because of error: {e:?}"))?;

            sockets.add(socket)
        };
        let (tun_rx_sender, tun_rx_receiver) = channel::<Vec<u8>>(1024);

        Ok(Self {
            connection_key,
            socket_handle,
            tun_read_receiver,
            tun_write_sender,
        })
    }
}
