use anyhow::{anyhow, Result};
use jni::{objects::JObject, JNIEnv};
use log::{debug, error};
use rand::random;

use std::{
    net::{SocketAddr, SocketAddrV4},
    os::fd::AsRawFd,
    sync::Arc,
    time::Duration,
};
use tokio::{
    io::{AsyncReadExt, AsyncWrite, AsyncWriteExt},
    net::{
        tcp::{OwnedReadHalf, OwnedWriteHalf},
        TcpSocket,
    },
    sync::{Mutex, RwLock},
    task::JoinHandle,
    time::timeout,
};

use etherparse::{Ipv4HeaderSlice, PacketBuilder, TcpHeaderSlice};

use crate::{protect_socket, tcp::log_tcp_header};

use super::model::{TcpConnectionDataModel, TcpConnectionKey, TcpConnectionStatus};

const IP_PACKET_TTL: u8 = 64;
const WINDOW_SIZE: u16 = 32 * 1024;

pub(crate) struct TcpConnection<'j, T>
where
    T: AsyncWrite + Unpin + Send + 'static,
{
    key: TcpConnectionKey,
    data_model: Arc<RwLock<TcpConnectionDataModel>>,
    device_output_stream: Arc<Mutex<T>>,
    destination_write: Option<OwnedWriteHalf>,
    destination_read_guard: Option<JoinHandle<Result<()>>>,
    vpn_service_java_obj: JObject<'j>,
    jni_env: JNIEnv<'j>,
}

