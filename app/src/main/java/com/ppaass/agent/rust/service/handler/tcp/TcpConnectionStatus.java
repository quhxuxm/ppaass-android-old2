package com.ppaass.agent.rust.service.handler.tcp;

public enum TcpConnectionStatus {
    CLOSED,
    LISTEN,
    SYNC_RCVD,
    ESTABLISHED,
    LAST_ACK,
    CLOSE_WAIT,
    CLOSING,
    FIN_WAIT1,
    FIN_WAIT2,
    TIME_WAIT

}
