use anyhow::Result;
use jni::{objects::JObject, JNIEnv};
use log::{debug, error};

use std::{
    fmt::{Display, Formatter},
    net::{Ipv4Addr, SocketAddr, SocketAddrV4},
    ops::Deref,
    os::fd::AsRawFd,
    sync::Arc,
};
use tokio::{
    fs::File,
    io::{AsyncReadExt, AsyncWriteExt},
    net::{tcp::OwnedWriteHalf, TcpSocket},
    sync::{Mutex, RwLock},
    task::JoinHandle,
};

use etherparse::{Ipv4HeaderSlice, PacketBuilder, TcpHeaderSlice};

use crate::{protect_socket, tcp::log_tcp_header};

const IP_PACKET_TTL: u8 = 64;

#[derive(Debug, Clone, Copy, Hash, PartialEq, Eq)]
pub(crate) struct TcpConnectionKey {
    pub source_address: Ipv4Addr,
    pub source_port: u16,
    pub destination_address: Ipv4Addr,
    pub destination_port: u16,
}

impl Display for TcpConnectionKey {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "{}:{}->{}:{}",
            self.source_address, self.source_port, self.destination_address, self.destination_port
        )
    }
}

#[derive(Debug, PartialEq, Eq)]
pub(crate) enum TcpConnectionStatus {
    Listen,
    Closed,
    SynSent,
    SynReceived,
    Established,
    FinWait1,
    FinWait2,
    Closing,
    CloseWait,
    LastAck,
    TimeWait,
}

impl Display for TcpConnectionStatus {
    fn fmt(&self, f: &mut Formatter) -> std::fmt::Result {
        match *self {
            TcpConnectionStatus::Closed => write!(f, "CLOSED"),
            TcpConnectionStatus::Listen => write!(f, "LISTEN"),
            TcpConnectionStatus::SynSent => write!(f, "SYN-SENT"),
            TcpConnectionStatus::SynReceived => write!(f, "SYN-RECEIVED"),
            TcpConnectionStatus::Established => write!(f, "ESTABLISHED"),
            TcpConnectionStatus::FinWait1 => write!(f, "FIN-WAIT-1"),
            TcpConnectionStatus::FinWait2 => write!(f, "FIN-WAIT-2"),
            TcpConnectionStatus::CloseWait => write!(f, "CLOSE-WAIT"),
            TcpConnectionStatus::Closing => write!(f, "CLOSING"),
            TcpConnectionStatus::LastAck => write!(f, "LAST-ACK"),
            TcpConnectionStatus::TimeWait => write!(f, "TIME-WAIT"),
        }
    }
}

/// RFC-793: Send Sequence Variables
///
/// * SND.UNA - send unacknowledged
/// * SND.NXT - send next
/// * SND.WND - send window
/// * SND.UP  - send urgent pointer
/// * SND.WL1 - segment sequence number used for last window update
/// * SND.WL2 - segment acknowledgment number used for last window update
/// * ISS     - initial send sequence number
///
/// Send Sequence Space
///
///            1         2          3          4
///       ----------|----------|----------|----------
///              SND.UNA    SND.NXT    SND.UNA
///                                   +SND.WND
/// 1 - old sequence numbers which have been acknowledged
/// 2 - sequence numbers of unacknowledged data
/// 3 - sequence numbers allowed for new data transmission
/// 4 - future sequence numbers which are not yet allowed
///
/// If the data flow is momentarily idle and all data
/// sent has been acknowledged then the SND.UNA = SND.NXT = RCV.NXT
///
#[derive(Debug)]
pub(crate) struct SendSequenceSpace {
    /// The sender of data keeps track of the oldest
    /// unacknowledged sequence number in the
    /// variable SND.UNA
    ///
    /// When the data sender receives an acknowledgment
    /// it advances SND.UNA
    ///
    /// The amount by which the variables are advanced is the
    /// length of the data in the segment
    pub snd_una: u32,
    /// The sender of data keeps track of the next
    /// sequence number to use in the variable SND.NXT.
    ///
    /// When the sender creates a segment and transmits
    /// it the sender advances SND.NXT
    ///
    /// The amount by which the variables are advanced is the
    /// length of the data in the segment
    pub snd_nxt: u32,
    pub snd_wnd: u16,
    pub snd_up: bool,
    pub snd_wl1: u32,
    pub snd_wl2: u32,
    pub iss: u32,
}