impl<'j, T> TcpConnection<'j, T>
where
    T: AsyncWrite + Unpin + Send + 'static,
{
    pub fn new(key: TcpConnectionKey, device_output_stream: Arc<Mutex<T>>, jni_env: JNIEnv<'j>, vpn_service_java_obj: JObject<'j>) -> Self {
        TcpConnection {
            key,
            data_model: Default::default(),
            device_output_stream,
            destination_write: None,
            jni_env,
            vpn_service_java_obj,
            destination_read_guard: None,
        }
    }

    pub async fn process<'a>(&mut self, _ipv4_header: Ipv4HeaderSlice<'a>, tcp_header: TcpHeaderSlice<'a>, payload: &'a [u8]) -> Result<()> {
        log_tcp_header(&self.key, &tcp_header, payload.len());

        let mut data_model = self.data_model.write().await;
        debug!(
            ">>>> Current tcp connection [{}] before process the data model is:\n{:#?}",
            self.key, data_model
        );

        match data_model.status {
            TcpConnectionStatus::Listen => {
                // Process the connection when the connection in Listen status
                if !tcp_header.syn() {
                    error!(
                        ">>>> Tcp connection [{}] in Listen status receive a invalid tcp packet, expect syn=true.",
                        self.key
                    );
                    return Err(anyhow!(
                        "Tcp connection [{}] in Listen status receive a invalid tcp packet, expect syn=true.",
                        self.key
                    ));
                }
                let initial_send_sequence_number = random::<u32>();

                data_model.status = TcpConnectionStatus::SynReceived;

                data_model.send_sequence_space.iss = initial_send_sequence_number;
                data_model.send_sequence_space.snd_nxt = initial_send_sequence_number;
                data_model.send_sequence_space.snd_una = initial_send_sequence_number;
                data_model.send_sequence_space.snd_wnd = WINDOW_SIZE;

                data_model.current_segment_space.seg_seq = initial_send_sequence_number;
                data_model.current_segment_space.seg_ack = tcp_header.sequence_number();
                data_model.current_segment_space.seg_wnd = WINDOW_SIZE;
                // The syn will also count into segment length,
                // but the ack will not count in
                data_model.current_segment_space.seq_len = 1;

                data_model.receive_sequence_space.irs = tcp_header.sequence_number();
                data_model.receive_sequence_space.rcv_nxt = tcp_header.sequence_number() + 1;
                data_model.receive_sequence_space.rcv_wnd = WINDOW_SIZE;

                debug!(">>>> Tcp connection [{}] in Listen status begin to send syn+ack to device.", self.key);
                if let Err(e) = send_syn_ack_to_device(self.key, &data_model, self.device_output_stream.clone()).await {
                    error!(">>>> Tcp connection [{}] fail to send sync ack to device because of error: {e:?}", self.key,);
                    return Err(anyhow!("Tcp connection [{}] fail to send sync ack to device because of error: {e:?}", self.key));
                };
                debug!("<<<< Tcp connection [{}] write sync ack to device, data_model: \n{:#?}", self.key, &data_model);
                Ok(())
            },

            TcpConnectionStatus::SynReceived => {
                // Process the connection when the connection in SynReceived status
                if tcp_header.syn() {
                    error!(
                        ">>>> Tcp connection [{}] receive invalid tcp packet, expect receive sync=false, ack=true, but sync=true",
                        self.key,
                    );
                    return Err(anyhow!(
                        "Tcp connection [{}] receive invalid tcp packet, expect receive sync=false, ack=true, but sync=true",
                        self.key
                    ));
                }
                if !tcp_header.ack() {
                    // In SynReceived status, connection should receive a ack.
                    error!(
                        ">>>> Tcp connection [{}] receive invalid tcp packet, expect receive sync=false, ack=true, but sync=true",
                        self.key,
                    );
                    return Err(anyhow!(
                        "Tcp connection [{}] receive invalid tcp packet, expect receive sync=false, ack=true, but ack=false",
                        self.key
                    ));
                }

                if tcp_header.acknowledgment_number() != data_model.send_sequence_space.snd_una {
                    error!(">>>> Tcp connection [{}] receive invalid tcp packet, data_model:\n {data_model:#?}", self.key,);
                    return Err(anyhow!(
                        "Tcp connection [{}] receive invalid tcp packet, data_model:\n{data_model:#?}",
                        self.key,
                    ));
                }

                if tcp_header.sequence_number() != data_model.receive_sequence_space.rcv_nxt {
                    error!(">>>> Tcp connection [{}] receive invalid tcp packet, data_model: {data_model:?}", self.key,);
                    return Err(anyhow!("Tcp connection [{}] receive invalid tcp packet, data_model: {data_model:?}", self.key,));
                }

                data_model.current_segment_space.seg_ack = tcp_header.acknowledgment_number();

                let destination_socket_address = SocketAddr::V4(SocketAddrV4::new(self.key.destination_address, self.key.destination_port));
                debug!(">>>> Tcp connection [{}] begin connect to [{destination_socket_address}]", self.key);
                let destination_tcp_socket = match TcpSocket::new_v4() {
                    Ok(destination_tcp_socket) => {
                        let destination_tcp_socket_raw_fd = destination_tcp_socket.as_raw_fd();
                        if let Err(e) = protect_socket(format!("{}", self.key), self.jni_env, self.vpn_service_java_obj, destination_tcp_socket_raw_fd) {
                            error!(">>>> Tcp connection [{}] fail to protect destination socket because of error: {e:?}", self.key);
                            return Err(anyhow!("Tcp connection [{}] fail to protect destination socket because of error", self.key));
                        };
                        if let Err(e) = destination_tcp_socket.set_reuseaddr(true) {
                            error!(
                                ">>>> Tcp connection [{}] fail to set reuse address in destination socket because of error: {e:?}",
                                self.key
                            );
                            return Err(anyhow!(
                                "Tcp connection [{}] fail to set reuse address in destination socket because of error",
                                self.key
                            ));
                        };
                        if let Err(e) = destination_tcp_socket.set_reuseport(true) {
                            error!(
                                ">>>> Tcp connection [{}] fail to set reuse port in destination socket because of error: {e:?}",
                                self.key
                            );
                            return Err(anyhow!(
                                "Tcp connection [{}] fail to set reuse port in destination socket because of error",
                                self.key
                            ));
                        };
                        destination_tcp_socket
                    },
                    Err(e) => {
                        error!(
                            ">>>> Tcp connection [{}] fail to create destination tcp socket because of error: {e:?}",
                            self.key
                        );
                        return Err(anyhow!("Tcp connection [{}] fail to create destination tcp socket because of error.", self.key));
                    },
                };

                let destination_tcp_stream = match timeout(Duration::from_secs(5), destination_tcp_socket.connect(destination_socket_address)).await {
                    Err(_) => {
                        error!(">>>> Tcp connection [{}] fail connect to destination because of timeout.", self.key);
                        return Err(anyhow!("Tcp connection [{}] fail connect to destination because of timeout.", self.key));
                    },
                    Ok(Ok(destination_tcp_stream)) => destination_tcp_stream,
                    Ok(Err(e)) => {
                        error!(">>>> Tcp connection [{}] fail connect to destination because of error: {e:?}", self.key);
                        return Err(anyhow!("Tcp connection [{}] fail connect to destination because of error: {e:?}", self.key));
                    },
                };
                debug!(">>>> Tcp connection [{}] success connect to [{destination_socket_address}]", self.key);
                let (destination_read, destination_write) = destination_tcp_stream.into_split();
                self.destination_write = Some(destination_write);
                data_model.status = TcpConnectionStatus::Established;
                debug!(
                    ">>>> Tcp connection [{}] switch to Establish status, data_model:\n{:#?}.",
                    self.key, &data_model
                );

                // Relay from destination to device.
                let data_model_clone = self.data_model.clone();
                let key_clone = self.key;
                let device_output_stream_clone = self.device_output_stream.clone();
                debug!(">>>> Tcp connection [{}] spawn destination read task.", self.key);
                let destination_read_guard = tokio::spawn(start_read_destination(
                    data_model_clone,
                    key_clone,
                    device_output_stream_clone,
                    destination_read,
                ));
                self.destination_read_guard = Some(destination_read_guard);
                Ok(())
            },
            TcpConnectionStatus::Established => {
                // Process the connection when the connection in Established status

                debug!(
                    ">>>> Tcp connection [{}] in [Established] status, data_model: {:#?} receive payload:\n{}",
                    self.key,
                    &data_model,
                    pretty_hex::pretty_hex(&payload)
                );

                if data_model.receive_sequence_space.rcv_nxt != tcp_header.sequence_number() {
                    error!(">>>> Tcp connection [{}] fail to relay device data because of the expecting next receive sequence not match the sequence in tcp header, expect next receive sequence: {}, incoming tcp header sequence: {}", self.key, data_model.receive_sequence_space.rcv_nxt, tcp_header.sequence_number());
                    return Err(anyhow!(
                       "Tcp connection [{}] fail to relay device data because of the expecting next receive sequence not match the sequence in tcp header, expect next receive sequence: {}, incoming tcp header sequence: {}", self.key, data_model.receive_sequence_space.rcv_nxt, tcp_header.sequence_number()
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
                        return Err(anyhow!("Tcp connection [{}] fail convert relay data length to u32 because of error.", self.key));
                    },
                };
                let Some(destination_write) = self.destination_write.as_mut() else{
                    error!(
                            ">>>> Tcp connection [{}] fail to relay device data to destination because of the destination write not exist.",
                            self.key
                        );
                    return Err(anyhow!("Tcp connection [{}] fail to relay device data to destination because of the destination write not exist.", self.key));
                };
                if let Err(e) = destination_write.write(payload).await {
                    error!(
                        ">>>> Tcp connection [{}] fail to write relay tcp payload to destination because of error: {e:?}",
                        self.key
                    );
                    return Err(anyhow!(
                        "Tcp connection [{}] fail to write relay tcp payload to destination because of error: {e:?}",
                        self.key
                    ));
                };
                if let Err(e) = destination_write.flush().await {
                    error!(
                        ">>>> Tcp connection [{}] fail to flush relay tcp payload to destination because of error: {e:?}",
                        self.key
                    );
                    return Err(anyhow!(
                        ">>>> Tcp connection [{}] fail to flush relay tcp payload to destination because of error: {e:?}",
                        self.key
                    ));
                };

                data_model.send_sequence_space.snd_una += device_data_length;
                data_model.receive_sequence_space.rcv_nxt += device_data_length;
                send_ack_to_device(self.key, &data_model, self.device_output_stream.clone(), None).await?;

                Ok(())
            },
            TcpConnectionStatus::FinWait1 => {
                let mut data_model_write = self.data_model.write().await;
                if tcp_header.ack() && data_model_write.receive_sequence_space.rcv_nxt != tcp_header.sequence_number() {
                    error!(">>>> Tcp connection [{}] in FinWait1 status, but can not close connection because of sequence number not match, expect sequence number: {}", self.key,  data_model_write.receive_sequence_space.rcv_nxt);
                    return Err(anyhow!(">>>> Tcp connection [{}] in FinWait1 status, but can not close connection because of sequence number not match, expect sequence number: {}", self.key,  data_model_write.receive_sequence_space.rcv_nxt));
                }

                data_model_write.status = TcpConnectionStatus::FinWait2;
                debug!(">>>> Tcp connection [{}] in FinWait1 status, receive ack for fin", self.key);
                Ok(())
            },
            TcpConnectionStatus::FinWait2 => {
                if tcp_header.ack() && tcp_header.fin() && data_model.receive_sequence_space.rcv_nxt != tcp_header.sequence_number() {
                    error!(">>>> Tcp connection [{}] in FinWait2 status, but can not close connection because of sequence number not match, expect sequence number: {}", self.key, data_model.receive_sequence_space.rcv_nxt);
                    return Err(anyhow!(">>>> Tcp connection [{}] in FinWait2 status, but can not close connection because of sequence number not match, expect sequence number: {}", self.key, data_model.receive_sequence_space.rcv_nxt));
                }

                send_ack_to_device(self.key, &data_model, self.device_output_stream.clone(), None).await?;

                data_model.status = TcpConnectionStatus::TimeWait;
                debug!(">>>> Tcp connection [{}] in FinWait2 status, receive ack for fin", self.key);

                Ok(())
            },
            TcpConnectionStatus::TimeWait => {
                send_rst_to_device(self.key, &data_model, self.device_output_stream.clone()).await?;
                data_model.status = TcpConnectionStatus::Closed;
                Ok(())
            },
            TcpConnectionStatus::Closed => todo!(),
            TcpConnectionStatus::Closing => todo!(),
            TcpConnectionStatus::SynSent => todo!(),
            TcpConnectionStatus::CloseWait => todo!(),
            TcpConnectionStatus::LastAck => todo!(),
        }
    }
}

