use anyhow::{anyhow, Result};
use jni::{objects::JObject, JNIEnv};
use log::debug;
use rand::random;

use std::{
    collections::HashMap,
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
    time::{sleep, timeout},
};

use etherparse::{Ipv4HeaderSlice, PacketBuilder, TcpHeaderSlice};

use crate::protect_socket;

use super::model::{TcpConnectionDataModel, TcpConnectionKey, TcpConnectionStatus};

const IP_PACKET_TTL: u8 = 64;
const WINDOW_SIZE: u16 = 32 * 1024;

pub(crate) struct TcpConnection<T>
where
    T: AsyncWrite + Unpin + Send + 'static,
{
    key: TcpConnectionKey,
    data_model: Arc<RwLock<TcpConnectionDataModel>>,
    device_output_stream: Arc<Mutex<T>>,
    destination_write: Option<OwnedWriteHalf>,
    destination_read_guard: Option<JoinHandle<Result<()>>>,
    connection_repository: Arc<RwLock<HashMap<TcpConnectionKey, TcpConnection<T>>>>,
}

impl<T> TcpConnection<T>
where
    T: AsyncWrite + Unpin + Send + 'static,
{
    pub fn new(
        key: TcpConnectionKey, device_output_stream: Arc<Mutex<T>>, connection_repository: Arc<RwLock<HashMap<TcpConnectionKey, TcpConnection<T>>>>,
    ) -> Self {
        TcpConnection {
            key,
            data_model: Default::default(),
            device_output_stream,
            destination_write: None,
            destination_read_guard: None,
            connection_repository,
        }
    }

    pub async fn close_connection(&self, data_model: &TcpConnectionDataModel, send_reset: bool) {
        if send_reset {
            if let Err(e) = send_rst_to_device(self.key, data_model, self.device_output_stream.clone()).await {
                debug!(">>>> Tcp connection [{}] fail to send reset to device because of error: {e:?}", self.key)
            };
        }
        let mut connection_repository = self.connection_repository.write().await;
        connection_repository.remove(&self.key);
    }

    pub async fn process<'a>(
        &mut self, _ipv4_header: Ipv4HeaderSlice<'a>, tcp_header: TcpHeaderSlice<'a>, payload: &'a [u8], jni_env: JNIEnv<'static>,
        vpn_service_java_obj: JObject<'static>,
    ) -> Result<()> {
        let mut data_model = self.data_model.write().await;

        match data_model.status {
            TcpConnectionStatus::Listen => {
                // Process the connection when the connection in Listen status
                debug!(
                    ">>>> Tcp connection [{}] in Listen status receive tcp header:\n\n{:#?}\n\nCurrent data_model:\n\n{data_model:#?}\n\n",
                    self.key,
                    tcp_header.to_header()
                );
                if !tcp_header.syn() {
                    debug!(
                        ">>>> Tcp connection [{}] in Listen status receive a invalid tcp packet, expect syn=true.",
                        self.key
                    );
                    self.close_connection(&data_model, true).await;
                    return Err(anyhow!(
                        "Tcp connection [{}] in Listen status receive a invalid tcp packet, expect syn=true.",
                        self.key
                    ));
                }

                let initial_send_sequence_number = random::<u32>();

                data_model.status = TcpConnectionStatus::SynReceived;

                data_model.current_segment_space.seg_seq = initial_send_sequence_number;
                // Expect next device tcp packet should increase the sequence by 1
                data_model.current_segment_space.seg_ack = tcp_header.sequence_number() + 1;
                data_model.current_segment_space.seg_wnd = WINDOW_SIZE;
                // Syn will also count into segment length, but the ack will not count in
                data_model.current_segment_space.seq_len = 1;

                data_model.send_sequence_space.iss = initial_send_sequence_number;
                data_model.send_sequence_space.snd_nxt = data_model.current_segment_space.seg_seq + 1;
                data_model.send_sequence_space.snd_una = data_model.current_segment_space.seg_seq;
                data_model.send_sequence_space.snd_wnd = WINDOW_SIZE;

                data_model.receive_sequence_space.irs = tcp_header.sequence_number();
                data_model.receive_sequence_space.rcv_nxt = tcp_header.sequence_number() + 1;
                data_model.receive_sequence_space.rcv_wnd = WINDOW_SIZE;

                if let Err(e) = send_syn_ack_to_device(self.key, &data_model, self.device_output_stream.clone()).await {
                    debug!(">>>> Tcp connection [{}] fail to send sync ack to device because of error: {e:?}", self.key,);
                    self.close_connection(&data_model, true).await;
                    return Err(anyhow!("Tcp connection [{}] fail to send sync ack to device because of error: {e:?}", self.key));
                };
                Ok(())
            },

            TcpConnectionStatus::SynReceived => {
                // Process the connection when the connection in SynReceived status
                debug!(
                    ">>>> Tcp connection [{}] in SynReceived status receive tcp header:\n\n{:#?}\n\nCurrent data_model:\n\n{data_model:#?}\n\n",
                    self.key,
                    tcp_header.to_header()
                );

                if tcp_header.syn() {
                    debug!(
                        ">>>> Tcp connection [{}] receive invalid tcp packet, expect receive sync=false, ack=true, but sync=true",
                        self.key,
                    );
                    self.close_connection(&data_model, true).await;
                    return Err(anyhow!(
                        "Tcp connection [{}] receive invalid tcp packet, expect receive sync=false, ack=true, but sync=true",
                        self.key
                    ));
                }
                if !tcp_header.ack() {
                    // In SynReceived status, connection should receive a ack.
                    debug!(
                        ">>>> Tcp connection [{}] receive invalid tcp packet, expect receive sync=false, ack=true, but sync=true",
                        self.key,
                    );
                    self.close_connection(&data_model, true).await;
                    return Err(anyhow!(
                        "Tcp connection [{}] receive invalid tcp packet, expect receive sync=false, ack=true, but ack=false",
                        self.key
                    ));
                }

                if tcp_header.sequence_number() != data_model.receive_sequence_space.rcv_nxt {
                    debug!(">>>> Tcp connection [{}] receive invalid tcp packet, data_model: {data_model:?}", self.key,);
                    self.close_connection(&data_model, true).await;
                    return Err(anyhow!("Tcp connection [{}] receive invalid tcp packet, data_model: {data_model:?}", self.key,));
                }

                if tcp_header.acknowledgment_number() != data_model.send_sequence_space.snd_una + 1 {
                    debug!(">>>> Tcp connection [{}] receive invalid tcp packet, data_model:\n {data_model:#?}", self.key,);
                    self.close_connection(&data_model, true).await;
                    return Err(anyhow!(
                        "Tcp connection [{}] receive invalid tcp packet, data_model:\n{data_model:#?}",
                        self.key,
                    ));
                }

                let destination_socket_address = SocketAddr::V4(SocketAddrV4::new(self.key.destination_address, self.key.destination_port));
                debug!(">>>> Tcp connection [{}] begin connect to [{destination_socket_address}]", self.key);
                let destination_tcp_socket = match TcpSocket::new_v4() {
                    Ok(destination_tcp_socket) => {
                        let destination_tcp_socket_raw_fd = destination_tcp_socket.as_raw_fd();
                        if let Err(e) = protect_socket(format!("{}", self.key), jni_env, vpn_service_java_obj, destination_tcp_socket_raw_fd) {
                            debug!(">>>> Tcp connection [{}] fail to protect destination socket because of error: {e:?}", self.key);
                            self.close_connection(&data_model, true).await;
                            return Err(anyhow!("Tcp connection [{}] fail to protect destination socket because of error", self.key));
                        };
                        if let Err(e) = destination_tcp_socket.set_reuseaddr(true) {
                            debug!(
                                ">>>> Tcp connection [{}] fail to set reuse address in destination socket because of error: {e:?}",
                                self.key
                            );
                            self.close_connection(&data_model, true).await;
                            return Err(anyhow!(
                                "Tcp connection [{}] fail to set reuse address in destination socket because of error",
                                self.key
                            ));
                        };
                        if let Err(e) = destination_tcp_socket.set_reuseport(true) {
                            debug!(
                                ">>>> Tcp connection [{}] fail to set reuse port in destination socket because of error: {e:?}",
                                self.key
                            );
                            self.close_connection(&data_model, true).await;
                            return Err(anyhow!(
                                "Tcp connection [{}] fail to set reuse port in destination socket because of error",
                                self.key
                            ));
                        };
                        destination_tcp_socket
                    },
                    Err(e) => {
                        debug!(
                            ">>>> Tcp connection [{}] fail to create destination tcp socket because of error: {e:?}",
                            self.key
                        );
                        self.close_connection(&data_model, true).await;
                        return Err(anyhow!("Tcp connection [{}] fail to create destination tcp socket because of error.", self.key));
                    },
                };

                let destination_tcp_stream = match timeout(Duration::from_secs(20), destination_tcp_socket.connect(destination_socket_address)).await {
                    Err(_) => {
                        debug!(">>>> Tcp connection [{}] fail connect to destination because of timeout.", self.key);
                        self.close_connection(&data_model, true).await;
                        return Err(anyhow!("Tcp connection [{}] fail connect to destination because of timeout.", self.key));
                    },
                    Ok(Ok(destination_tcp_stream)) => destination_tcp_stream,
                    Ok(Err(e)) => {
                        debug!(">>>> Tcp connection [{}] fail connect to destination because of error: {e:?}", self.key);
                        self.close_connection(&data_model, true).await;
                        return Err(anyhow!("Tcp connection [{}] fail connect to destination because of error: {e:?}", self.key));
                    },
                };
                debug!(">>>> Tcp connection [{}] success connect to [{destination_socket_address}]", self.key);
                let (destination_read, destination_write) = destination_tcp_stream.into_split();
                self.destination_write = Some(destination_write);
                data_model.status = TcpConnectionStatus::Established;
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

                data_model.current_segment_space.seg_seq += 1;
                data_model.send_sequence_space.snd_nxt = data_model.current_segment_space.seg_seq;
                data_model.send_sequence_space.snd_una = data_model.current_segment_space.seg_seq;

                debug!(
                    ">>>> Tcp connection [{}] in SynReceived status switch to Established status, data_model:\n{:#?}.",
                    self.key, &data_model
                );

                Ok(())
            },
            TcpConnectionStatus::Established => {
                // Process the connection when the connection in Established status
                debug!(
                    ">>>> Tcp connection [{}] in Established status receive tcp header:\n\n{:#?}\n\nCurrent data_model:\n\n{data_model:#?}\n\nReceive device payload:\n\n{}\n\n",
                    self.key,
                    tcp_header.to_header(),
                    pretty_hex::pretty_hex(&payload)
                );

                if data_model.current_segment_space.seg_seq < tcp_header.acknowledgment_number() {
                    debug!(">>>> Tcp connection [{}] fail to relay device data because of the current sequence not match the acknowledgment in tcp header, expect sequence: {}, incoming tcp header acknowledgment: {}", self.key, data_model.current_segment_space.seg_seq , tcp_header.acknowledgment_number());
                    self.close_connection(&data_model, true).await;
                    return Err(anyhow!(
                       "Tcp connection [{}] fail to relay device data because of the current sequence not match the acknowledgment in tcp header, expect sequence: {}, incoming tcp header acknowledgment: {}", self.key, data_model.current_segment_space.seg_seq , tcp_header.acknowledgment_number()
                    ));
                }

                if data_model.current_segment_space.seg_ack < tcp_header.sequence_number() {
                    debug!(">>>> Tcp connection [{}] fail to relay device data because of the current acknowledgment not match the sequence in tcp header, expect acknowledgment: {}, incoming tcp header sequence: {}", self.key, data_model.current_segment_space.seg_ack , tcp_header.sequence_number());
                    self.close_connection(&data_model, true).await;
                    return Err(anyhow!(
                       "Tcp connection [{}] fail to relay device data because of the current acknowledgment not match the sequence in tcp header, expect acknowledgment: {}, incoming tcp header sequence: {}", self.key, data_model.current_segment_space.seg_ack , tcp_header.sequence_number()
                    ));
                }

                if tcp_header.fin() {
                    debug!(
                        ">>>> Tcp connection [{}] in Established status receive fin switch to CLOSE_WAIT status",
                        self.key
                    );
                    data_model.status = TcpConnectionStatus::CloseWait;
                    data_model.current_segment_space.seg_ack += 1;
                    data_model.receive_sequence_space.rcv_nxt += 1;
                    send_ack_to_device(self.key, &data_model, self.device_output_stream.clone(), None).await?;
                    if let Some(ref destination_read_guard) = self.destination_read_guard {
                        destination_read_guard.abort();
                    }
                    debug!(
                        ">>>> Tcp connection [{}] in CloseWait status send fin to device, switch to LastAck status",
                        self.key
                    );
                    data_model.status = TcpConnectionStatus::LastAck;
                    send_fin_ack_to_device(self.key, &data_model, self.device_output_stream.clone()).await?;
                    return Ok(());
                }

                // Relay from device to destination.
                let relay_data_length = payload.len();
                let device_data_length: u32 = match relay_data_length.try_into() {
                    Ok(relay_data_length) => relay_data_length,
                    Err(e) => {
                        debug!(
                            ">>>> Tcp connection [{}] fail convert relay data length to u32 because of error: {e:?}",
                            self.key
                        );
                        self.close_connection(&data_model, true).await;
                        return Err(anyhow!("Tcp connection [{}] fail convert relay data length to u32 because of error.", self.key));
                    },
                };
                let Some(destination_write) = self.destination_write.as_mut() else{
                    debug!(
                            ">>>> Tcp connection [{}] fail to relay device data to destination because of the destination write not exist.",
                            self.key
                        );
                    self.close_connection(&data_model, true).await;
                    return Err(anyhow!("Tcp connection [{}] fail to relay device data to destination because of the destination write not exist.", self.key));
                };
                if let Err(e) = destination_write.write(payload).await {
                    debug!(
                        ">>>> Tcp connection [{}] fail to write relay tcp payload to destination because of error: {e:?}",
                        self.key
                    );
                    self.close_connection(&data_model, true).await;
                    return Err(anyhow!(
                        "Tcp connection [{}] fail to write relay tcp payload to destination because of error: {e:?}",
                        self.key
                    ));
                };
                if let Err(e) = destination_write.flush().await {
                    debug!(
                        ">>>> Tcp connection [{}] fail to flush relay tcp payload to destination because of error: {e:?}",
                        self.key
                    );
                    self.close_connection(&data_model, true).await;
                    return Err(anyhow!(
                        ">>>> Tcp connection [{}] fail to flush relay tcp payload to destination because of error: {e:?}",
                        self.key
                    ));
                };

                data_model.current_segment_space.seg_ack += device_data_length;
                data_model.receive_sequence_space.rcv_nxt = data_model.current_segment_space.seg_ack;
                send_ack_to_device(self.key, &data_model, self.device_output_stream.clone(), None).await?;

                Ok(())
            },
            TcpConnectionStatus::FinWait1 => {
                debug!(
                    ">>>> Tcp connection [{}] in FinWait1 status receive tcp header:\n\n{:#?}\n\nCurrent data_model:\n\n{data_model:#?}",
                    self.key,
                    tcp_header.to_header(),
                );

                if tcp_header.ack() && data_model.current_segment_space.seg_ack != tcp_header.sequence_number() {
                    debug!(">>>> Tcp connection [{}] in FinWait1 status, but can not close connection because of sequence number not match, expect sequence number: {}", self.key,  data_model.receive_sequence_space.rcv_nxt);
                    self.close_connection(&data_model, true).await;
                    return Err(anyhow!(">>>> Tcp connection [{}] in FinWait1 status, but can not close connection because of sequence number not match, expect sequence number: {}", self.key,  data_model.receive_sequence_space.rcv_nxt));
                }

                // if let Err(e) = send_fin_ack_to_device(self.key, &data_model, self.device_output_stream.clone()).await {
                //     debug!(">>>> Tcp connection [{}] fail to send fin ack to device because of error: {e:?}", self.key,);
                //     self.close_connection(&data_model, true).await;
                //     return Err(anyhow!("Tcp connection [{}] fail to send fin ack to device because of error: {e:?}", self.key));
                // }
                data_model.status = TcpConnectionStatus::FinWait2;
                data_model.current_segment_space.seg_seq += 1;
                data_model.receive_sequence_space.rcv_nxt += 1;
                debug!(
                    ">>>> Tcp connection [{}] in FinWait1 status switch to FinWait2 status, receive ack for fin.\n\nCurrent data_model:\n\n{data_model:#?}",
                    self.key
                );
                Ok(())
            },
            TcpConnectionStatus::FinWait2 => {
                debug!(
                    ">>>> Tcp connection [{}] in FinWait2 status receive tcp header:\n\n{:#?}\n\nCurrent data_model:\n\n{data_model:#?}",
                    self.key,
                    tcp_header.to_header(),
                );
                if tcp_header.ack() && !tcp_header.fin() && data_model.current_segment_space.seg_ack != tcp_header.sequence_number() {
                    debug!(">>>> Tcp connection [{}] in FinWait2 status, but can not close connection because of sequence number not match, expect sequence number: {}", self.key, data_model.receive_sequence_space.rcv_nxt);
                    self.close_connection(&data_model, true).await;
                    return Err(anyhow!(">>>> Tcp connection [{}] in FinWait2 status, but can not close connection because of sequence number not match, expect sequence number: {}", self.key, data_model.receive_sequence_space.rcv_nxt));
                }

                data_model.status = TcpConnectionStatus::TimeWait;
                debug!(
                    ">>>> Tcp connection [{}] in FinWait2 status switch to TimeWait status, receive ack for fin.\n\nCurrent data_model:\n\n{data_model:#?}",
                    self.key
                );
                let data_model_clone = self.data_model.clone();
                let connection_repository_clone = self.connection_repository.clone();
                let key = self.key;
                tokio::spawn(async move {
                    debug!(">>>> Tcp connection [{key}] in TimeWait status begin 2ML task.");
                    sleep(Duration::from_secs(20)).await;
                    let mut data_model = data_model_clone.write().await;
                    debug!(">>>> Tcp connection [{key}] in TimeWait status doing 2ML task.\n\nCurrent data_model:\n\n{data_model:#?}",);
                    data_model.status = TcpConnectionStatus::Closed;
                    let mut connection_repository = connection_repository_clone.write().await;
                    connection_repository.remove(&key);

                    debug!(
                        ">>>> Tcp connection [{key}] switch to Closed status , remove from the connection repository.\n\nCurrent data_model:\n\n{data_model:#?}",         
                    );
                });

                Ok(())
            },
            TcpConnectionStatus::TimeWait => {
                debug!(
                    ">>>> Tcp connection [{}] in TimeWait status receive tcp header:\n\n{:#?}\n\nCurrent data_model:\n\n{data_model:#?}",
                    self.key,
                    tcp_header.to_header(),
                );
                if let Err(e) = send_ack_to_device(self.key, &data_model, self.device_output_stream.clone(), None).await {
                    debug!(
                        ">>>> Tcp connection [{}] in TimeWait status fail to send ack to device because of error: {e:?}",
                        self.key,
                    );
                    self.close_connection(&data_model, true).await;
                    return Err(anyhow!(
                        "Tcp connection [{}] in TimeWait status fail to send ack to device because of error: {e:?}",
                        self.key,
                    ));
                }
                Ok(())
            },
            TcpConnectionStatus::CloseWait => {
                debug!(
                    ">>>> Tcp connection [{}] in CloseWait status receive tcp header:\n\n{:#?}\n\nCurrent data_model:\n\n{data_model:#?}",
                    self.key,
                    tcp_header.to_header(),
                );
                if let Err(e) = send_ack_to_device(self.key, &data_model, self.device_output_stream.clone(), None).await {
                    debug!(
                        ">>>> Tcp connection [{}] in CloseWait status fail to send ack to device because of error: {e:?}",
                        self.key,
                    );
                    self.close_connection(&data_model, true).await;
                    return Err(anyhow!(
                        "Tcp connection [{}] in CloseWait status fail to send ack to device because of error: {e:?}",
                        self.key,
                    ));
                }
                Ok(())
            },
            TcpConnectionStatus::LastAck => {
                debug!(
                    ">>>> Tcp connection [{}] in LastAck status receive tcp header:\n\n{:#?}\n\nCurrent data_model:\n\n{data_model:#?}",
                    self.key,
                    tcp_header.to_header(),
                );
                if data_model.current_segment_space.seg_ack != tcp_header.sequence_number() {
                    debug!(">>>> Tcp connection [{}] fail to close connection because of the current acknowledgment not match the sequence in tcp header, expect acknowledgment: {}, incoming tcp header sequence: {}", self.key, data_model.current_segment_space.seg_ack , tcp_header.sequence_number());
                    self.close_connection(&data_model, true).await;
                    return Err(anyhow!(
                       "Tcp connection [{}] fail to close connection because of the current acknowledgment not match the sequence in tcp header, expect acknowledgment: {}, incoming tcp header sequence: {}", self.key, data_model.current_segment_space.seg_ack , tcp_header.sequence_number()
                    ));
                }
                data_model.status = TcpConnectionStatus::Closed;
                let mut connection_repository = self.connection_repository.write().await;
                connection_repository.remove(&self.key);
                debug!(
                    ">>>> Tcp connection [{}] switch to Closed status , remove from the connection repository.\n\nCurrent data_model:\n\n{data_model:#?}",
                    self.key,
                );
                Ok(())
            },
            TcpConnectionStatus::Closed => {
                debug!(
                    ">>>> Tcp connection [{}] in Closed status receive tcp header:\n\n{:#?}\n\nCurrent data_model:\n\n{data_model:#?}",
                    self.key,
                    tcp_header.to_header(),
                );
                send_rst_to_device(self.key, &data_model, self.device_output_stream.clone()).await?;
                Ok(())
            },
        }
    }
}