/// RFC-793: Receive Sequence Variables
///
/// * RCV.NXT - receive next
/// * RCV.WND - receive window
/// * RCV.UP  - receive urgent pointer
/// * IRS     - initial receive sequence number
///
/// Receive Sequence Space
///
///                1          2          3
///            ----------|----------|----------
///                   RCV.NXT    RCV.NXT
///                             +RCV.WND
/// 1 - old sequence numbers which have been acknowledged
/// 2 - sequence numbers allowed for new reception
/// 3 - future sequence numbers which are not yet allowed
#[derive(Debug)]
pub(crate) struct ReceiveSequenceSpace {
    /// The receiver of data keeps track of the next
    /// sequence number to expect in the variable RCV.NXT
    ///
    /// When the receiver accepts a segment it advances RCV.NXT
    /// and sends an acknowledgment
    ///
    /// The amount by which the variables are advanced is the
    /// length of the data in the segment
    pub rcv_nxt: u32,
    pub rcv_wnd: u16,
    pub rcv_up: bool,
    pub irs: u32,
}

/// Current Segment Variables
///
/// SEG.SEQ - segment sequence number
/// SEG.ACK - segment acknowledgment number
/// SEG.LEN - segment length
/// SEG.WND - segment window
/// SEG.UP  - segment urgent pointer
/// SEG.PRC - segment precedence value
pub(crate) struct CurrentSegmentVariables {
    pub seg_seq: u32,
    pub seg_ack: u32,
    pub seg_wnd: u32,
    pub seg_up: bool,
    pub seg_prc: bool,
}

pub(crate) struct TcpConnection<'j> {
    key: TcpConnectionKey,
    status: Arc<RwLock<TcpConnectionStatus>>,
    send_sequence_space: Arc<RwLock<SendSequenceSpace>>,
    receive_sequence_space: ReceiveSequenceSpace,
    device_output_stream: Arc<Mutex<File>>,
    destination_write: Option<OwnedWriteHalf>,
    destination_read_guard: Option<JoinHandle<Result<()>>>,
    vpn_service_java_obj: JObject<'j>,
    jni_env: JNIEnv<'j>,
}

impl core::fmt::Debug for TcpConnection<'_> {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("TcpConnection")
            .field("key", &self.key)
            .field("status", &self.status)
            .field("send_sequence_space", &self.send_sequence_space)
            .field("receive_sequence_space", &self.receive_sequence_space)
            .finish()
    }
}

impl<'j> TcpConnection<'j> {
    pub fn new(key: TcpConnectionKey, device_output_stream: Arc<Mutex<File>>, jni_env: JNIEnv<'j>, vpn_service_java_obj: JObject<'j>) -> Self {
        let send_sequence_space = SendSequenceSpace {
            snd_una: 0,
            snd_nxt: 0,
            snd_wnd: 1500,
            snd_up: false,
            snd_wl1: 0,
            snd_wl2: 0,
            iss: 0,
        };
        let receive_sequence_space = ReceiveSequenceSpace {
            rcv_nxt: 0,
            rcv_wnd: 0,
            rcv_up: false,
            irs: 0,
        };
        TcpConnection {
            key,
            status: Arc::new(RwLock::new(TcpConnectionStatus::Listen)),
            send_sequence_space: Arc::new(RwLock::new(send_sequence_space)),
            receive_sequence_space,
            device_output_stream,
            destination_write: None,
            jni_env,
            vpn_service_java_obj,
            destination_read_guard: None,
        }
    }

