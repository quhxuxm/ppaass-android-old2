package com.ppaass.agent.service.handler.tcp;

public enum TcpConnectionStatus {
    CLOSED,
    LISTEN,
    SYNC_RCVD,
    ESTABLISHED,
    LAST_ACK,
    CLOSE_WAIT,
}
