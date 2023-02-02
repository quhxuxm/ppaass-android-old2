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
#[derive(Debug, Default)]
struct SendSequenceSpace {
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
#[derive(Debug, Default)]
struct ReceiveSequenceSpace {
    /// The receiver of data keeps track of the next
    /// sequence number to expect in the variable RCV.NXT.
    ///
    /// The next sequence number expected on an incoming segments, and
    /// is the left or lower edge of the receive window
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
/// * SEG.SEQ - segment sequence number
/// * SEG.ACK - segment acknowledgment number
/// * SEG.LEN - segment length
/// * SEG.WND - segment window
/// * SEG.UP  - segment urgent pointer
/// * SEG.PRC - segment precedence value
#[derive(Debug, Default)]
struct CurrentSegmentSpace {
    /// first sequence number of a segment
    pub seg_seq: u32,
    /// acknowledgment from the receiving TCP
    /// (next sequence number expected by the receiving TCP)
    pub seg_ack: u32,
    /// segment window field
    pub seg_wnd: u16,
    /// segment urgent pointer field
    pub seg_up: bool,
    /// segment precedence value
    pub seg_prc: bool,
    /// the number of octets occupied by the data in
    /// the segment (counting SYN and FIN)
    pub seq_len: u32,
}

#[derive(Debug, Default)]
struct TcpConnectionControlBlock {
    pub send_sequence_space: SendSequenceSpace,
    pub receive_sequence_space: ReceiveSequenceSpace,
    pub current_segment_space: CurrentSegmentSpace,
    pub status: TcpConnectionStatus,
}
