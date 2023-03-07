use std::{
    collections::VecDeque,
    fmt::{Debug, Formatter},
};

use log::trace;
use smoltcp::{
    phy::{Device, DeviceCapabilities, Medium, RxToken, TxToken},
    time::Instant,
    wire::Ipv4Packet,
};

use uuid::Uuid;

use crate::{util::print_packet_bytes, IP_MTU};

pub(crate) struct VirtualRxToken {
    id: String,
    raw_data: Vec<u8>,
}

impl VirtualRxToken {
    fn new(raw_data: Vec<u8>) -> Self {
        let id = format!("R-{}", Uuid::new_v4().to_string().replace('-', ""));
        Self { id, raw_data }
    }
}

impl Debug for VirtualRxToken {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.id)
    }
}

impl RxToken for VirtualRxToken {
    fn consume<R, F>(mut self, f: F) -> R
    where
        F: FnOnce(&mut [u8]) -> R,
    {
        let result = f(&mut self.raw_data);
        trace!(
            ">>>> Inner RX token [{}] receive data:\n{}",
            self.id,
            print_packet_bytes::<Ipv4Packet<&'static [u8]>>(&self.raw_data)
        );
        result
    }
}

pub(crate) struct VirtualTxToken<'a> {
    id: String,
    tx_queue: &'a mut VecDeque<Vec<u8>>,
}

impl<'a> Debug for VirtualTxToken<'a> {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.id)
    }
}

impl<'a> VirtualTxToken<'a> {
    fn new(tx_queue: &'a mut VecDeque<Vec<u8>>) -> Self {
        let id = format!("T-{}", Uuid::new_v4().to_string().replace('-', ""));
        Self { id, tx_queue }
    }
}

impl<'a> TxToken for VirtualTxToken<'a> {
    fn consume<R, F>(self, len: usize, f: F) -> R
    where
        F: FnOnce(&mut [u8]) -> R,
    {
        let mut raw_data = vec![0; len];
        let result = f(&mut raw_data);
        trace!(
            "<<<< Inner TX token [{}] transmit data:\n{}",
            self.id,
            print_packet_bytes::<Ipv4Packet<&'static [u8]>>(&raw_data)
        );
        self.tx_queue.push_back(raw_data);
        result
    }
}

pub(crate) struct VirtualDevice {
    rx_queue: VecDeque<Vec<u8>>,
    tx_queue: VecDeque<Vec<u8>>,
}

impl VirtualDevice {
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

impl Device for VirtualDevice {
    type RxToken<'a> = VirtualRxToken;

    type TxToken<'a> = VirtualTxToken<'a>;

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
                let rx_token = VirtualRxToken::new(raw_data);
                let tx_token = VirtualTxToken::new(&mut self.tx_queue);
                trace!(">>>> Inner device create RX token: [{rx_token:?}] and TX token: [{tx_token:?}]",);
                Some((rx_token, tx_token))
            }
        }
    }

    fn transmit(&mut self, timestamp: Instant) -> Option<Self::TxToken<'_>> {
        let tx_token = VirtualTxToken::new(&mut self.tx_queue);
        trace!("<<<< Inner device create TX token: {tx_token:?}",);
        Some(tx_token)
    }
}
