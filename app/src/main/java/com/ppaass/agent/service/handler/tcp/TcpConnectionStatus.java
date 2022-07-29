package com.ppaass.agent.service.handler.tcp;

public enum TcpConnectionStatus {
    CLOSED,
    LISTEN,
    SYNC_RCVD,
    ESTABLISHED,
    CLOSE_WAIT,
    LAST_ACK,
    FIN_WAIT1,
    FIN_WAIT2,
    CLOSING,
    TIME_WAIT
}
