use std::collections::VecDeque;

use smoltcp::{
    phy::{Device, DeviceCapabilities, Medium, RxToken, TxToken},
    time::Instant,
};

use crate::IP_MTU;

struct PpaassVpnRxToken {
    rx_buffer: Vec<u8>,
}

impl PpaassVpnRxToken {
    fn new() -> Self {
        Self { rx_buffer: Vec::new() }
    }
}

impl RxToken for PpaassVpnRxToken {
    fn consume<R, F>(mut self, timestamp: Instant, f: F) -> smoltcp::Result<R>
    where
        F: FnOnce(&mut [u8]) -> smoltcp::Result<R>,
    {
        f(&mut self.rx_buffer)
    }
}

struct PpaassVpnTxToken {
    tx_buffer: Vec<u8>,
}

impl PpaassVpnTxToken {
    fn new() -> Self {
        Self { tx_buffer: Vec::new() }
    }
}

impl TxToken for PpaassVpnTxToken {
    fn consume<R, F>(self, timestamp: Instant, len: usize, f: F) -> smoltcp::Result<R>
    where
        F: FnOnce(&mut [u8]) -> smoltcp::Result<R>,
    {
        todo!()
    }
}

pub(crate) struct PpaassVpnDevice {
    rx_queue: VecDeque<Vec<u8>>,
    tx_queue: VecDeque<Vec<u8>>,
}

impl PpaassVpnDevice {
    pub fn new() -> Self {
        Self {
            rx_queue: VecDeque::new(),
            tx_queue: VecDeque::new(),
        }
    }
}

impl<'a> Device<'a> for PpaassVpnDevice {
    type RxToken = PpaassVpnRxToken;

    type TxToken = PpaassVpnTxToken;

    fn receive(&'a mut self) -> Option<(Self::RxToken, Self::TxToken)> {
        todo!()
    }

    fn transmit(&'a mut self) -> Option<Self::TxToken> {
        todo!()
    }

    fn capabilities(&self) -> DeviceCapabilities {
        let result = DeviceCapabilities::default();
        result.medium = Medium::Ip;
        result.max_transmission_unit = IP_MTU;
        result
    }
}
