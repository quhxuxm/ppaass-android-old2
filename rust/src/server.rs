use std::{
    collections::{
        hash_map::Entry::{Occupied, Vacant},
        BTreeMap, HashMap,
    },
    fmt::Debug,
    fs::File,
    io::{ErrorKind, Read, Write},
    os::fd::FromRawFd,
    sync::Arc,
    time::Duration,
};

use anyhow::{anyhow, Context, Result};

use log::{debug, error, info, trace};

use smoltcp::{
    iface::{Interface, InterfaceBuilder, Routes, SocketHandle},
    socket::TcpSocket,
    time::Instant,
    wire::{IpAddress, IpCidr, Ipv4Address},
};
use tokio::{runtime::Builder as TokioRuntimeBuilder, sync::mpsc::channel};
use tokio::{runtime::Runtime as TokioRuntime, sync::Mutex};

use uuid::Uuid;

use crate::{device::PpaassVpnDevice, tcp::TcpConnectionKey};

pub(crate) struct PpaassVpnServer {
    id: String,
    runtime: Option<TokioRuntime>,
    tun_fd: i32,
    tcp_connection_repository: Arc<Mutex<HashMap<TcpConnectionKey, SocketHandle>>>,
}

impl Debug for PpaassVpnServer {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("PpaassVpnServer")
            .field("id", &self.id)
            .field("runtime", &self.runtime)
            .field("tun_fd", &self.tun_fd)
            .field("tcp_connection_repository", &self.tcp_connection_repository)
            .finish()
    }
}

impl PpaassVpnServer {
    pub(crate) fn new(tun_fd: i32) -> Result<Self> {
        let mut runtime_builder = TokioRuntimeBuilder::new_multi_thread();
        runtime_builder.worker_threads(32).enable_all().thread_name("PPAASS-RUST-THREAD");
        let runtime = runtime_builder.build().expect("Fail to start vpn runtime.");
        let id = Uuid::new_v4().to_string();
        let id = id.replace('-', "");

        info!("Create ppaass vpn server instance [{id}]");

        Ok(Self {
            id,
            runtime: Some(runtime),
            tun_fd,
            tcp_connection_repository: Arc::new(Mutex::new(HashMap::<TcpConnectionKey, SocketHandle>::new())),
        })
    }

    pub(crate) fn stop(&mut self) -> Result<()> {
        if let Some(runtime) = self.runtime.take() {
            runtime.shutdown_background();
        }

        info!("Ppaass vpn server instance [{}] stopped.", self.id);
        Ok(())
    }

    pub(crate) fn start(&mut self) -> Result<()> {
        info!("Start ppaass vpn server instance [{}]", self.id);

        let runtime = self
            .runtime
            .as_mut()
            .with_context(|| anyhow!("Fail to start ppaass vpn server [{}] becuase of no runtime.", self.id))?;

        let tcp_connection_repository = self.tcp_connection_repository.clone();
        let mut routes = Routes::new(BTreeMap::new());
        let default_gateway_ipv4 = Ipv4Address::new(0, 0, 0, 1);
        routes.add_default_ipv4_route(default_gateway_ipv4).unwrap();
        let mut interface = InterfaceBuilder::new(PpaassVpnDevice::new(), vec![])
            .any_ip(true)
            .ip_addrs([IpCidr::new(IpAddress::v4(0, 0, 0, 1), 0)])
            .routes(routes)
            .finalize();
        runtime.block_on(async move {
            let interface_poll_guard = tokio::spawn(async move {
                loop {
                    match interface.poll(Instant::now()) {
                        Ok(false) => {
                            tokio::time::sleep(Duration::from_millis(100)).await;
                            continue;
                        },
                        Ok(true) => {
                            let tcp_connection_repository = tcp_connection_repository.lock().await;
                            tcp_connection_repository.iter().for_each(|(tcp_connection_key, socket_handle)| {
                                let tcp_socket = interface.get_socket::<TcpSocket>(*socket_handle);
                                while tcp_socket.can_recv() {
                                    tcp_socket.recv(|raw_data| {})
                                }
                                while tcp_socket.can_send() {}
                            })
                        },
                        Err(_) => todo!(),
                    };
                }
            });
            let interface_rx_guard = tokio::spawn(async move {});
            let _ = tokio::join!(interface_tx_guard, interface_rx_guard);
        });

        Ok(())
    }
}
