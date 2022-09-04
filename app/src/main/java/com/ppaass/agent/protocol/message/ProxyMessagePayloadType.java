package com.ppaass.agent.protocol.message;

public enum ProxyMessagePayloadType {
    TcpConnectSuccess,
    TcpConnectFail,
    TcpDataSuccess,
    UdpAssociateSuccess,
    UdpAssociateFail,
    UdpDataSuccess,
    UdpDataRelayFail,
    DomainResolveSuccess,
    DomainResolveFail,
    HeartbeatSuccess,
}
