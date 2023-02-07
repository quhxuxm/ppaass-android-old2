use std::{
    collections::VecDeque,
    fmt::{Debug, Formatter},
};

use log::debug;
use smoltcp::{
    phy::{Device, DeviceCapabilities, Medium, RxToken, TxToken},
    time::Instant,
};

use uuid::Uuid;

use crate::IP_MTU;

pub(crate) struct PpaassVpnRxToken {
    id: String,
    raw_data: Vec<u8>,
}

impl PpaassVpnRxToken {
    fn new(raw_data: Vec<u8>) -> Self {
        let id = format!(">==> RX[{}]", Uuid::new_v4().to_string().replace("-", ""));
        Self { id, raw_data }
    }
}

impl Debug for PpaassVpnRxToken {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.id)
    }
}

impl RxToken for PpaassVpnRxToken {
    fn consume<R, F>(mut self, timestamp: Instant, f: F) -> smoltcp::Result<R>
    where
        F: FnOnce(&mut [u8]) -> smoltcp::Result<R>,
    {
        let result = f(&mut self.raw_data);
        debug!(
            ">>>> Ppaass vpn RX token [{}] receive data:\n{}",
            self.id,
            pretty_hex::pretty_hex(&self.raw_data)
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
        let id = format!("<==< TX[{}]", Uuid::new_v4().to_string().replace("-", ""));
        Self { id, raw_data_queue }
    }
}

impl<'a> TxToken for PpaassVpnTxToken<'a> {
    fn consume<R, F>(self, timestamp: Instant, len: usize, f: F) -> smoltcp::Result<R>
    where
        F: FnOnce(&mut [u8]) -> smoltcp::Result<R>,
    {
        let mut raw_data = Vec::new();
        let result = f(&mut raw_data);
        debug!("<<<< Ppaass vpn TX token [{}] transmit data:\n{}", self.id, pretty_hex::pretty_hex(&raw_data));
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

impl<'a> Device<'a> for PpaassVpnDevice {
    type RxToken = PpaassVpnRxToken;

    type TxToken = PpaassVpnTxToken<'a>;

    fn receive(&'a mut self) -> Option<(Self::RxToken, Self::TxToken)> {
        match self.rx_queue.pop_front() {
            None => None,
            Some(raw_data) => {
                let rx_token = PpaassVpnRxToken::new(raw_data);
                let tx_token = PpaassVpnTxToken::new(&mut self.tx_queue);
                debug!(">>>> Ppaass vpn device create RX token: {rx_token:?} and TX token: {tx_token:?}",);
                Some((rx_token, tx_token))
            },
        }
    }

    fn transmit(&'a mut self) -> Option<Self::TxToken> {
        let tx_token = PpaassVpnTxToken::new(&mut self.tx_queue);
        debug!("<<<< Ppaass vpn device create TX token: {tx_token:?}",);
        Some(tx_token)
    }

    fn capabilities(&self) -> DeviceCapabilities {
        let mut result = DeviceCapabilities::default();
        result.medium = Medium::Ip;
        result.max_transmission_unit = IP_MTU;
        result
    }
}
