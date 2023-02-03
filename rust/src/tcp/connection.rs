use std::{
    collections::HashMap,
    fmt::Debug,
    net::{IpAddr, SocketAddr},
    os::fd::AsRawFd,
    sync::Arc,
    time::Duration,
};

use crate::protect_socket;

use super::{TcpConnectionKey, TcpConnectionStatus, TransmissionControlBlock};
use anyhow::{anyhow, Result};
use etherparse::{PacketBuilder, TcpHeader};
use log::{debug, error};

use rand::random;
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::tcp::{OwnedReadHalf, OwnedWriteHalf},
    sync::{
        mpsc::{channel, Receiver, Sender},
        Mutex, RwLock,
    },
    task::JoinHandle,
    time::timeout,
};

const IP_PACKET_TTL: u8 = 64;
const WINDOW_SIZE: u16 = 65535;
const CONNECT_TO_DST_TIMEOUT: u64 = 20;

#[derive(Debug, Clone)]
pub(crate) struct TcpConnectionTunHandle {
    tun_input_sender: Sender<(TcpHeader, Vec<u8>)>,
}

impl TcpConnectionTunHandle {
    pub(crate) async fn handle_tun_input(&self, tcp_header: TcpHeader, payload: &[u8]) -> Result<()> {
        self.tun_input_sender.send((tcp_header, payload.to_vec())).await?;
        Ok(())
    }
}

pub(crate) struct TcpConnection {
    connection_key: TcpConnectionKey,
    dst_relay_guard: Option<JoinHandle<()>>,
    dst_write: Option<OwnedWriteHalf>,
    tun_input_receiver: Receiver<(TcpHeader, Vec<u8>)>,
    tun_output_sender: Sender<Vec<u8>>,
    tun_handle: TcpConnectionTunHandle,
    tcb: Arc<RwLock<TransmissionControlBlock>>,
    connection_repository: Arc<Mutex<HashMap<TcpConnectionKey, TcpConnectionTunHandle>>>,
    tun_input_buffer: Vec<u8>,
}

impl Drop for TcpConnection {
    fn drop(&mut self) {
        if let Some(ref dst_relay_guard) = self.dst_relay_guard {
            dst_relay_guard.abort();
        }
        let _ = self.dst_write.take();
        debug!("#### Tcp connection [{}] dropped.", self.connection_key)
    }
}

impl Debug for TcpConnection {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("TcpConnection")
            .field("connection_key", &self.connection_key)
            .field("tcb", &self.tcb)
            .finish()
    }
}

impl TcpConnection {
    pub(crate) fn new(
        connection_key: TcpConnectionKey, tun_output_sender: Sender<Vec<u8>>,
        connection_repository: Arc<Mutex<HashMap<TcpConnectionKey, TcpConnectionTunHandle>>>,
    ) -> Self {
        debug!(">>>> Create new tcp connection [{connection_key}]");
        let (tun_input_sender, tun_input_receiver) = channel(1024);

        let tun_handle = TcpConnectionTunHandle { tun_input_sender };
        Self {
            connection_key,
            dst_relay_guard: None,
            tun_handle,
            tun_input_receiver,
            tun_output_sender,
            tcb: Default::default(),
            dst_write: None,
            connection_repository,
            tun_input_buffer: Vec::new(),
        }
    }

    pub(crate) fn clone_tun_handle(&self) -> TcpConnectionTunHandle {
        self.tun_handle.clone()
    }

    pub(crate) async fn process(&mut self) -> Result<()> {
        if let Err(e) = self.concrete_process().await {
            let tcb = self.tcb.read().await;
            error!(
                "<<<< Tcp connection [{}] fail to process state machine because of error, current tcb: {tcb:?}, error: {e:?}",
                self.connection_key
            );
            Self::send_rst_ack_to_tun(self.connection_key, &tcb, &self.tun_output_sender).await?;
        }
        Ok(())
    }

