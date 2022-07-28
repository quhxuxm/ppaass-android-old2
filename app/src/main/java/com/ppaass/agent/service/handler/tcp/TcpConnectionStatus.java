package com.ppaass.agent.service.handler.tcp;

public enum TcpConnectionStatus {
    CLOSED,
    LISTEN,
    SYNC_RCVD,
    ESTABLISHED,
    CLOSE_WAIT,
    LAST_ACK
}
