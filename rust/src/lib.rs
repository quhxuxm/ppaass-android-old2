#![allow(non_snake_case)]
use std::{
    collections::{
        hash_map::Entry::{Occupied, Vacant},
        HashMap,
    },
    fs::Permissions,
    io::ErrorKind,
    os::fd::FromRawFd,
    sync::Arc,
    time::Duration,
};

use crate::{
    tcp::{connection::TcpConnection, model::TcpConnectionKey},
    udp::handler::{handle_udp_packet, UdpPacketInfo},
};
use android_logger::Config;
use anyhow::{anyhow, Result};

use etherparse::{
    InternetSlice::{Ipv4, Ipv6},
    TransportSlice::{Icmpv4, Icmpv6, Tcp, Udp, Unknown},
};

use jni::{objects::GlobalRef, JNIEnv, JavaVM};
use jni::{
    objects::{JClass, JObject, JValue},
    sys::jint,
};
use log::{debug, error, trace, LevelFilter};

use once_cell::sync::OnceCell;

use tokio::{
    fs::File,
    io::AsyncReadExt,
    runtime::{Builder as TokioRuntimeBuilder, Runtime},
    sync::{Mutex, RwLock},
    time::sleep,
};

mod tcp;
mod udp;

static mut VPN_RUNTIME: OnceCell<Runtime> = OnceCell::new();
static mut VPN_SERVICE: OnceCell<GlobalRef> = OnceCell::new();
static mut VPN_JVM: OnceCell<JavaVM> = OnceCell::new();

pub fn protect_socket(action_key: impl AsRef<str>, socket_fd: i32) -> Result<()> {
    let action_key = action_key.as_ref();
    debug!("Action of [{action_key}] begin to call vpn service protect socket fd, socket raw fd: {socket_fd}");
    let socket_fd_jni_arg = JValue::Int(socket_fd);
    let vpn_service = unsafe { VPN_SERVICE.get_mut() }.expect("Fail to get vpn service from global ref").as_obj();

    let jvm = unsafe { VPN_JVM.get_mut() }.expect("Fail to get jvm from global");

    let jni_env = jvm.attach_current_thread_permanently().expect("Fail to attach jni env to current thread");
    let protect_result = jni_env.call_method(vpn_service, "protect", "(I)Z", &[socket_fd_jni_arg]);
    let protect_result = match protect_result {
        Ok(protect_result) => protect_result,
        Err(e) => {
            error!("Action of [{action_key}] fail to protect socket because of error: {e:?}");
            return Err(anyhow!("Action of [{action_key}] fail to protect socket because of error: {e:?}"));
        },
    };
    match protect_result.z() {
        Ok(true) => {
            debug!("Action of [{action_key}] call vpn service protect socket java method success, socket raw fd: {socket_fd}");
            Ok(())
        },
        Ok(false) => {
            error!("Action of [{action_key}] fail to convert protect socket result because of return false");
            Err(anyhow!("Action of [{action_key}] fail to protect socket because of result is false"))
        },
        Err(e) => {
            error!("Action of [{action_key}] fail to convert protect socket result because of error: {e:?}");
            Err(anyhow!(
                "Action of [{action_key}] fail to convert protect socket result because of error: {e:?}"
            ))
        },
    }
}

/// # Safety
///
/// This function should not be called before the horsemen are ready.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ppaass_agent_rust_jni_RustLibrary_stopVpn(jni_env: JNIEnv, class: JClass) {
    if let Err(e) = concrete_stop_vpn(jni_env, class) {
        error!("Fail to stop vpn because of error: {e:?}");
    }
}

fn concrete_stop_vpn(_jni_env: JNIEnv, _class: JClass) -> Result<()> {
    if let Some(runtime) = unsafe { VPN_RUNTIME.take() } {
        runtime.shutdown_background();
    }
    Ok(())
}

/// # Safety
///
/// This function should not be called before the horsemen are ready.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ppaass_agent_rust_jni_RustLibrary_startVpn(
    jni_env: JNIEnv<'static>, class: JClass<'static>, device_fd: jint, vpn_service: JObject<'static>,
) {
    log::set_max_level(LevelFilter::Debug);
    android_logger::init_once(Config::default().with_tag("PPAASS-RUST"));
    let mut vpn_runtime_builder = TokioRuntimeBuilder::new_multi_thread();
    vpn_runtime_builder.worker_threads(32).enable_all().thread_name("PPAASS-RUST-THREAD");
    let vpn_runtime = vpn_runtime_builder.build().expect("Fail to start vpn runtime.");
    let vpn_runtime = unsafe { VPN_RUNTIME.get_or_init(|| vpn_runtime) };
    VPN_SERVICE
        .set(jni_env.new_global_ref(vpn_service).expect("Fail to generate global ref for vpn service."))
        .expect("Fail to set vpn service to global ref.");
    let jvm = jni_env.get_java_vm().expect("Fail to get jvm from jni env");
    if let Err(_) = VPN_JVM.set(jvm) {
        panic!("Fail to set vpn jvm to global");
    };
    vpn_runtime.block_on(async move {
        if let Err(e) = concrete_start_vpn(class, device_fd, vpn_service).await {
            error!("Fail to start vpn because of error: {e:?}");
        }
    });
}

