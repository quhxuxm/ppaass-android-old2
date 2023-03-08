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
    socket::tcp::{Socket as VirtualTcpSocket, SocketBuffer},
    time::Instant,
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    sync::{
        mpsc::{channel, Receiver, Sender},
        Mutex as TokioMutex, Notify,
    },
    time::timeout,
};

use crate::{device::VirtualDevice, protect_socket};

use super::TcpConnectionId;

pub(crate) struct TcpConnection {
    id: TcpConnectionId,
    device_input_receiver: Arc<TokioMutex<Receiver<Vec<u8>>>>,
    virtual_socket_handle: SocketHandle,
    dst_data_sender: Sender<Vec<u8>>,
}

impl TcpConnection {
    pub fn new(id: TcpConnectionId, virtual_socket_handle: SocketHandle, device_input_receiver: Receiver<Vec<u8>>) -> Result<(Self, Receiver<Vec<u8>>)> {
        let (dst_data_sender, dst_data_receiver) = channel::<Vec<u8>>(1024);
        Ok((
            Self {
                id,
                device_input_receiver: Arc::new(TokioMutex::new(device_input_receiver)),
                virtual_socket_handle,
                dst_data_sender,
            },
            dst_data_receiver,
        ))
    }

    pub async fn run(self) {}
}
