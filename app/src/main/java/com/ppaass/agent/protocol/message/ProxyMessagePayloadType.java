package com.ppaass.agent.protocol.message;

public enum ProxyMessagePayloadType {
    TcpConnectSuccess,
    TcpConnectFail,
    TcpData,
    UdpAssociateSuccess,
    UdpAssociateFail,
    UdpData,
    UdpDataRelayFail,
    HeartbeatSuccess,
}
