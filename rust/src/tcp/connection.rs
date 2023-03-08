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
    dst_data_buffer: Arc<TokioMutex<Vec<u8>>>,
}

impl TcpConnection {
    pub fn new(id: TcpConnectionId, device_input_receiver: Receiver<Vec<u8>>) -> Result<(Self, Arc<TokioMutex<Vec<u8>>>)> {
        let dst_data_buffer: Arc<TokioMutex<Vec<u8>>> = Default::default();
        Ok((
            Self {
                id,
                device_input_receiver: Arc::new(TokioMutex::new(device_input_receiver)),
                dst_data_buffer: dst_data_buffer.clone(),
            },
            dst_data_buffer,
        ))
    }

    pub async fn run(self) {
        
    }
}