async fn send_syn_ack_to_device<T: AsyncWrite + Unpin + Send>(
    key: TcpConnectionKey, data_model: &TcpConnectionDataModel, device_output_stream: Arc<Mutex<T>>,
) -> Result<()> {
    let sync_ack_tcp_packet = PacketBuilder::ipv4(key.destination_address.octets(), key.source_address.octets(), IP_PACKET_TTL)
        .tcp(
            key.destination_port,
            key.source_port,
            data_model.current_segment_space.seg_seq,
            data_model.current_segment_space.seg_wnd,
        )
        .syn()
        .ack(data_model.current_segment_space.seg_ack);
    let mut sync_ack_packet_bytes = Vec::with_capacity(sync_ack_tcp_packet.size(0));
    if let Err(e) = sync_ack_tcp_packet.write(&mut sync_ack_packet_bytes, &[0u8; 0]) {
        error!("<<<< Fail to generate sync ack packet because of error: {e:?}");
        return Err(anyhow!("Fail to generate sync ack packet because of error"));
    };

    let mut device_output_stream = device_output_stream.lock().await;
    if let Err(e) = device_output_stream.write(sync_ack_packet_bytes.as_ref()).await {
        error!("<<<< Fail to write sync ack packet to device because of error: {e:?}");
        return Err(anyhow!("Fail to write sync ack packet to device because of error"));
    };
    if let Err(e) = device_output_stream.flush().await {
        error!("<<<< Fail to flush sync ack packet to device because of error: {e:?}");
        return Err(anyhow!("Fail to write sync ack packet to device because of error"));
    };
    Ok(())
}

