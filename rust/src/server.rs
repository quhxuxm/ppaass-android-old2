use std::{
    collections::{
        hash_map::Entry::{Occupied, Vacant},
        HashMap,
    },
    fmt::Debug,
    fs::File,
    io::{ErrorKind, Read, Write},
    os::fd::FromRawFd,
    sync::Arc,
    time::Duration,
};

use anyhow::{anyhow, Result};

use log::{debug, error, info, trace};

use tokio::{runtime::Builder as TokioRuntimeBuilder, sync::mpsc::channel};
use tokio::{runtime::Runtime as TokioRuntime, sync::Mutex};

use uuid::Uuid;

use crate::{
    tcp::{TcpConnection, TcpConnectionKey, TcpConnectionTunHandle},
    udp::{handle_udp_packet, UdpPacketInfo},
};

pub(crate) struct PpaassVpnServer {
    id: String,
    runtime: Option<TokioRuntime>,
    tun_fd: i32,
    tcp_connection_repository: Arc<Mutex<HashMap<TcpConnectionKey, TcpConnection>>>,
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
            tcp_connection_repository: Arc::new(Mutex::new(HashMap::<TcpConnectionKey, TcpConnection>::new())),
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

        let Some(ref runtime) = self.runtime else{
            return Err(anyhow!("Fail to start ppaass vpn server [{}] becuase of no runtime.", self.id));
        };

        let tcp_connection_repository = self.tcp_connection_repository.clone();

        Ok(())
    }
}
