#![allow(non_snake_case)]
use std::{
    collections::{
        hash_map::Entry::{Occupied, Vacant},
        HashMap,
    },
    fs::File,
    io::{ErrorKind, Read, Write},
    os::fd::{FromRawFd, OwnedFd},
    sync::Arc,
};

use crate::{
    tcp::{connection::TcpConnection, model::TcpConnectionKey},
    udp::handler::{handle_udp_packet, UdpPacketInfo},
};
use android_logger::Config;
use anyhow::Result;

use etherparse::{
    InternetSlice::{Ipv4, Ipv6},
    TransportSlice::{Icmpv4, Icmpv6, Tcp, Udp, Unknown},
};

use jni::{objects::GlobalRef, JNIEnv};
use jni::{
    objects::{JClass, JObject, JValue},
    sys::jint,
};
use log::{debug, error, trace, LevelFilter};

use once_cell::sync::OnceCell;

use tokio::{
    runtime::{Builder as TokioRuntimeBuilder, Runtime},
    sync::{Mutex, RwLock},
};

mod tcp;
mod udp;

static mut VPN_RUNTIME: OnceCell<Runtime> = OnceCell::new();

pub fn protect_socket(action_key: impl AsRef<str>, jni_env: JNIEnv, vpn_service: JObject, socket_fd: i32) -> Result<()> {
    let action_key = action_key.as_ref();
    let socket_fd_jni_arg = JValue::Int(socket_fd);
    let protect_result = jni_env.call_method(vpn_service, "protect", "(I)Z", &[socket_fd_jni_arg]);
    let protect_result = match protect_result {
        Ok(protect_result) => protect_result,
        Err(e) => {
            debug!("Action of [{action_key}] fail to protect socket because of error: {e:?}");
            return Err(anyhow::anyhow!("Fail to protect socket"));
        },
    };
    let protect_result = match protect_result.z() {
        Ok(protect_result) => protect_result,
        Err(e) => {
            debug!("Action of [{action_key}] fail to convert protect socket result because of error: {e:?}");
            return Err(anyhow::anyhow!("Fail to convert protect socket result"));
        },
    };
    if !protect_result {
        return Err(anyhow::anyhow!("Action of [{action_key}] fail to protect socket because of result is false"));
    }
    debug!("Action of [{action_key}] call vpn service protect socket java method success, socket raw fd: {socket_fd}, protect result: {protect_result}");
    Ok(())
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
pub unsafe extern "C" fn Java_com_ppaass_agent_rust_jni_RustLibrary_startVpn(jni_env: JNIEnv, class: JClass, device_fd: jint, vpn_service: JObject) {
    android_logger::init_once(Config::default().with_tag("PPAASS-RUST").with_max_level(LevelFilter::Debug));

    if let Err(e) = concrete_start_vpn(jni_env, class, device_fd, vpn_service) {
        error!("Fail to start vpn because of error: {e:?}");
    }
}

fn concrete_start_vpn(jni_env: JNIEnv, _class: JClass, device_fd: jint, vpn_service: JObject) -> Result<()> {
    let mut vpn_runtime_builder = TokioRuntimeBuilder::new_multi_thread();
    vpn_runtime_builder.worker_threads(32).enable_all().thread_name("PPAASS-RUST-THREAD");
    let vpn_runtime = unsafe {
        VPN_RUNTIME.get_or_init(|| match vpn_runtime_builder.build() {
            Ok(vpn_handler_runtime) => vpn_handler_runtime,
            Err(e) => {
                debug!(">>>> Fail to create vpn runtime because of error: {e:?}");
                panic!(">>>> Fail to create vpn runtime because of error: {e:?}");
            },
        })
    };

    vpn_runtime.block_on(async move {
        debug!(">>>> Start vpn runtime success.");
        let tcp_connection_repository = Arc::new(RwLock::new(HashMap::<TcpConnectionKey, TcpConnection>::new()));
        let device_fd = unsafe { File::from_raw_fd(device_fd) };
        let device_file: File = device_fd;

        debug!(">>>> Device vpn file meta data: {:#?}", device_file.metadata());

        let device_file: Arc<Mutex<File>> = Arc::new(Mutex::new(device_file));

        let device_write = device_file.clone();
        let device_read = device_file.clone();

        loop {
            let mut ip_packet_buf = [0u8; 1024 * 64];
            let mut device_read = device_read.lock().await;
            let ip_packet_buf = match device_read.read(&mut ip_packet_buf) {
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
            drop(device_read);
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
            let (ipv4_header, ipv4_extension) = match ip_header {
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
                    if let Err(e) = handle_udp_packet(udp_packet_info, jni_env, vpn_service).await {
                        error!(
                            ">>>> Fail to handle udp packet [{source_address}:{source_port}->{destination_address}:{destination_port}] because of error: {e:?}"
                        )
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
                            debug!(">>>> Get existing tcp connection: {key}");
                            let tcp_connection = entry.get_mut();
                            if let Err(e) = tcp_connection.process(ipv4_header, tcp_header, ip_packet.payload, jni_env, vpn_service).await {
                                debug!(">>>> Fail to process tcp connection [{key}] because of error(existing): {e:?}");
                            };
                        },
                        Vacant(entry) => {
                            debug!(">>>> Create new tcp connection: {key}");
                            let tcp_connection = TcpConnection::new(key, device_write.clone(), tcp_connection_repository.clone());
                            let tcp_connection = entry.insert(tcp_connection);
                            if let Err(e) = tcp_connection.process(ipv4_header, tcp_header, ip_packet.payload, jni_env, vpn_service).await {
                                debug!(">>>> Fail to process tcp connection [{key}] because of error(new): {e:?}");
                            };
                        },
                    };
                    continue;
                },
                Unknown(unknown_protocol) => {
                    debug!(">>>> Receive unknown protocol: {unknown_protocol}");
                    continue;
                },
            }
        }
    });
    Ok(())
}