    async fn concrete_process(&mut self) -> Result<()> {
        loop {
            let (tcp_header, payload) = match self.tun_input_receiver.recv().await {
                Some(value) => value,
                None => {
                    debug!(">>>> Tcp connection [{}] read all tun input.", self.connection_key);
                    break;
                },
            };

            let mut tcb = self.tcb.write().await;
            debug!(
                ">>>> Tcp connection [{}] receive: {tcp_header:?}, payload size: {}, current tcb: {tcb:?}",
                self.connection_key,
                payload.len()
            );
            match tcb.status {
                TcpConnectionStatus::Listen => {
                    Self::on_listen(self.connection_key, &mut tcb, &self.tun_output_sender, tcp_header).await?;
                    continue;
                },
                TcpConnectionStatus::SynReceived => {
                    let (dst_write, dst_relay_guard) =
                        Self::on_syn_received(self.connection_key, &mut tcb, self.tcb.clone(), &self.tun_output_sender, tcp_header).await?;
                    self.dst_relay_guard = Some(dst_relay_guard);
                    self.dst_write = Some(dst_write);
                    continue;
                },
                TcpConnectionStatus::Established => {
                    Self::on_established(
                        self.connection_key,
                        &mut tcb,
                        &self.tun_output_sender,
                        self.dst_write.as_mut().ok_or(anyhow!(
                            ">>>> Tcp connection [{}] can not handle established status because of no destination write.",
                            self.connection_key
                        ))?,
                        tcp_header,
                        payload,
                    )
                    .await?;
                    continue;
                },
                TcpConnectionStatus::Closed => {
                    return Err(anyhow!(
                        "Tcp connection [{}] in Closed status should not handle any tcp packet.",
                        self.connection_key
                    ));
                },
                TcpConnectionStatus::FinWait1 => {
                    Self::on_fin_wait1(self.connection_key, &mut tcb, &self.tun_output_sender, tcp_header).await?;
                    continue;
                },
                TcpConnectionStatus::FinWait2 => {
                    Self::on_fin_wait2(
                        self.connection_key,
                        &mut tcb,
                        self.tcb.clone(),
                        self.connection_repository.clone(),
                        &self.tun_output_sender,
                        tcp_header,
                    )
                    .await?;
                    continue;
                },
                TcpConnectionStatus::CloseWait => {
                    Self::on_close_wait(self.connection_key, &mut tcb, &self.tun_output_sender, tcp_header).await?;
                    continue;
                },
                TcpConnectionStatus::LastAck => {
                    Self::on_last_ack(self.connection_key, &mut tcb, &self.tun_output_sender, &self.connection_repository, tcp_header).await?;
                    continue;
                },
                TcpConnectionStatus::TimeWait => {
                    Self::on_time_wait(self.connection_key, &mut tcb, &self.tun_output_sender, tcp_header).await?;
                    continue;
                },
            }
        }
        Ok(())
    }

    async fn on_listen(
        connection_key: TcpConnectionKey, tcb: &mut TransmissionControlBlock, tun_output_sender: &Sender<Vec<u8>>, tcp_header: TcpHeader,
    ) -> Result<()> {
        if !tcp_header.syn {
            error!(
                ">>>> Tcp connection [{}] fail to process [Listen], expect syn=true, but get: {tcp_header:?}",
                connection_key
            );
            return Err(anyhow!(
                "Tcp connection [{}] fail to process [Listen], expect syn=true, but get: {tcp_header:?}",
                connection_key
            ));
        }

        let iss = random::<u32>();

        tcb.status = TcpConnectionStatus::SynReceived;

        tcb.current_segment_space.seg_seq = iss;
        // Expect next device tcp packet should increase the sequence by 1
        tcb.current_segment_space.seg_ack = tcp_header.sequence_number + 1;
        tcb.current_segment_space.seg_wnd = WINDOW_SIZE;
        // Syn will also count into segment length, but the ack will not count in
        tcb.current_segment_space.seq_len = 1;

        tcb.send_sequence_space.iss = iss;
        tcb.send_sequence_space.snd_nxt = tcb.current_segment_space.seg_seq + tcb.current_segment_space.seq_len;
        tcb.send_sequence_space.snd_una = tcb.current_segment_space.seg_seq;
        tcb.send_sequence_space.snd_wnd = WINDOW_SIZE;

        tcb.receive_sequence_space.irs = tcp_header.sequence_number;
        tcb.receive_sequence_space.rcv_nxt = tcp_header.sequence_number + 1;
        tcb.receive_sequence_space.rcv_wnd = WINDOW_SIZE;

        Self::send_syn_ack_to_tun(connection_key, tcb, tun_output_sender).await?;
        debug!(">>>> Tcp connection [{connection_key}] switch to [SynReceived], current tcb: {tcb:?}",);
        Ok(())
    }

