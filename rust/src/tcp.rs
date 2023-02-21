use std::{
    fmt::{Debug, Display},
    net::Ipv4Addr,
};

mod connection;
pub(crate) use connection::VpnTcpConnection;

#[derive(Clone, Copy, PartialEq, Eq, Hash)]
pub(crate) struct VpnTcpConnectionKey {
    pub src_addr: Ipv4Addr,
    pub dst_addr: Ipv4Addr,
    pub src_port: u16,
    pub dst_port: u16,
}

impl VpnTcpConnectionKey {
    pub(crate) fn new(
        src_addr: Ipv4Addr,
        src_port: u16,
        dst_addr: Ipv4Addr,
        dst_port: u16,
    ) -> Self {
        Self {
            src_addr,
            dst_addr,
            src_port,
            dst_port,
        }
    }
}

impl Debug for VpnTcpConnectionKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "[{}:{}->{}:{}]",
            self.src_addr, self.src_port, self.dst_addr, self.dst_port
        )
    }
}

impl Display for VpnTcpConnectionKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{self:?}")
    }
}