async fn send_rst_to_device<T: AsyncWrite + Unpin + Send>(
    key: TcpConnectionKey, data_model: &TcpConnectionDataModel, device_output_stream: Arc<Mutex<T>>,
) -> Result<()> {
    let rst_ack_tcp_packet = PacketBuilder::ipv4(key.destination_address.octets(), key.source_address.octets(), IP_PACKET_TTL)
        .tcp(
            key.destination_port,
            key.source_port,
            data_model.current_segment_space.seg_seq,
            data_model.current_segment_space.seg_wnd,
        )
        .rst()
        .ack(data_model.current_segment_space.seg_ack);
    let mut rst_ack_packet_bytes = Vec::with_capacity(rst_ack_tcp_packet.size(0));
    if let Err(e) = rst_ack_tcp_packet.write(&mut rst_ack_packet_bytes, &[0u8; 0]) {
        error!("<<<< Fail to generate rst ack packet because of error: {e:?}");
        return Err(anyhow!("Fail to generate rst ack packet because of error"));
    };

    let mut device_output_stream = device_output_stream.lock().await;
    if let Err(e) = device_output_stream.write(rst_ack_packet_bytes.as_ref()).await {
        error!("<<<< Fail to write rst ack packet to device because of error: {e:?}");
        return Err(anyhow!("Fail to write rst ack packet to device because of error"));
    };
    if let Err(e) = device_output_stream.flush().await {
        error!("<<<< Fail to flush rst ack packet to device because of error: {e:?}");
        return Err(anyhow!("Fail to write rst ack packet to device because of error"));
    };
    Ok(())
}