    async fn on_syn_received(
        connection_key: TcpConnectionKey, tcb: &mut TransmissionControlBlock, owned_tbc: Arc<RwLock<TransmissionControlBlock>>,
        tun_output_sender: &Sender<Vec<u8>>, tcp_header: TcpHeader,
    ) -> Result<(OwnedWriteHalf, JoinHandle<()>)> {
        if tcp_header.syn {
            error!(">>>> Tcp connection [{connection_key}] fail to process [SynReceived], expect syn=false, but get: {tcp_header:?}",);
            return Err(anyhow!(
                "Tcp connection [{connection_key}] fail to process [SynReceived], expect syn=false, but get: {tcp_header:?}",
            ));
        }
        if !tcp_header.ack {
            // In SynReceived status, connection should receive a ack.
            error!(">>>> Tcp connection [{connection_key}] fail to process [SynReceived], expect ack=true, but get: {tcp_header:?}",);
            return Err(anyhow!(
                "Tcp connection [{connection_key}] fail to process [SynReceived], expect ack=true, but get: {tcp_header:?}",
            ));
        }
        // Process the connection when the connection in SynReceived status
        if tcp_header.sequence_number != tcb.receive_sequence_space.rcv_nxt {
            error!(
                ">>>> Tcp connection [{connection_key}] fail to process [SynReceived], expect sequence number={}, but get: {tcp_header:?}",
                tcb.receive_sequence_space.rcv_nxt
            );
            return Err(anyhow!(
                "Tcp connection [{connection_key}] fail to process [SynReceived], expect sequence number={}, but get: {tcp_header:?}",
                tcb.receive_sequence_space.rcv_nxt
            ));
        }

        if tcp_header.acknowledgment_number != tcb.send_sequence_space.snd_una + 1 {
            error!(
                ">>>> Tcp connection [{connection_key}] fail to process [SynReceived], expect sequence number={}, but get: {tcp_header:?}",
                tcb.send_sequence_space.snd_una + 1
            );
            return Err(anyhow!(
                "Tcp connection [{connection_key}] fail to process [SynReceived], expect sequence number={}, but get: {tcp_header:?}",
                tcb.send_sequence_space.snd_una + 1,
            ));
        }

        let dst_socket = tokio::net::TcpSocket::new_v4()?;
        let dst_socket_raw_fd = dst_socket.as_raw_fd();
        protect_socket(dst_socket_raw_fd)?;
        let dst_socket_addr = SocketAddr::new(IpAddr::V4(connection_key.dst_addr), connection_key.dst_port);
        let dst_tcp_stream = timeout(Duration::from_secs(CONNECT_TO_DST_TIMEOUT), dst_socket.connect(dst_socket_addr)).await??;

        debug!(">>>> Tcp connection [{}] connect to destination success.", connection_key);
        let (dst_read, dst_write) = dst_tcp_stream.into_split();

        let dst_relay_task_guard = Self::start_dst_relay(connection_key, tun_output_sender.clone(), dst_read, owned_tbc).await;

        tcb.status = TcpConnectionStatus::Established;

        tcb.current_segment_space.seg_seq += 1;

        tcb.send_sequence_space.snd_nxt = tcb.current_segment_space.seg_seq;
        tcb.send_sequence_space.snd_una = tcb.current_segment_space.seg_seq;

        debug!(">>>> Tcp connection [{connection_key}] switch to [Established], current tcb: {tcb:?}",);
        Ok((dst_write, dst_relay_task_guard))
    }