    pub async fn process<'a>(&mut self, _ipv4_header: Ipv4HeaderSlice<'a>, tcp_header: TcpHeaderSlice<'a>, payload: &'a [u8]) -> Result<()> {
        log_tcp_header(&self.key, &tcp_header);
        let mut current_connection_status = self.status.write().await;
        match current_connection_status.deref() {
            TcpConnectionStatus::Listen => {
                if !tcp_header.syn() {
                    return Err(anyhow::anyhow!(
                        "Invalid connection status for tcp connection [{}], expect receive sync=true",
                        self.key
                    ));
                }
                let iss = rand::random::<u32>();
                *current_connection_status = TcpConnectionStatus::SynReceived;
                drop(current_connection_status);
                let mut send_sequence_space = self.send_sequence_space.write().await;
                send_sequence_space.iss = iss;
                send_sequence_space.snd_nxt = iss + 1;
                send_sequence_space.snd_una = send_sequence_space.snd_nxt;
                self.receive_sequence_space.irs = tcp_header.sequence_number();
                self.receive_sequence_space.rcv_nxt = tcp_header.sequence_number() + 1;
                self.receive_sequence_space.rcv_wnd = tcp_header.window_size();
                let mut device_output_stream = self.device_output_stream.lock().await;
                let sync_ack_tcp_packet = PacketBuilder::ipv4(self.key.destination_address.octets(), self.key.source_address.octets(), IP_PACKET_TTL)
                    .tcp(self.key.destination_port, self.key.source_port, iss, send_sequence_space.snd_wnd)
                    .syn()
                    .ack(self.receive_sequence_space.rcv_nxt);
                let mut sync_ack_packet_bytes = Vec::with_capacity(sync_ack_tcp_packet.size(0));
                if let Err(e) = sync_ack_tcp_packet.write(&mut sync_ack_packet_bytes, &[0u8; 0]) {
                    error!("Fail to generate sync ack packet because of error: {e:?}");
                    return Err(anyhow::anyhow!("Fail to generate sync ack packet because of error"));
                };
                if let Err(e) = device_output_stream.write(sync_ack_packet_bytes.as_ref()).await {
                    error!("Fail to write sync ack packet to device because of error: {e:?}");
                    return Err(anyhow::anyhow!("Fail to write sync ack packet to device because of error"));
                };
                if let Err(e) = device_output_stream.flush().await {
                    error!("Fail to flush sync ack packet to device because of error: {e:?}");
                    return Err(anyhow::anyhow!("Fail to write sync ack packet to device because of error"));
                };
                drop(send_sequence_space);
                Ok(())
            },
            TcpConnectionStatus::SynReceived => {
                if tcp_header.syn() {
                    return Err(anyhow::anyhow!(
                        "Receive invalid tcp packet for tcp connection [{}], expect receive sync=false, ack=true, but sync=true",
                        self.key
                    ));
                }
                if !tcp_header.ack() {
                    return Err(anyhow::anyhow!(
                        "Receive invalid tcp packet for tcp connection [{}], expect receive sync=false, ack=true, but ack=false",
                        self.key
                    ));
                }
                let send_sequence_space = self.send_sequence_space.read().await;
                if tcp_header.acknowledgment_number() != send_sequence_space.snd_una {
                    return Err(anyhow::anyhow!(
                        "Receive invalid tcp packet for tcp connection [{}], expect ack number={}, ack=true, but ack number={}",
                        self.key,
                        send_sequence_space.snd_una,
                        tcp_header.acknowledgment_number()
                    ));
                }
                drop(send_sequence_space);
                let destination_socket_address = SocketAddr::V4(SocketAddrV4::new(self.key.destination_address, self.key.destination_port));
                // let destination_socket_address = SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::new(110, 242, 68, 3), 80));
                debug!("Tcp connection [{}] begin connect to [{destination_socket_address}]", self.key);
                let destination_tcp_socket = match TcpSocket::new_v4() {
                    Ok(destination_tcp_socket) => {
                        let destination_tcp_socket_raw_fd = destination_tcp_socket.as_raw_fd();
                        if let Err(e) = protect_socket(format!("{}", self.key), self.jni_env, self.vpn_service_java_obj, destination_tcp_socket_raw_fd) {
                            error!("<<<< Tcp connection [{}] fail to protect destination socket because of error: {e:?}", self.key);
                            return Err(anyhow::anyhow!(
                                "Tcp connection [{}] fail to protect destination socket because of error",
                                self.key
                            ));
                        };
                        if let Err(e) = destination_tcp_socket.set_reuseaddr(true) {
                            error!(
                                "<<<< Tcp connection [{}] fail to set reuse address in destination socket because of error: {e:?}",
                                self.key
                            );
                            return Err(anyhow::anyhow!(
                                "Tcp connection [{}] fail to set reuse address in destination socket because of error",
                                self.key
                            ));
                        };
                        if let Err(e) = destination_tcp_socket.set_reuseport(true) {
                            error!(
                                "<<<< Tcp connection [{}] fail to set reuse port in destination socket because of error: {e:?}",
                                self.key
                            );
                            return Err(anyhow::anyhow!(
                                "Tcp connection [{}] fail to set reuse port in destination socket because of error",
                                self.key
                            ));
                        };
                        destination_tcp_socket
                    },
                    Err(e) => {
                        error!(
                            "<<<< Tcp connection [{}] fail to create destination tcp socket because of error: {e:?}",
                            self.key
                        );
                        return Err(anyhow::anyhow!(
                            "Tcp connection [{}] fail to create destination tcp socket because of error.",
                            self.key
                        ));
                    },
                };

                let destination_tcp_stream = match destination_tcp_socket.connect(destination_socket_address).await {
                    Ok(destination_tcp_stream) => destination_tcp_stream,
                    Err(e) => {
                        error!("<<<< Tcp connection [{}] fail connect to destination because of error: {e:?}", self.key);
                        return Err(anyhow::anyhow!("Fai connect to destion."));
                    },
                };
                debug!("<<<< Tcp connection [{}] success connect to [{destination_socket_address}]", self.key);
                let (mut destination_read, destination_write) = destination_tcp_stream.into_split();
                self.destination_write = Some(destination_write);
                *current_connection_status = TcpConnectionStatus::Established;
                drop(current_connection_status);
                self.receive_sequence_space.rcv_nxt = self.receive_sequence_space.irs + 1;
                self.receive_sequence_space.rcv_wnd = tcp_header.window_size();
                {
                    debug!(
                        "<<<< Tcp connection [{}] switch to Establish status, send sequence space: {:?}, receive sequence space: {:?}.",
                        self.key,
                        self.send_sequence_space.read().await,
                        self.receive_sequence_space
                    );
                }
                // Relay from destination to device.
                let send_sequence_space_clone = self.send_sequence_space.clone();
                let key_clone = self.key;
                let device_output_stream_clone = self.device_output_stream.clone();
                debug!("<<<< Tcp connection [{}] start destination read task.", self.key);
                let current_connection_status_clone = self.status.clone();
                let destination_read_guard = tokio::spawn(async move {
                    let current_connection_status = current_connection_status_clone;
                    let send_sequence_space = send_sequence_space_clone;
                    let key = key_clone;
                    let device_output_stream = device_output_stream_clone;
                    loop {
                        let mut destination_tcp_buf = Vec::with_capacity(1024 * 64);
                        let destination_read_data_size = match destination_read.read_buf(&mut destination_tcp_buf).await {
                            Ok(0) => {
                                debug!("<<<< Tcp connection [{key}] read destination data complete.");
                                let mut send_sequence_space = send_sequence_space.write().await;
                                send_sequence_space.snd_nxt += 1;
                                let fin_tcp_packet = PacketBuilder::ipv4(key.destination_address.octets(), key.source_address.octets(), IP_PACKET_TTL)
                                    .tcp(key.destination_port, key.source_port, send_sequence_space.snd_nxt, send_sequence_space.snd_wnd)
                                    .fin();

                                let mut fin_tcp_packet_bytes = Vec::with_capacity(fin_tcp_packet.size(0));
                                if let Err(e) = fin_tcp_packet.write(&mut fin_tcp_packet_bytes, &[0; 0]) {
                                    error!("<<<< Tcp connection [{key}] fail to generate fin packet because of error: {e:?}");
                                    return Err(anyhow::anyhow!("Tcp connection [{key}] fail to generate fin packet because of error"));
                                };
                                let mut device_output_stream = device_output_stream.lock().await;
                                if let Err(e) = device_output_stream.write(&fin_tcp_packet_bytes).await {
                                    error!("<<<< Tcp connection [{key}] fail to write fin packet to device because of error: {e:?}");
                                    return Err(anyhow::anyhow!("Tcp connection [{key}] fail to write fin packet to device because of error"));
                                };
                                if let Err(e) = device_output_stream.flush().await {
                                    error!("<<<< Tcp connection [{key}] fail to flush fin packet to device because of error: {e:?}");
                                    return Err(anyhow::anyhow!("Tcp connection [{key}] fail to flush fin packet to device because of error"));
                                };
                                let mut current_connection_status = current_connection_status.write().await;
                                *current_connection_status = TcpConnectionStatus::FinWait1;
                                debug!("<<<< Tcp connection [{key}] read destination data complete, switch to FinWait1 status.");
                                return Ok(());
                            },
                            Ok(destination_read_data_size) => destination_read_data_size,
                            Err(e) => {
                                error!("<<<< Tcp connection [{key}] fail to read destination data because of error: {e:?}");
                                return Err::<(), anyhow::Error>(anyhow::anyhow!(e));
                            },
                        };
                        let destination_tcp_buf = &destination_tcp_buf[..destination_read_data_size];
                        let mut send_sequence_space = send_sequence_space.write().await;
                        let destination_read_data_size: u32 = match destination_read_data_size.try_into() {
                            Ok(size) => size,
                            Err(e) => {
                                error!("<<<< Tcp connection [{key}] fail to convert destination read data size because of error: {e:?}");
                                return Err::<(), anyhow::Error>(anyhow::anyhow!(e));
                            },
                        };
                        send_sequence_space.snd_nxt += destination_read_data_size;

                        let destination_data_ack_tcp_packet = PacketBuilder::ipv4(key.destination_address.octets(), key.source_address.octets(), IP_PACKET_TTL)
                            .tcp(key.destination_port, key.source_port, send_sequence_space.snd_nxt, send_sequence_space.snd_wnd)
                            .ack(send_sequence_space.snd_una);

                        let mut destination_data_ack_tcp_packet_bytes =
                            Vec::with_capacity(destination_data_ack_tcp_packet.size(destination_read_data_size as usize));
                        if let Err(e) = destination_data_ack_tcp_packet.write(&mut destination_data_ack_tcp_packet_bytes, destination_tcp_buf) {
                            error!("<<<< Tcp connection [{key}] fail to generate sync ack packet because of error: {e:?}");
                            return Err(anyhow::anyhow!("Tcp connection [{key}] fail to generate sync ack packet because of error"));
                        };

                        debug!(
                            "<<<< Tcp connection [{key}] write tcp packet to device, sequence number: {}, ack number: {}, data:\n{}",
                            send_sequence_space.snd_nxt,
                            send_sequence_space.snd_una,
                            pretty_hex::pretty_hex(&destination_data_ack_tcp_packet_bytes)
                        );

                        let mut device_output_stream = device_output_stream.lock().await;
                        if let Err(e) = device_output_stream.write(&destination_data_ack_tcp_packet_bytes).await {
                            error!("<<<< Tcp connection [{key}] fail to write destination data packet to device because of error: {e:?}");
                            return Err(anyhow::anyhow!(
                                "Tcp connection [{key}] fail to write destination data packet to device because of error"
                            ));
                        };
                        if let Err(e) = device_output_stream.flush().await {
                            error!("<<<< Tcp connection [{key}] fail to flush destination data packet to device because of error: {e:?}");
                            return Err(anyhow::anyhow!(
                                "Tcp connection [{key}] fail to flush destination data packet to device because of error"
                            ));
                        };
                        drop(send_sequence_space);
                        drop(device_output_stream);
                    }
                });
                self.destination_read_guard = Some(destination_read_guard);
                Ok(())
            },
            TcpConnectionStatus::Established => {
                {
                    debug!(
                        ">>>> Tcp connection [{}] in [Established] status, send sequence space: {:?}, receive sequence space: {:?}, receive payload:\n{}",
                        self.key,
                        self.send_sequence_space.read().await,
                        self.receive_sequence_space,
                        pretty_hex::pretty_hex(&payload)
                    );
                }
                if self.receive_sequence_space.rcv_nxt != tcp_header.sequence_number() {
                    error!(">>>> Tcp connection [{}] fail to relay device data because of the expecting next receive sequence not match the sequence in tcp header, expect next receive sequence: {}, incoming tcp header sequence: {}", self.key, self.receive_sequence_space.rcv_nxt, tcp_header.sequence_number());
                    return Err(anyhow::anyhow!(
                       "Tcp connection [{}] fail to relay device data because of the expecting next receive sequence not match the sequence in tcp header, expect next receive sequence: {}, incoming tcp header sequence: {}", self.key, self.receive_sequence_space.rcv_nxt, tcp_header.sequence_number()
                    ));
                }
                // Relay from device to destination.
                let relay_data_length = payload.len();
                let device_data_length: u32 = match relay_data_length.try_into() {
                    Ok(relay_data_length) => relay_data_length,
                    Err(e) => {
                        error!(
                            ">>>> Tcp connection [{}] fail convert relay data length to u32 because of error: {e:?}",
                            self.key
                        );
                        return Err(anyhow::anyhow!(
                            "Tcp connection [{}] fail convert relay data length to u32 because of error.",
                            self.key
                        ));
                    },
                };
                let Some(destination_write) = self.destination_write.as_mut() else{
                    return Err(anyhow::anyhow!("Tcp connection [{}] no attached destination tcp stream existing in current connection.", self.key));
                };
                if let Err(e) = destination_write.write(payload).await {
                    error!(
                        ">>>> Tcp connection [{}] fail to write relay tcp payload to destination because of error: {e:?}",
                        self.key
                    );
                    return Err(anyhow::anyhow!(
                        "Tcp connection [{}] fail to write relay tcp payload to destination because of error: {e:?}",
                        self.key
                    ));
                };
                if let Err(e) = destination_write.flush().await {
                    error!(
                        ">>>> Tcp connection [{}] fail to flush relay tcp payload to destination because of error: {e:?}",
                        self.key
                    );
                    return Err(anyhow::anyhow!(
                        ">>>> Tcp connection [{}] fail to flush relay tcp payload to destination because of error: {e:?}",
                        self.key
                    ));
                };
                self.receive_sequence_space.rcv_nxt += device_data_length;
                let mut send_sequence_space = self.send_sequence_space.write().await;
                send_sequence_space.snd_una += device_data_length;
                let device_data_received_ack_tcp_packet =
                    PacketBuilder::ipv4(self.key.destination_address.octets(), self.key.source_address.octets(), IP_PACKET_TTL)
                        .tcp(
                            self.key.destination_port,
                            self.key.source_port,
                            send_sequence_space.snd_nxt,
                            send_sequence_space.snd_wnd,
                        )
                        .ack(send_sequence_space.snd_una);
                drop(send_sequence_space);
                let mut device_data_received_ack_tcp_packet_bytes = Vec::with_capacity(device_data_received_ack_tcp_packet.size(0));
                if let Err(e) = device_data_received_ack_tcp_packet.write(&mut device_data_received_ack_tcp_packet_bytes, &[0u8; 0]) {
                    error!(
                        ">>>> Tcp connection [{}] fail to generate device data ack packet because of error: {e:?}",
                        self.key
                    );
                    return Err(anyhow::anyhow!(
                        "Tcp connection [{}] fail to generate device data ack packet because of error",
                        self.key
                    ));
                };
                let mut device_output_stream = self.device_output_stream.lock().await;
                if let Err(e) = device_output_stream.write(device_data_received_ack_tcp_packet_bytes.as_ref()).await {
                    error!(
                        ">>>> Tcp connection [{}] fail to write device relay ack data packet to device because of error: {e:?}",
                        self.key
                    );
                    return Err(anyhow::anyhow!(
                        "Tcp connection [{}] fail to write device relay ack data packet to device because of error",
                        self.key
                    ));
                };
                if let Err(e) = device_output_stream.flush().await {
                    error!(
                        ">>>> Tcp connection [{}] fail to flush device relay ack data packet to device because of error: {e:?}",
                        self.key
                    );
                    return Err(anyhow::anyhow!(
                        "Tcp connection [{}] fail to flush device relay ack data packet to device because of error",
                        self.key
                    ));
                };

                Ok(())
            },
            TcpConnectionStatus::FinWait1 => {
                if tcp_header.ack() && self.receive_sequence_space.rcv_nxt != tcp_header.sequence_number() {}
                debug!(">>>> Tcp connection [{}] in FinWait1 status, receive ack for fin", self.key);
                Ok(())
            },
            TcpConnectionStatus::Closed => todo!(),

            TcpConnectionStatus::FinWait2 => todo!(),
            TcpConnectionStatus::Closing => todo!(),
            TcpConnectionStatus::TimeWait => todo!(),
            TcpConnectionStatus::SynSent => todo!(),
            TcpConnectionStatus::CloseWait => todo!(),
            TcpConnectionStatus::LastAck => todo!(),
        }
    }
}