impl<T> Drop for TcpConnection<T>
where
    T: AsyncWrite + Unpin + Send,
{
    fn drop(&mut self) {
        if let Some(ref destination_read_guard) = self.destination_read_guard {
            destination_read_guard.abort();
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
        debug!("<<<< Fail to generate sync ack packet because of error: {e:?}");
        return Err(anyhow!("Fail to generate sync ack packet because of error"));
    };

    let mut device_output_stream = device_output_stream.lock().await;
    if let Err(e) = device_output_stream.write(sync_ack_packet_bytes.as_ref()).await {
        debug!("<<<< Fail to write sync ack packet to device because of error: {e:?}");
        return Err(anyhow!("Fail to write sync ack packet to device because of error"));
    };
    if let Err(e) = device_output_stream.flush().await {
        debug!("<<<< Fail to flush sync ack packet to device because of error: {e:?}");
        return Err(anyhow!("Fail to write sync ack packet to device because of error"));
    };
    debug!(
        "<<<< Tcp connection [{}] write syn+ack to device, current data_model:\n{:#?}\n\n",
        key, &data_model
    );
    Ok(())
}

async fn send_fin_ack_to_device<T: AsyncWrite + Unpin + Send>(
    key: TcpConnectionKey, data_model: &TcpConnectionDataModel, device_output_stream: Arc<Mutex<T>>,
) -> Result<()> {
    let rst_ack_tcp_packet = PacketBuilder::ipv4(key.destination_address.octets(), key.source_address.octets(), IP_PACKET_TTL)
        .tcp(
            key.destination_port,
            key.source_port,
            data_model.current_segment_space.seg_seq,
            data_model.current_segment_space.seg_wnd,
        )
        .fin()
        .ack(data_model.current_segment_space.seg_ack);
    let mut rst_ack_packet_bytes = Vec::with_capacity(rst_ack_tcp_packet.size(0));
    if let Err(e) = rst_ack_tcp_packet.write(&mut rst_ack_packet_bytes, &[0u8; 0]) {
        debug!("<<<< Fail to generate fin ack packet because of error: {e:?}");
        return Err(anyhow!("Fail to generate rst ack packet because of error"));
    };

    let mut device_output_stream = device_output_stream.lock().await;
    if let Err(e) = device_output_stream.write(rst_ack_packet_bytes.as_ref()).await {
        debug!("<<<< Fail to write fin ack packet to device because of error: {e:?}");
        return Err(anyhow!("Fail to write rst ack packet to device because of error"));
    };
    if let Err(e) = device_output_stream.flush().await {
        debug!("<<<< Fail to flush fin ack packet to device because of error: {e:?}");
        return Err(anyhow!("Fail to write rst ack packet to device because of error"));
    };
    debug!("<<<< Tcp connection [{}] write fin to device, current data_model:\n{:#?}\n\n", key, &data_model);
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
        debug!("<<<< Fail to generate rst ack packet because of error: {e:?}");
        return Err(anyhow!("Fail to generate rst ack packet because of error"));
    };

    let mut device_output_stream = device_output_stream.lock().await;
    if let Err(e) = device_output_stream.write(rst_ack_packet_bytes.as_ref()).await {
        debug!("<<<< Fail to write rst ack packet to device because of error: {e:?}");
        return Err(anyhow!("Fail to write rst ack packet to device because of error"));
    };
    if let Err(e) = device_output_stream.flush().await {
        debug!("<<<< Fail to flush rst ack packet to device because of error: {e:?}");
        return Err(anyhow!("Fail to write rst ack packet to device because of error"));
    };
    debug!("<<<< Tcp connection [{}] write rst to device, current data_model:\n{:#?}\n\n", key, &data_model);
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
        debug!("<<<< Fail to generate sync ack packet because of error: {e:?}");
        return Err(anyhow!("Fail to generate sync ack packet because of error"));
    };

    let mut device_output_stream = device_output_stream.lock().await;
    if let Err(e) = device_output_stream.write(ack_packet_bytes.as_ref()).await {
        debug!("<<<< Fail to write sync ack packet to device because of error: {e:?}");
        return Err(anyhow!("Fail to write sync ack packet to device because of error"));
    };
    if let Err(e) = device_output_stream.flush().await {
        debug!("<<<< Fail to flush sync ack packet to device because of error: {e:?}");
        return Err(anyhow!("Fail to write sync ack packet to device because of error"));
    };

    debug!(
        "<<<< Tcp connection [{}] ack to device, current data_model:\n{:#?}\n\ndestination payload:\n\n{}\n\n",
        key,
        &data_model,
        pretty_hex::pretty_hex(&payload)
    );
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

                debug!("<<<< Tcp connection [{key}] read destination data complete send fin to device, data_model:\n\n{data_model:#?}\n\n");

                // Close the connection activally when read destination complete

                if let Err(e) = send_fin_ack_to_device(key, &data_model, device_output_stream).await {
                    debug!("<<<< Tcp connection [{key}] fail to send fin ack packet to device because of error: {e:?}");
                    return Err(anyhow!("Tcp connection [{key}] fail to send fin ack packet to device because of error"));
                };

                data_model.status = TcpConnectionStatus::FinWait1;

                debug!("<<<< Tcp connection [{key}] read destination data complete, switch to FinWait1 status.");
                return Ok(());
            },
            Ok(destination_read_data_size) => destination_read_data_size,
            Err(e) => {
                debug!("<<<< Tcp connection [{key}] fail to read destination data because of error: {e:?}");
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
            debug!("<<<< Tcp connection [{key}] fail to send ack to device because of error: {e:?}");
            return Err(anyhow!("Tcp connection [{key}] fail to send ack to device because of error"));
        };

        let destination_read_data_size: u32 = match destination_read_data_size.try_into() {
            Ok(size) => size,
            Err(e) => {
                debug!("<<<< Tcp connection [{key}] fail to convert destination read data size because of error: {e:?}");
                return Err(anyhow!("Tcp connection [{key}] fail to convert destination read data size because of error"));
            },
        };
        data_model.current_segment_space.seg_seq += destination_read_data_size;
        data_model.send_sequence_space.snd_nxt += destination_read_data_size;
        data_model.current_segment_space.seq_len = destination_read_data_size;
    }
}
