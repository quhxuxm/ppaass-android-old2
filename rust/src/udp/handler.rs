use std::{
    net::{Ipv4Addr, SocketAddr, SocketAddrV4},
    os::fd::AsRawFd,
    sync::Arc,
    time::Duration,
};

use anyhow::{anyhow, Result};
use etherparse::PacketBuilder;
use jni::{objects::JObject, JNIEnv};
use log::debug;
use tokio::{fs::File, io::AsyncWriteExt, net::UdpSocket, sync::Mutex, time::timeout};

use crate::protect_socket;
use dns_parser::Packet as DnsPacket;

const IP_PACKET_TTL: u8 = 64;

fn generate_udp_packet_key(source_address: Ipv4Addr, source_port: u16, destination_address: Ipv4Addr, destination_port: u16) -> String {
    format!("{source_address}:{source_port}->{destination_address}:{destination_port}")
}

pub(crate) struct UdpPacketInfo {
    pub source_address: Ipv4Addr,
    pub source_port: u16,
    pub destination_address: Ipv4Addr,
    pub destination_port: u16,
    pub payload: Vec<u8>,
    pub device_output_stream: Arc<Mutex<File>>,
}

pub(crate) async fn handle_udp_packet(udp_packet_info: UdpPacketInfo, jni_env: JNIEnv<'static>, vpn_service_java_obj: JObject<'static>) -> Result<()> {
    let UdpPacketInfo {
        source_address,
        source_port,
        destination_address,
        destination_port,
        payload,

        device_output_stream,
    } = udp_packet_info;
    let udp_packet_key = generate_udp_packet_key(source_address, source_port, destination_address, destination_port);
    let local_socket_address = SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, 0));
    let local_udp_socket = match UdpSocket::bind(local_socket_address).await {
        Ok(local_udp_socket) => local_udp_socket,
        Err(e) => {
            debug!("Udp socket [{udp_packet_key}] fail to bind because of error: {e:?}");
            return Err(anyhow!(e));
        },
    };
    let udp_socket_raw_fd = local_udp_socket.as_raw_fd();
    if let Err(e) = protect_socket(&udp_packet_key, jni_env, vpn_service_java_obj, udp_socket_raw_fd) {
        debug!("Udp socket [{udp_packet_key}] fail to protect udp socket because of error: {e:?}");
        return Err(anyhow!(e));
    };

    tokio::spawn(async move {
        let destination_socket_address = SocketAddr::V4(SocketAddrV4::new(destination_address, destination_port));
        if let Err(e) = local_udp_socket.connect(destination_socket_address).await {
            debug!("Udp socket [{udp_packet_key}] fail connect to destination because of error: {e:?}");
            return Err(anyhow!(e));
        };

        debug!(
            "Udp socket [{udp_packet_key}] forward packet to destination, payload:\n\n{}",
            pretty_hex::pretty_hex(&payload)
        );
        if let Err(e) = local_udp_socket.send(&payload).await {
            debug!("Udp socket [{udp_packet_key}] fail send to destination because of error: {e:?}");
            return Err(anyhow!(e));
        };
        let dns_packet = match DnsPacket::parse(&payload) {
            Ok(dns_packet) => Some(dns_packet),
            Err(e) => {
                debug!("Udp socket [{udp_packet_key}] fail to parse dns question because of error: {e:?}");
                None
            },
        };
        if let Some(dns_packet) = dns_packet {
            debug!("Udp socket [{udp_packet_key}] send dns question packet to destination:\n\n{dns_packet:#?}");
        }
        debug!("Udp socket [{udp_packet_key}] success forward packet to destination, udp socket: {local_udp_socket:?}");
        // loop {
        let mut receive_data = vec![0; 1024 * 64];
        debug!("Udp socket [{udp_packet_key}] begin to receive data from destination, udp socket: {local_udp_socket:?}");
        let receive_data_size = match timeout(Duration::from_secs(5), local_udp_socket.recv(&mut receive_data)).await {
            Err(_) => {
                debug!("Udp socket [{udp_packet_key}] fail to receive destination data because of timeout.");
                return Err::<(), anyhow::Error>(anyhow!("Udp socket [{udp_packet_key}] fail to receive destination data because of timeout."));
            },
            Ok(Ok(0)) => {
                debug!("Udp socket [{udp_packet_key}] nothing receive from destination, udp socket: {local_udp_socket:?}");
                return Ok(());
            },
            Ok(Ok(receive_data_size)) => receive_data_size,
            Ok(Err(e)) => {
                debug!("Udp socket [{udp_packet_key}] fail to receive destination data because of error: {e:?}");
                return Err::<(), anyhow::Error>(anyhow!(e));
            },
        };
        let receive_data = &receive_data[0..receive_data_size];
        debug!(
            "Udp socket [{udp_packet_key}] success receive destination data:\n\n{}",
            pretty_hex::pretty_hex(&receive_data)
        );
        let dns_packet = match DnsPacket::parse(receive_data) {
            Ok(dns_packet) => Some(dns_packet),
            Err(e) => {
                debug!("Udp socket [{udp_packet_key}] fail to parse dns answer because of error: {e:?}");
                None
            },
        };
        if let Some(dns_packet) = dns_packet {
            debug!("Udp socket [{udp_packet_key}] receive dns answer packet from destination:\n\n{dns_packet:#?}");
        }
        let received_destination_udp_packet =
            PacketBuilder::ipv4(destination_address.octets(), source_address.octets(), IP_PACKET_TTL).udp(destination_port, source_port);
        let mut received_destination_udp_packet_bytes = Vec::with_capacity(received_destination_udp_packet.size(receive_data_size));
        if let Err(e) = received_destination_udp_packet.write(&mut received_destination_udp_packet_bytes, receive_data) {
            debug!("Udp socket [{udp_packet_key}] fail to prepare destination data udp packet write to device because of error: {e:?}");
            return Err::<(), anyhow::Error>(anyhow!(e));
        };
        let mut device_output_stream = device_output_stream.lock().await;
        if let Err(e) = device_output_stream.write(&received_destination_udp_packet_bytes).await {
            debug!("Udp socket [{udp_packet_key}] fail to write to device because of error: {e:?}");
            return Err::<(), anyhow::Error>(anyhow!(e));
        };
        if let Err(e) = device_output_stream.flush().await {
            debug!("Udp socket [{udp_packet_key}] fail to flush to device because of error: {e:?}");
            return Err::<(), anyhow::Error>(anyhow!(e));
        };
        Ok(())
        // }
    });

    Ok(())
}