async fn send_ack_to_device<T: AsyncWrite + Unpin + Send>(
    key: TcpConnectionKey, data_model: &TcpConnectionDataModel, device_output_stream: Arc<Mutex<T>>, payload: Option<&[u8]>,
) -> Result<()> {
    let ack_tcp_packet = PacketBuilder::ipv4(key.destination_address.octets(), key.source_address.octets(), IP_PACKET_TTL)
        .tcp(
            key.destination_port,
            key.source_port,
            data_model.current_segment_space.seg_seq,
            data_model.current_segment_space.seg_wnd,
        )
        .ack(data_model.current_segment_space.seg_ack);
    let mut ack_packet_bytes = if let Some(payload) = payload {
        Vec::with_capacity(ack_tcp_packet.size(payload.len()))
    } else {
        Vec::with_capacity(ack_tcp_packet.size(0))
    };

    let payload = if let Some(payload) = payload {
        payload
    } else {
        &[0u8; 0]
    };
    if let Err(e) = ack_tcp_packet.write(&mut ack_packet_bytes, payload) {
        error!("<<<< Fail to generate sync ack packet because of error: {e:?}");
        return Err(anyhow!("Fail to generate sync ack packet because of error"));
    };

    let mut device_output_stream = device_output_stream.lock().await;
    if let Err(e) = device_output_stream.write(ack_packet_bytes.as_ref()).await {
        error!("<<<< Fail to write sync ack packet to device because of error: {e:?}");
        return Err(anyhow!("Fail to write sync ack packet to device because of error"));
    };
    if let Err(e) = device_output_stream.flush().await {
        error!("<<<< Fail to flush sync ack packet to device because of error: {e:?}");
        return Err(anyhow!("Fail to write sync ack packet to device because of error"));
    };
    Ok(())
}

