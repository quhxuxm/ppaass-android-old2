use std::{
    net::{IpAddr, SocketAddr},
    os::fd::AsRawFd,
    sync::Arc,
    task::{RawWaker, RawWakerVTable, Waker},
    time::Duration,
};

use anyhow::anyhow;
use anyhow::Result;

use futures::Future;
use log::{debug, error, info};

use smoltcp::{
    iface::{Interface, SocketHandle, SocketSet},
    socket::tcp::{Socket as SmolTcpSocket, SocketBuffer},
    time::Instant,
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    sync::{
        mpsc::{channel, Receiver},
        Mutex,
    },
    time::timeout,
};

use crate::{device::PpaassVpnDevice, protect_socket};

use super::TcpConnectionKey;

pub(crate) struct TcpConnection {
    connection_key: TcpConnectionKey,
    tub_input_receiver: Arc<Mutex<Receiver<Vec<u8>>>>,
    iface: Arc<Mutex<Interface>>,
    device: Arc<Mutex<PpaassVpnDevice>>,
    socket_set: Arc<Mutex<SocketSet<'static>>>,
    socket_handle: SocketHandle,
}

impl TcpConnection {
    pub async fn new(
        connection_key: TcpConnectionKey,
        iface: Arc<Mutex<Interface>>,
        device: Arc<Mutex<PpaassVpnDevice>>,
        tub_input_receiver: Receiver<Vec<u8>>,
    ) -> Result<Self> {
        let listen_addr = SocketAddr::new(IpAddr::V4(connection_key.dst_addr), connection_key.dst_port);
        let mut socket = SmolTcpSocket::new(SocketBuffer::new(vec![0; 655350]), SocketBuffer::new(vec![0; 655350]));
        if let Err(e) = socket.listen::<SocketAddr>(listen_addr) {
            error!(">>>> Tcp connection [{connection_key}] fail to listen vpn tcp socket because of error: {e:?}");
            return Err(anyhow!(">>>> Tcp connection [{connection_key}] fail to listen vpn tcp socket because of error: {e:?}"));
        }
        let mut socket_set = SocketSet::new(vec![]);
        let socket_handle = socket_set.add(socket);

        Ok(Self {
            connection_key,
            tub_input_receiver: Arc::new(Mutex::new(tub_input_receiver)),
            socket_set: Arc::new(Mutex::new(socket_set)),
            socket_handle,
            iface,
            device,
        })
    }

    pub async fn start(&self) {
        let iface = self.iface.clone();
        let device = self.device.clone();
        let socket_set = self.socket_set.clone();
        {
            let mut iface = iface.lock().await;
            let mut device = device.lock().await;
            let mut socket_set = socket_set.lock().await;
            iface.poll(Instant::now(), &mut *device, &mut socket_set);
        }
        let tub_input_receiver = self.tub_input_receiver.clone();
        let connection_key = self.connection_key;
        tokio::spawn(async move {
            loop {
                let mut tub_input_receiver = tub_input_receiver.lock().await;
                let tun_input = tub_input_receiver.recv().await;
                let tun_input = match tun_input {
                    None => {
                        break;
                    },
                    Some(tun_input) => tun_input,
                };
                drop(tub_input_receiver);
                debug!(">>>> Tcp connection [{connection_key}] receive tun data:\n{}\n", pretty_hex::pretty_hex(&tun_input))
            }
        });
    }
}