    async fn on_established(
        connection_key: TcpConnectionKey, tcb: &mut TransmissionControlBlock, tun_output_sender: &Sender<Vec<u8>>, dst_write: &mut OwnedWriteHalf,
        tcp_header: TcpHeader, payload: Vec<u8>,
    ) -> Result<()> {
        // Process the connection when the connection in Established status
        if tcb.current_segment_space.seg_seq < tcp_header.acknowledgment_number {
            error!(
                ">>>> Tcp connection [{connection_key}] fail to process [Established], expect sequence number: {}, but get: {tcp_header:?}",
                tcb.current_segment_space.seg_seq
            );

            return Err(anyhow!(
                "Tcp connection [{connection_key}] fail to process [Established], expect sequence number: {}, but get: {tcp_header:?}",
                tcb.current_segment_space.seg_seq
            ));
        }

        if tcb.current_segment_space.seg_ack < tcp_header.sequence_number {
            error!(
                ">>>> Tcp connection [{connection_key}] fail to process [Established], expect acknowledgment number: {}, but get: {tcp_header:?}",
                tcb.current_segment_space.seg_ack
            );

            return Err(anyhow!(
                "Tcp connection [{connection_key}] fail to process [Established], expect acknowledgment number: {}, but get: {tcp_header:?}",
                tcb.current_segment_space.seg_ack
            ));
        }

        if tcp_header.fin {
            tcb.status = TcpConnectionStatus::CloseWait;
            tcb.current_segment_space.seg_ack += 1;
            tcb.receive_sequence_space.rcv_nxt += 1;
            debug!(">>>> Tcp connection [{connection_key}] switch to [CloseWait], current tcb: {tcb:?}",);
            Self::send_ack_to_tun(connection_key, tcb, tun_output_sender, None).await?;

            tcb.status = TcpConnectionStatus::LastAck;
            debug!(">>>> Tcp connection [{connection_key}] switch to [LastAck], current tcb: {tcb:?}",);
            Self::send_fin_ack_to_tun(connection_key, tcb, tun_output_sender).await?;
            return Ok(());
        }

        // Relay from device to destination.
        let relay_data_length = payload.len();
        let relay_data_length: u32 = match relay_data_length.try_into() {
            Ok(relay_data_length) => relay_data_length,
            Err(e) => {
                error!(">>>> Tcp connection [{connection_key}] fail convert tun data length to u32 because of error: {e:?}",);
                return Err(anyhow!(
                    "Tcp connection [{connection_key}] fail convert tun data length to u32 because of error.",
                ));
            },
        };

        if let Err(e) = dst_write.write(&payload).await {
            error!(">>>> Tcp connection [{connection_key}] fail to relay tun data to destination because of error(write): {e:?}",);
            return Err(anyhow!(
                "Tcp connection [{connection_key}] fail to relay tun data to destination because of error(write): {e:?}",
            ));
        };
        if let Err(e) = dst_write.flush().await {
            error!(">>>> Tcp connection [{connection_key}] fail to relay tun data to destination because of error(flush): {e:?}",);
            return Err(anyhow!(
                "Tcp connection [{connection_key}] fail to relay tun data to destination because of error(flush): {e:?}",
            ));
        };

        tcb.current_segment_space.seg_ack += relay_data_length;
        tcb.receive_sequence_space.rcv_nxt = tcb.current_segment_space.seg_ack;

        debug!(">>>> Tcp connection [{connection_key}] keep in [Established], current tcb: {tcb:?}",);
        Self::send_ack_to_tun(connection_key, tcb, tun_output_sender, None).await?;
        Ok(())
    }

    async fn on_fin_wait1(
        connection_key: TcpConnectionKey, tcb: &mut TransmissionControlBlock, tun_output_sender: &Sender<Vec<u8>>, tcp_header: TcpHeader,
    ) -> Result<()> {
        if !tcp_header.ack {
            error!(">>>> Tcp connection [{connection_key}] fail to process [FinWait1], expect ack=true, but get: {tcp_header:?}",);
            return Err(anyhow!(
                "Tcp connection [{connection_key}] fail to process [FinWait1], expect ack=true, but get: {tcp_header:?}",
            ));
        }
        if tcb.current_segment_space.seg_ack != tcp_header.sequence_number {
            error!(
                ">>>> Tcp connection [{connection_key}] fail to process [FinWait1], expect ack=true, sequence number={}, but get: {tcp_header:?}",
                tcb.current_segment_space.seg_ack
            );
            return Err(anyhow!(
                "Tcp connection [{connection_key}] fail to process [FinWait1], expect ack=true, sequence number={}, but get: {tcp_header:?}",
                tcb.current_segment_space.seg_ack,
            ));
        }
        tcb.status = TcpConnectionStatus::FinWait2;
        tcb.current_segment_space.seg_seq += 1;
        tcb.receive_sequence_space.rcv_nxt += 1;

        debug!(">>>> Tcp connection [{connection_key}] switch to [FinWait2], current tcb: {tcb:?}",);
        Self::send_fin_ack_to_tun(connection_key, tcb, tun_output_sender).await?;
        Ok(())
    }