/// Start a async task to read data from destination
async fn start_read_destination<T: AsyncWrite + Unpin + Send>(
    data_model: Arc<RwLock<TcpConnectionDataModel>>, key: TcpConnectionKey, device_output_stream: Arc<Mutex<T>>, mut destination_read: OwnedReadHalf,
) -> Result<(), anyhow::Error> {
    loop {
        let mut destination_tcp_buf = Vec::with_capacity(1024 * 64);
        let destination_read_data_size = match destination_read.read_buf(&mut destination_tcp_buf).await {
            Ok(0) => {
                let mut data_model = data_model.write().await;

                debug!("<<<< Tcp connection [{key}] read destination data complete, data_model: {data_model:?}");

                // Close the connection activally when read destination complete
                let fin_tcp_packet = PacketBuilder::ipv4(key.destination_address.octets(), key.source_address.octets(), IP_PACKET_TTL)
                    .tcp(
                        key.destination_port,
                        key.source_port,
                        data_model.send_sequence_space.snd_nxt,
                        data_model.send_sequence_space.snd_wnd,
                    )
                    .fin()
                    .ack(data_model.receive_sequence_space.rcv_nxt);

                let mut fin_tcp_packet_bytes = Vec::with_capacity(fin_tcp_packet.size(0));
                if let Err(e) = fin_tcp_packet.write(&mut fin_tcp_packet_bytes, &[0; 0]) {
                    error!("<<<< Tcp connection [{key}] fail to generate fin packet because of error: {e:?}");
                    return Err(anyhow!("Tcp connection [{key}] fail to generate fin packet because of error"));
                };
                let mut device_output_stream = device_output_stream.lock().await;
                if let Err(e) = device_output_stream.write(&fin_tcp_packet_bytes).await {
                    error!("<<<< Tcp connection [{key}] fail to write fin packet to device because of error: {e:?}");
                    return Err(anyhow!("Tcp connection [{key}] fail to write fin packet to device because of error"));
                };
                if let Err(e) = device_output_stream.flush().await {
                    error!("<<<< Tcp connection [{key}] fail to flush fin packet to device because of error: {e:?}");
                    return Err(anyhow!("Tcp connection [{key}] fail to flush fin packet to device because of error"));
                };

                data_model.status = TcpConnectionStatus::FinWait1;
                data_model.receive_sequence_space.rcv_nxt += 1;
                debug!("<<<< Tcp connection [{key}] read destination data complete, switch to FinWait1 status.");
                return Ok(());
            },
            Ok(destination_read_data_size) => destination_read_data_size,
            Err(e) => {
                error!("<<<< Tcp connection [{key}] fail to read destination data because of error: {e:?}");
                return Err::<(), anyhow::Error>(anyhow!(e));
            },
        };

        let mut data_model = data_model.write().await;

        if let Err(e) = send_ack_to_device(
            key,
            &data_model,
            device_output_stream.clone(),
            Some(&destination_tcp_buf[..destination_read_data_size]),
        )
        .await
        {
            error!("<<<< Tcp connection [{key}] fail to send ack to device because of error: {e:?}");
            return Err(anyhow!("Tcp connection [{key}] fail to send ack to device because of error"));
        };

        let destination_read_data_size: u32 = match destination_read_data_size.try_into() {
            Ok(size) => size,
            Err(e) => {
                error!("<<<< Tcp connection [{key}] fail to convert destination read data size because of error: {e:?}");
                return Err(anyhow!("Tcp connection [{key}] fail to convert destination read data size because of error"));
            },
        };

        data_model.send_sequence_space.snd_nxt += destination_read_data_size;
    }
}
