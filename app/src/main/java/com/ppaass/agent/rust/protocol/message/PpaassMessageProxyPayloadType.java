package com.ppaass.agent.rust.protocol.message;

public enum PpaassMessageProxyPayloadType {
    TcpLoopInit,
    UdpLoopInit,
    DomainNameResolve,
    IdleHeartbeat,
}
