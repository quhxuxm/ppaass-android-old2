use std::{
    fmt::{Debug, Display},
    net::{Ipv4Addr, SocketAddr, SocketAddrV4},
    os::fd::AsRawFd,
    time::Duration,
};

use anyhow::{anyhow, Result};

use etherparse::PacketBuilder;

use log::debug;
use tokio::{net::UdpSocket, sync::mpsc::Sender, time::timeout};

use crate::protect_socket;
use dns_parser::Packet as DnsPacket;

const IP_PACKET_TTL: u8 = 64;

pub(crate) struct UdpPacketInfo {
    pub src_addr: Ipv4Addr,
    pub src_port: u16,
    pub dst_addr: Ipv4Addr,
    pub dst_port: u16,
    pub payload: Vec<u8>,
}

impl Debug for UdpPacketInfo {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}:{}->{}:{}", self.src_addr, self.src_port, self.dst_addr, self.dst_port)
    }
}

impl Display for UdpPacketInfo {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{self:?}")
    }
}

pub(crate) async fn handle_udp_packet<'a>(udp_packet_info: UdpPacketInfo, tun_output_sender: Sender<Vec<u8>>) -> Result<()> {
    let udp_packet_key = format!("{udp_packet_info}");
    let UdpPacketInfo {
        src_addr,
        src_port,
        dst_addr,
        dst_port,
        payload,
    } = udp_packet_info;
    let local_socket_address = SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, 0));
    let local_udp_socket = match UdpSocket::bind(local_socket_address).await {
        Ok(local_udp_socket) => local_udp_socket,
        Err(e) => {
            debug!(">>>> Udp socket [{udp_packet_key}] fail to bind because of error: {e:?}");
            return Err(anyhow!(e));
        },
    };
    let udp_socket_raw_fd = local_udp_socket.as_raw_fd();
    if let Err(e) = protect_socket(udp_socket_raw_fd) {
        debug!(">>>> Udp socket [{udp_packet_key}] fail to protect udp socket because of error: {e:?}");
        return Err(anyhow!(e));
    };

    tokio::spawn(async move {
        let destination_socket_address = SocketAddr::V4(SocketAddrV4::new(dst_addr, dst_port));
        if let Err(e) = local_udp_socket.connect(destination_socket_address).await {
            debug!(">>>> Udp socket [{udp_packet_key}] fail connect to destination because of error: {e:?}");
            return Err(anyhow!(e));
        };

        debug!(
            ">>>> Udp socket [{udp_packet_key}] forward packet to destination, payload:\n\n{}",
            pretty_hex::pretty_hex(&payload)
        );
        if let Err(e) = local_udp_socket.send(&payload).await {
            debug!(">>>> Udp socket [{udp_packet_key}] fail send to destination because of error: {e:?}");
            return Err(anyhow!(e));
        };
        let dns_packet = match DnsPacket::parse(&payload) {
            Ok(dns_packet) => Some(dns_packet),
            Err(e) => {
                debug!(">>>> Udp socket [{udp_packet_key}] fail to parse dns question because of error: {e:?}");
                None
            },
        };
        if let Some(dns_packet) = dns_packet {
            debug!(">>>> Udp socket [{udp_packet_key}] send dns question packet to destination:\n\n{dns_packet:#?}");
        }
        debug!(">>>> Udp socket [{udp_packet_key}] success forward packet to destination, udp socket: {local_udp_socket:?}");
        // loop {
        let mut receive_data = vec![0; 1024 * 64];
        debug!(">>>> Udp socket [{udp_packet_key}] begin to receive data from destination, udp socket: {local_udp_socket:?}");
        let receive_data_size = match timeout(Duration::from_secs(5), local_udp_socket.recv(&mut receive_data)).await {
            Err(_) => {
                debug!(">>>> Udp socket [{udp_packet_key}] fail to receive destination data because of timeout.");
                return Err::<(), anyhow::Error>(anyhow!("Udp socket [{udp_packet_key}] fail to receive destination data because of timeout."));
            },
            Ok(Ok(0)) => {
                debug!(">>>> Udp socket [{udp_packet_key}] nothing receive from destination, udp socket: {local_udp_socket:?}");
                return Ok(());
            },
            Ok(Ok(receive_data_size)) => receive_data_size,
            Ok(Err(e)) => {
                debug!(">>>> Udp socket [{udp_packet_key}] fail to receive destination data because of error: {e:?}");
                return Err::<(), anyhow::Error>(anyhow!(e));
            },
        };
        let receive_data = &receive_data[0..receive_data_size];
        debug!(
            ">>>> Udp socket [{udp_packet_key}] success receive destination data:\n\n{}",
            pretty_hex::pretty_hex(&receive_data)
        );
        let dns_packet = match DnsPacket::parse(receive_data) {
            Ok(dns_packet) => Some(dns_packet),
            Err(e) => {
                debug!(">>>> Udp socket [{udp_packet_key}] fail to parse dns answer because of error: {e:?}");
                None
            },
        };
        if let Some(dns_packet) = dns_packet {
            debug!(">>>> Udp socket [{udp_packet_key}] receive dns answer packet from destination:\n\n{dns_packet:#?}");
        }
        let received_destination_udp_packet = PacketBuilder::ipv4(dst_addr.octets(), src_addr.octets(), IP_PACKET_TTL).udp(dst_port, src_port);
        let mut received_destination_udp_packet_bytes = Vec::with_capacity(received_destination_udp_packet.size(receive_data_size));
        if let Err(e) = received_destination_udp_packet.write(&mut received_destination_udp_packet_bytes, receive_data) {
            debug!(">>>> Udp socket [{udp_packet_key}] fail to prepare destination data udp packet write to device because of error: {e:?}");
            return Err::<(), anyhow::Error>(anyhow!(e));
        };

        if let Err(e) = tun_output_sender.send(received_destination_udp_packet_bytes).await {
            debug!(">>>> Udp socket [{udp_packet_key}] fail to write to device because of error: {e:?}");
            return Err::<(), anyhow::Error>(anyhow!(e));
        }
        Ok(())
    });

    Ok(())
}