    async fn on_fin_wait2(
        connection_key: TcpConnectionKey, tcb: &mut TransmissionControlBlock, owned_tcb: Arc<RwLock<TransmissionControlBlock>>,
        connection_repository: Arc<Mutex<HashMap<TcpConnectionKey, TcpConnectionTunHandle>>>, tun_output_sender: &Sender<Vec<u8>>, tcp_header: TcpHeader,
    ) -> Result<()> {
        if !tcp_header.fin {
            error!(">>>> Tcp connection [{connection_key}] fail to process [FinWait2], expect fin=true, but get: {tcp_header:?}",);
            return Err(anyhow!(
                "Tcp connection [{connection_key}] fail to process [FinWait2], expect fin=true, but get: {tcp_header:?}",
            ));
        }
        if !tcp_header.ack {
            error!(">>>> Tcp connection [{connection_key}] fail to process [FinWait2], expect ack=true, but get: {tcp_header:?}",);
            return Err(anyhow!(
                "Tcp connection [{connection_key}] fail to process [FinWait2], expect ack=true, but get: {tcp_header:?}",
            ));
        }
        if tcb.current_segment_space.seg_ack != tcp_header.sequence_number {
            error!(
                ">>>> Tcp connection [{connection_key}] fail to process [FinWait2], expect ack=true, sequence number={}, but get: {tcp_header:?}",
                tcb.current_segment_space.seg_ack
            );
            return Err(anyhow!(
                "Tcp connection [{connection_key}] fail to process [FinWait2], expect ack=true, sequence number={}, but get: {tcp_header:?}",
                tcb.current_segment_space.seg_ack,
            ));
        }

        tcb.status = TcpConnectionStatus::TimeWait;

        tokio::spawn(async move {
            debug!(">>>> Tcp connection [{connection_key}] in TimeWait status begin 2ML task.");
            let mut tcb = owned_tcb.write().await;
            debug!(">>>> Tcp connection [{connection_key}] in TimeWait status doing 2ML task, current connection: {tcb:?}",);
            tcb.status = TcpConnectionStatus::Closed;
            let mut connection_repository = connection_repository.lock().await;
            connection_repository.remove(&connection_key);

            debug!(">>>> Tcp connection [{connection_key}] complete 2ML task switch to [Closed], current tcb: {tcb:?}",);
        });

        tcb.current_segment_space.seg_seq += 1;
        tcb.current_segment_space.seg_ack += 1;

        debug!(">>>> Tcp connection [{connection_key}] switch to [TimeWait], current tcb: {tcb:?}",);
        Self::send_ack_to_tun(connection_key, tcb, tun_output_sender, None).await?;
        Ok(())
    }

    async fn on_last_ack(
        connection_key: TcpConnectionKey, tcb: &mut TransmissionControlBlock, tun_output_sender: &Sender<Vec<u8>>,
        connection_repository: &Arc<Mutex<HashMap<TcpConnectionKey, TcpConnectionTunHandle>>>, tcp_header: TcpHeader,
    ) -> Result<()> {
        if !tcp_header.ack && tcb.current_segment_space.seg_ack != tcp_header.sequence_number {
            error!(
                ">>>> Tcp connection [{connection_key}] fail to process [LastAck], expect ack=true, sequence number={}, but get: {tcp_header:?}",
                tcb.current_segment_space.seg_ack
            );
            return Err(anyhow!(
                "Tcp connection [{connection_key}] fail to process [LastAck], expect ack=true, sequence number={}, but get: {tcp_header:?}",
                tcb.current_segment_space.seg_ack,
            ));
        }
        tcb.status = TcpConnectionStatus::Closed;
        let mut connection_repository = connection_repository.lock().await;
        connection_repository.remove(&connection_key);
        debug!(">>>> Tcp connection [{connection_key}] switch to Closed status, remove from the connection repository.");
        Ok(())
    }

