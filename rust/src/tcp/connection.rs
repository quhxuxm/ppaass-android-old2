use std::{
    net::{IpAddr, SocketAddr},
    os::fd::AsRawFd,
    task::{RawWaker, RawWakerVTable, Waker},
    time::Duration,
};

use anyhow::anyhow;
use anyhow::Result;

use futures::Future;
use log::{debug, error, info};

use smoltcp::{
    iface::{SocketHandle, SocketSet},
    socket::tcp::{Socket as SmolTcpSocket, SocketBuffer},
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
    receive_waker: Waker,
    receive_waker_vtable: RawWakerVTable,
    send_waker: Waker,
}

impl TcpConnection {
    pub fn new(connection_key: TcpConnectionKey) -> Result<Self> {
        let listen_addr = SocketAddr::new(IpAddr::V4(connection_key.dst_addr), connection_key.dst_port);
        let mut socket = SmolTcpSocket::new(SocketBuffer::new(vec![0; 655350]), SocketBuffer::new(vec![0; 655350]));
        let (receive_waker, receive_waker_vtable) = Self::create_receive_waker(&socket);
        socket.register_recv_waker(&receive_waker);
        let send_waker = Self::create_send_waker(&socket);
        socket.register_send_waker(&send_waker);
        match socket.listen::<SocketAddr>(listen_addr) {
            Ok(()) => Ok(Self {
                connection_key,
                receive_waker,
                receive_waker_vtable,
                send_waker,
            }),
            Err(e) => {
                error!(">>>> Tcp connection [{connection_key}] fail to listen vpn tcp socket because of error: {e:?}");
                Err(anyhow!(">>>> Tcp connection [{connection_key}] fail to listen vpn tcp socket because of error: {e:?}"))
            },
        }
    }

    fn recive_raw_waker_clone(data: *const ()) -> RawWaker {
        todo!()
    }

    fn recive_raw_waker_wake(data: *const ()) {
        todo!()
    }

    fn recive_raw_waker_wake_by_ref(data: *const ()) {
        todo!()
    }

    fn create_receive_waker(socket: *const SmolTcpSocket) -> (Waker, RawWakerVTable) {
        let vtble = RawWakerVTable::new(Self::recive_raw_waker_clone, Self::recive_raw_waker_wake, Self::recive_raw_waker_wake_by_ref, drop);
        let raw_waker = RawWaker::new(socket as *const (), &vtble);
        (unsafe { Waker::from_raw(raw_waker) }, vtble)
    }

    fn create_send_waker(socket: *const SmolTcpSocket) -> Waker {
        todo!()
    }
}