async fn concrete_start_vpn(_class: JClass<'static>, device_fd: jint, vpn_service: JObject<'static>) -> Result<()> {
    debug!(">>>> Start vpn runtime success, device vpn file: {device_fd:?}");
    let tcp_connection_repository = Arc::new(RwLock::new(HashMap::<TcpConnectionKey, TcpConnection<_>>::new()));

    let device_file = unsafe { File::from_raw_fd(device_fd) };
    // let mut device_file_permissions = device_file.metadata().await.expect("Fail to get vpn file permissions").permissions();
    // device_file_permissions.set_readonly(false);
    // device_file
    //     .set_permissions(device_file_permissions)
    //     .await
    //     .expect("Fail to set vpn file to writable.");
    let (mut device_read, device_write) = tokio::io::split(device_file);

    let device_write = Arc::new(Mutex::new(device_write));
    loop {
        let mut ip_packet_buf = [0u8; 1024 * 64];

        let ip_packet_buf = match device_read.read(&mut ip_packet_buf).await {
            Ok(0) => continue,
            Ok(size) => &ip_packet_buf[..size],
            Err(e) => match e.kind() {
                ErrorKind::WouldBlock => {
                    continue;
                },
                _ => {
                    error!(">>>> Fail to read input bytes from device vpn file because of error: {e:?}");
                    continue;
                },
            },
        };

        let ip_packet = match etherparse::SlicedPacket::from_ip(ip_packet_buf) {
            Ok(vpn_packet) => vpn_packet,
            Err(e) => {
                error!(">>>> Fail to read ip packet from device vpn file because of error: {e:?}");
                continue;
            },
        };

        trace!(">>>> Read vpn packet: {ip_packet:?}");
        let ip_header = match ip_packet.ip {
            Some(ip_header) => {
                trace!(">>>> Vpn packet is ip packet, header: {ip_header:?}");
                ip_header
            },
            None => {
                error!(">>>> No ip packet in the vpn packet, skip and read next");
                continue;
            },
        };
        let transport = match ip_packet.transport {
            Some(transport) => transport,
            None => {
                error!(">>>> No transport in the vpn packet, skip and read next");
                continue;
            },
        };
        let (ipv4_header, _ipv4_extension) = match ip_header {
            Ipv6(_, _) => {
                trace!(">>>> Can not support ip v6, skip");
                continue;
            },
            Ipv4(header, extension) => (header, extension),
        };

        match transport {
            Icmpv4(icmp_header) => {
                trace!("Receive icmp v4 packet: {icmp_header:?}");
                continue;
            },
            Icmpv6(icmp_header) => {
                trace!(">>>> Receive icmp v6 packet: {icmp_header:?}");
                continue;
            },
            Udp(udp_header) => {
                let udp_payload = ip_packet.payload.to_vec();
                let destination_port = udp_header.destination_port();
                let destination_address = ipv4_header.destination_addr();
                let source_port = udp_header.source_port();
                let source_address = ipv4_header.source_addr();

                let udp_packet_info = UdpPacketInfo {
                    source_address,
                    source_port,
                    destination_address,
                    destination_port,
                    payload: udp_payload,
                    device_write: device_write.clone(),
                };
                if let Err(e) = handle_udp_packet(udp_packet_info).await {
                    error!(">>>> Fail to handle udp packet [{source_address}:{source_port}->{destination_address}:{destination_port}] because of error: {e:?}")
                };
                continue;
            },
            Tcp(tcp_header) => {
                let key = TcpConnectionKey {
                    destination_address: ipv4_header.destination_addr(),
                    destination_port: tcp_header.destination_port(),
                    source_address: ipv4_header.source_addr(),
                    source_port: tcp_header.source_port(),
                };
                let mut tcp_connection_repository_write = tcp_connection_repository.write().await;
                match tcp_connection_repository_write.entry(key) {
                    Occupied(mut entry) => {
                        debug!(">>>> Get existing tcp connection [{key}]");
                        let tcp_connection = entry.get_mut();

                        if let Err(e) = tcp_connection.process(ipv4_header, tcp_header, ip_packet.payload).await {
                            error!(">>>> Fail to process tcp connection [{key}] because of error(existing): {e:?}");
                            entry.remove();
                            continue;
                        };
                    },
                    Vacant(entry) => {
                        debug!(">>>> Create new tcp connection [{key}]");
                        let mut tcp_connection = TcpConnection::new(key, device_write.clone(), tcp_connection_repository.clone());

                        if let Err(e) = tcp_connection.process(ipv4_header, tcp_header, ip_packet.payload).await {
                            error!(">>>> Fail to process tcp connection [{key}] because of error(new): {e:?}");
                            continue;
                        };
                        entry.insert(tcp_connection);
                    },
                };
            },
            Unknown(unknown_protocol) => {
                error!(">>>> Receive unknown protocol: {unknown_protocol}");
                continue;
            },
        }
    }
}