    async fn on_time_wait(
        connection_key: TcpConnectionKey, tcb: &mut TransmissionControlBlock, tun_output_sender: &Sender<Vec<u8>>, tcp_header: TcpHeader,
    ) -> Result<()> {
        Self::send_ack_to_tun(connection_key, tcb, tun_output_sender, None).await?;
        debug!(">>>> Tcp connection [{connection_key}] keep in [TimeWait], current tcb: {tcb:?}");
        Ok(())
    }

    async fn on_close_wait(
        connection_key: TcpConnectionKey, tcb: &mut TransmissionControlBlock, tun_output_sender: &Sender<Vec<u8>>, tcp_header: TcpHeader,
    ) -> Result<()> {
        Self::send_ack_to_tun(connection_key, tcb, tun_output_sender, None).await?;
        tcb.status = TcpConnectionStatus::LastAck;
        debug!(">>>> Tcp connection [{connection_key}] switch to [LastAck], current tcb: {tcb:?}");
        Ok(())
    }

    async fn start_dst_relay(
        connection_key: TcpConnectionKey, tun_output_sender: Sender<Vec<u8>>, mut dst_read: OwnedReadHalf, owned_tcb: Arc<RwLock<TransmissionControlBlock>>,
    ) -> JoinHandle<()> {
        tokio::spawn(async move {
            loop {
                let mut dst_read_buf = [0u8; 65535];
                let dst_read_buf = match dst_read.read(&mut dst_read_buf).await {
                    Ok(0) => {
                        let mut tcb = owned_tcb.write().await;
                        debug!("<<<< Tcp connection [{connection_key}] read destination data complete send fin to tun, current connection:{tcb:?}");
                        // Close the connection activally when read destination complete
                        if let Err(e) = Self::send_fin_ack_to_tun(connection_key, &tcb, &tun_output_sender).await {
                            error!("<<<< Tcp connection [{connection_key}] fail to send fin ack packet to tun because of error: {e:?}");
                            break;
                        };
                        tcb.status = TcpConnectionStatus::FinWait1;
                        debug!("<<<< Tcp connection [{connection_key}] read destination data complete, switch to FinWait1 status.");
                        break;
                    },
                    Ok(size) => &dst_read_buf[0..size],
                    Err(e) => {
                        error!("<<<< Tcp connection [{connection_key}] fail to read destination data because of error: {e:?}");
                        break;
                    },
                };
                let mut tcb = owned_tcb.write().await;
                let destination_read_data_size: u32 = match dst_read_buf.len().try_into() {
                    Ok(size) => size,
                    Err(e) => {
                        error!("<<<< Tcp connection [{connection_key}] fail to convert destination read data size because of error: {e:?}");

                        break;
                    },
                };
                tcb.current_segment_space.seg_seq += destination_read_data_size;
                tcb.send_sequence_space.snd_nxt += destination_read_data_size;
                tcb.current_segment_space.seq_len = destination_read_data_size;

                let ip_packet = PacketBuilder::ipv4(connection_key.dst_addr.octets(), connection_key.src_addr.octets(), IP_PACKET_TTL)
                    .tcp(
                        connection_key.dst_port,
                        connection_key.src_port,
                        tcb.current_segment_space.seg_seq,
                        tcb.current_segment_space.seg_wnd,
                    )
                    .ack(tcb.current_segment_space.seg_ack);
                let mut ip_packet_bytes = Vec::with_capacity(ip_packet.size(dst_read_buf.len()));
                if let Err(e) = ip_packet.write(&mut ip_packet_bytes, dst_read_buf) {
                    error!("<<<< Tcp connection [{connection_key}] fail to generate ip packet write to tun device because of error: {e:?}");
                    continue;
                };
                if let Err(e) = tun_output_sender.send(ip_packet_bytes).await {
                    error!("<<<< Tcp connection [{connection_key}] fail to generate ip packet write to tun device because of error: {e:?}");
                    continue;
                };
                debug!("<<<< Tcp connection [{connection_key}] send ack to device");
            }
        })
    }

