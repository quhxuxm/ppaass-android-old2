#![allow(non_snake_case)]
use std::{
    collections::{
        hash_map::Entry::{Occupied, Vacant},
        HashMap,
    },
    os::fd::FromRawFd,
    sync::Arc,
};

use crate::{
    tcp::connection::{TcpConnection, TcpConnectionKey},
    udp::handler::{handle_udp_packet, UdpPacketInfo},
};
use android_logger::Config;
use anyhow::Result;
use etherparse::{
    InternetSlice::{Ipv4, Ipv6},
    TransportSlice::{Icmpv4, Icmpv6, Tcp, Udp, Unknown},
};
use jni::sys::{jobject, jstring};
use jni::JNIEnv;
use jni::{
    objects::{JClass, JObject, JString, JValue},
    sys::jint,
};
use log::{debug, error, info, trace, Level};
use tokio::{
    fs::File as TokioAsyncFile,
    io::AsyncReadExt,
    runtime::Builder as TokioRuntimeBuilder,
    sync::{Mutex, RwLock},
};

mod tcp;
mod udp;

/// # Safety
///
/// This function should not be called before the horsemen are ready.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ppaass_agent_rust_jni_RustLibrary_handleInputString(env: JNIEnv, _class: JClass, input_message: JString) -> jstring {
    let input_message: String = env.get_string(input_message).unwrap().into();
    let output_message = env.new_string(format!("Rust library: The input message: {input_message}")).unwrap();
    output_message.into_raw()
}

/// # Safety
///
/// This function should not be called before the horsemen are ready.
#[no_mangle]
pub unsafe extern "C" fn Java_com_ppaass_agent_rust_jni_RustLibrary_handleInputObject(env: JNIEnv, _class: JClass, inputObj: JObject) -> jobject {
    let name = env.get_field(inputObj, "name", "Ljava/lang/String;").unwrap().l().unwrap();
    let name: JString = name.into();
    let name: String = env.get_string(name).unwrap().into();

    let age = env.get_field(inputObj, "age", "I").unwrap().i().unwrap();

    let output_name = JValue::Object(env.new_string(format!("FROM-RUST: {name}")).unwrap().into());
    let output_age = JValue::Int(age + 1);

    let output_class = env.find_class("com/ppaass/agent/rust/jni/ExampleNativeObject").unwrap();
    let output_obj = env.alloc_object(output_class).unwrap();
    env.set_field(output_obj, "name", "Ljava/lang/String;", output_name).unwrap();
    env.set_field(output_obj, "age", "I", output_age).unwrap();
    output_obj.into_raw()
}

