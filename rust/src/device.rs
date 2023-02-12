use std::{
    collections::VecDeque,
    fmt::{Debug, Formatter},
};

use log::{debug, trace};
use smoltcp::{
    phy::{Device, DeviceCapabilities, Medium, RxToken, TxToken},
    time::Instant,
    wire::Ipv4Packet,
};

use uuid::Uuid;

use crate::{util::print_packet_bytes, IP_MTU};

pub(crate) struct PpaassVpnRxToken {
    id: String,
    raw_data: Vec<u8>,
}

impl PpaassVpnRxToken {
    fn new(raw_data: Vec<u8>) -> Self {
        let id = format!("R-{}", Uuid::new_v4().to_string().replace('-', ""));
        Self { id, raw_data }
    }
}

impl Debug for PpaassVpnRxToken {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.id)
    }
}

impl RxToken for PpaassVpnRxToken {
    fn consume<R, F>(mut self, f: F) -> R
    where
        F: FnOnce(&mut [u8]) -> R,
    {
        let result = f(&mut self.raw_data);
        trace!(
            ">>>> Ppaass vpn RX token [{}] receive data:\n{}",
            self.id,
            print_packet_bytes::<Ipv4Packet<&'static [u8]>>(&self.raw_data)
        );
        result
    }
}

pub(crate) struct PpaassVpnTxToken<'a> {
    id: String,
    raw_data_queue: &'a mut VecDeque<Vec<u8>>,
}

impl<'a> Debug for PpaassVpnTxToken<'a> {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.id)
    }
}

impl<'a> PpaassVpnTxToken<'a> {
    fn new(raw_data_queue: &'a mut VecDeque<Vec<u8>>) -> Self {
        let id = format!("T-{}", Uuid::new_v4().to_string().replace('-', ""));
        Self { id, raw_data_queue }
    }
}

impl<'a> TxToken for PpaassVpnTxToken<'a> {
    fn consume<R, F>(self, len: usize, f: F) -> R
    where
        F: FnOnce(&mut [u8]) -> R,
    {
        let mut raw_data = vec![0; len];
        let result = f(&mut raw_data);
        trace!(
            "<<<< Ppaass vpn TX token [{}] transmit data:\n{}",
            self.id,
            print_packet_bytes::<Ipv4Packet<&'static [u8]>>(&raw_data)
        );
        self.raw_data_queue.push_back(raw_data);
        result
    }
}

pub(crate) struct PpaassVpnDevice {
    rx_queue: VecDeque<Vec<u8>>,
    tx_queue: VecDeque<Vec<u8>>,
}

impl PpaassVpnDevice {
    pub fn new() -> Self {
        let rx_queue = VecDeque::new();
        let tx_queue = VecDeque::new();
        Self { rx_queue, tx_queue }
    }

    pub(crate) fn push_rx(&mut self, raw_packet: Vec<u8>) {
        self.rx_queue.push_back(raw_packet)
    }

    pub(crate) fn pop_tx(&mut self) -> Option<Vec<u8>> {
        self.tx_queue.pop_front()
    }
}

impl Device for PpaassVpnDevice {
    type RxToken<'a> = PpaassVpnRxToken;

    type TxToken<'a> = PpaassVpnTxToken<'a>;

    fn capabilities(&self) -> DeviceCapabilities {
        let mut result = DeviceCapabilities::default();
        result.medium = Medium::Ip;
        result.max_transmission_unit = IP_MTU;

        result
    }

    fn receive(&mut self, timestamp: Instant) -> Option<(Self::RxToken<'_>, Self::TxToken<'_>)> {
        match self.rx_queue.pop_front() {
            None => None,
            Some(raw_data) => {
                let rx_token = PpaassVpnRxToken::new(raw_data);
                let tx_token = PpaassVpnTxToken::new(&mut self.tx_queue);
                trace!(">>>> Ppaass vpn device create RX token: [{rx_token:?}] and TX token: [{tx_token:?}]",);
                Some((rx_token, tx_token))
            },
        }
    }

    fn transmit(&mut self, timestamp: Instant) -> Option<Self::TxToken<'_>> {
        let tx_token = PpaassVpnTxToken::new(&mut self.tx_queue);
        trace!("<<<< Ppaass vpn device create TX token: {tx_token:?}",);
        Some(tx_token)
    }
}