    async fn send_ack_to_tun(
        connection_key: TcpConnectionKey, tcb: &TransmissionControlBlock, tun_output_sender: &Sender<Vec<u8>>, payload: Option<&[u8]>,
    ) -> Result<()> {
        let ip_packet = PacketBuilder::ipv4(connection_key.dst_addr.octets(), connection_key.src_addr.octets(), IP_PACKET_TTL)
            .tcp(
                connection_key.dst_port,
                connection_key.src_port,
                tcb.current_segment_space.seg_seq,
                tcb.current_segment_space.seg_wnd,
            )
            .ack(tcb.current_segment_space.seg_ack);
        let mut ip_packet_bytes = if let Some(payload) = payload {
            Vec::with_capacity(ip_packet.size(payload.len()))
        } else {
            Vec::with_capacity(ip_packet.size(0))
        };

        let payload = if let Some(payload) = payload {
            payload
        } else {
            &[0u8; 0]
        };
        ip_packet.write(&mut ip_packet_bytes, payload)?;
        tun_output_sender.send(ip_packet_bytes).await?;
        debug!("<<<< Tcp connection [{connection_key}] send ack to device, current tcp connection:{tcb:?}",);
        Ok(())
    }

    async fn send_fin_ack_to_tun(connection_key: TcpConnectionKey, tcb: &TransmissionControlBlock, tun_output_sender: &Sender<Vec<u8>>) -> Result<()> {
        let ip_packet = PacketBuilder::ipv4(connection_key.dst_addr.octets(), connection_key.src_addr.octets(), IP_PACKET_TTL)
            .tcp(
                connection_key.dst_port,
                connection_key.src_port,
                tcb.current_segment_space.seg_seq,
                tcb.current_segment_space.seg_wnd,
            )
            .fin()
            .ack(tcb.current_segment_space.seg_ack);
        let mut ip_packet_bytes = Vec::with_capacity(ip_packet.size(0));
        ip_packet.write(&mut ip_packet_bytes, &[0u8; 0])?;
        tun_output_sender.send(ip_packet_bytes).await?;
        debug!("<<<< Tcp connection [{connection_key}] send ack to device, current tcp connection:{tcb:?}",);
        Ok(())
    }

    async fn send_syn_ack_to_tun(connection_key: TcpConnectionKey, tcb: &TransmissionControlBlock, tun_output_sender: &Sender<Vec<u8>>) -> Result<()> {
        let ip_packet = PacketBuilder::ipv4(connection_key.dst_addr.octets(), connection_key.src_addr.octets(), IP_PACKET_TTL)
            .tcp(
                connection_key.dst_port,
                connection_key.src_port,
                tcb.current_segment_space.seg_seq,
                tcb.current_segment_space.seg_wnd,
            )
            .syn()
            .ack(tcb.current_segment_space.seg_ack);

        let mut ip_packet_bytes = Vec::with_capacity(ip_packet.size(0));
        ip_packet.write(&mut ip_packet_bytes, &[0u8; 0])?;
        tun_output_sender.send(ip_packet_bytes).await?;
        debug!("<<<< Tcp connection [{connection_key}] send syn ack to device, current tcp connection:{tcb:?}",);
        Ok(())
    }

    async fn send_rst_ack_to_tun(connection_key: TcpConnectionKey, tcb: &TransmissionControlBlock, tun_output_sender: &Sender<Vec<u8>>) -> Result<()> {
        let ip_packet = PacketBuilder::ipv4(connection_key.dst_addr.octets(), connection_key.src_addr.octets(), IP_PACKET_TTL)
            .tcp(
                connection_key.dst_port,
                connection_key.src_port,
                tcb.current_segment_space.seg_seq,
                tcb.current_segment_space.seg_wnd,
            )
            .rst()
            .ack(tcb.current_segment_space.seg_ack);

        let mut ip_packet_bytes = Vec::with_capacity(ip_packet.size(0));
        ip_packet.write(&mut ip_packet_bytes, &[0u8; 0])?;
        tun_output_sender.send(ip_packet_bytes).await?;
        debug!("<<<< Tcp connection [{connection_key}] send rst ack to device, current tcp connection:{tcb:?}",);
        Ok(())
    }
}