pub fn protect_socket(action_key: impl AsRef<str>, jni_env: JNIEnv, vpn_service_java_obj: JObject, socket_fd: i32) -> Result<()> {
    let action_key = action_key.as_ref();
    let socket_fd_jni_arg = JValue::Int(socket_fd);
    let protect_result = jni_env.call_method(vpn_service_java_obj, "protect", "(I)Z", &[socket_fd_jni_arg]);
    let protect_result = match protect_result {
        Ok(protect_result) => protect_result,
        Err(e) => {
            error!("Action of [{action_key}] fail to protect socket because of error: {e:?}");
            return Err(anyhow::anyhow!("Fail to protect socket"));
        },
    };
    let protect_result = match protect_result.z() {
        Ok(protect_result) => protect_result,
        Err(e) => {
            error!("Action of [{action_key}] fail to convert protect socket result because of error: {e:?}");
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
pub unsafe extern "C" fn Java_com_ppaass_agent_rust_jni_RustLibrary_startVpn(jni_env: JNIEnv, _class: JClass, device_fd: jint, vpn_service_java_obj: JObject) {
    android_logger::init_once(Config::default().with_min_level(Level::Debug).with_tag("PPAASS-RUST"));
    let mut vpn_handler_runtime_builder = TokioRuntimeBuilder::new_multi_thread();
    vpn_handler_runtime_builder
        .worker_threads(32)
        .enable_all()
        .thread_name("PPAASS-VPN-RUST-THREAD");
    let vpn_handler_tuntime = match vpn_handler_runtime_builder.build() {
        Ok(vpn_handler_tuntime) => vpn_handler_tuntime,
        Err(e) => {
            error!("Fail to create vpn handler runtime because of error: {e:?}");
            panic!("Fail to create vpn handler runtime because of error: {e:?}");
        },
    };

    vpn_handler_tuntime.block_on(async move {
        info!("Start vpn handler runtime success.");
        let mut tcp_connection_repository = HashMap::<TcpConnectionKey, TcpConnection>::new();
        let mut device_input_stream = unsafe { TokioAsyncFile::from_raw_fd(device_fd) };
        let device_output_stream = Arc::new(Mutex::new(unsafe { TokioAsyncFile::from_raw_fd(device_fd) }));
        loop {
            let mut vpn_data_buf = [0u8; 1024 * 32];
            let vpn_packet_buf = match device_input_stream.read(&mut vpn_data_buf).await {
                Ok(0) => {
                    error!("Nothing to read from vpn.");
                    continue;
                },
                Ok(size) => &vpn_data_buf[0..size],
                Err(e) => {
                    error!("Fail to read input bytes from vpn because of error: {e:?}");
                    return;
                },
            };
            let vpn_packet = match etherparse::SlicedPacket::from_ip(vpn_packet_buf) {
                Ok(vpn_packet) => vpn_packet,
                Err(e) => {
                    error!("Fail to read ip packet from vpn because of error: {e:?}");
                    continue;
                },
            };
            trace!("Read vpn packet: {vpn_packet:?}");
            let ip_header = match vpn_packet.ip {
                Some(ip_header) => {
                    trace!("Vpn packet is ip packet, header: {ip_header:?}");
                    ip_header
                },
                None => {
                    trace!("No ip packet in the vpn packet, skip and read next");
                    continue;
                },
            };
            let transport = match vpn_packet.transport {
                Some(transport) => transport,
                None => {
                    trace!("No transport in the vpn packet, skip and read next");
                    continue;
                },
            };
            let (ipv4_header, ipv4_extension) = match ip_header {
                Ipv6(_, _) => {
                    trace!("Can not support ip v6, skip");
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
                    trace!("Receive icmp v6 packet: {icmp_header:?}");
                    continue;
                },
                Udp(udp_header) => {
                    let udp_payload = vpn_packet.payload.to_vec();
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
                        jni_env,
                        vpn_service_java_obj,
                        device_output_stream: device_output_stream.clone(),
                    };
                    if let Err(e) = handle_udp_packet(udp_packet_info).await {
                        error!("Fail to handle udp packet [{source_address}:{source_port}->{destination_address}:{destination_port}] because of error: {e:?}")
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
                    match tcp_connection_repository.entry(key) {
                        Occupied(mut entry) => {
                            debug!("Get existing tcp connection: {key}");
                            let tcp_connection = entry.get_mut();
                            if let Err(e) = tcp_connection.process(ipv4_header, tcp_header, vpn_packet.payload).await {
                                error!("Fail to process tcp connection [{key}] because of error: {e:?}");
                            };
                            continue;
                        },
                        Vacant(entry) => {
                            debug!("Create new tcp connection: {key}");
                            let tcp_connection = TcpConnection::new(key, device_output_stream.clone(), jni_env, vpn_service_java_obj);
                            let tcp_connection = entry.insert(tcp_connection);
                            if let Err(e) = tcp_connection.process(ipv4_header, tcp_header, vpn_packet.payload).await {
                                error!("Fail to process tcp connection [{key}] because of error: {e:?}");
                            };
                            continue;
                        },
                    };
                },
                Unknown(unknown_protocol) => {
                    debug!("Receive unknown protocol: {unknown_protocol}");
                    continue;
                },
            }
        }
    });
}
