use std::{
    fmt::{Debug, Display, Formatter},
    net::Ipv4Addr,
};

mod connection;

pub(crate) use connection::TcpConnection;
pub(crate) use connection::TcpConnectionTunHandle;

#[derive(Clone, Copy, PartialEq, Eq, Hash)]
pub(crate) struct TcpConnectionKey {
    src_addr: Ipv4Addr,
    dst_addr: Ipv4Addr,
    src_port: u16,
    dst_port: u16,
}

impl TcpConnectionKey {
    pub(crate) fn new(src_addr: Ipv4Addr, src_port: u16, dst_addr: Ipv4Addr, dst_port: u16) -> Self {
        Self {
            src_addr,
            dst_addr,
            src_port,
            dst_port,
        }
    }
}

impl Debug for TcpConnectionKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "[{}:{}->{}:{}]", self.src_addr, self.src_port, self.dst_addr, self.dst_port)
    }
}

impl Display for TcpConnectionKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{self:?}")
    }
}

#[derive(Debug, PartialEq, Eq, Default, Clone, Copy)]
pub(crate) enum TcpConnectionStatus {
    #[default]
    Listen,
    Closed,

    SynReceived,
    Established,
    FinWait1,
    FinWait2,

    CloseWait,
    LastAck,
    TimeWait,
}

impl Display for TcpConnectionStatus {
    fn fmt(&self, f: &mut Formatter) -> std::fmt::Result {
        match *self {
            TcpConnectionStatus::Closed => write!(f, "CLOSED"),
            TcpConnectionStatus::Listen => write!(f, "LISTEN"),
            TcpConnectionStatus::SynReceived => write!(f, "SYN-RECEIVED"),
            TcpConnectionStatus::Established => write!(f, "ESTABLISHED"),
            TcpConnectionStatus::FinWait1 => write!(f, "FIN-WAIT-1"),
            TcpConnectionStatus::FinWait2 => write!(f, "FIN-WAIT-2"),
            TcpConnectionStatus::CloseWait => write!(f, "CLOSE-WAIT"),
            TcpConnectionStatus::LastAck => write!(f, "LAST-ACK"),
            TcpConnectionStatus::TimeWait => write!(f, "TIME-WAIT"),
        }
    }
}

#[derive(Debug, Default)]
struct TransmissionControlBlock {
    pub sequence_number: u32,
    pub acknowledgment_number: u32,
    pub status: TcpConnectionStatus,
}
