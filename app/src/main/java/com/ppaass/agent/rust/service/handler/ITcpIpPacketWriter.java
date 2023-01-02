package com.ppaass.agent.rust.service.handler;

import com.ppaass.agent.rust.service.handler.tcp.TcpConnection;

public interface ITcpIpPacketWriter {
    void writeSyncAckToDevice(TcpConnection connection, long sequenceNumber, long acknowledgementNumber,
                              byte[] senderTimestamp);

    void writeAckToDevice(byte[] ackData, TcpConnection connection, long sequenceNumber,
                          long acknowledgementNumber, byte[] senderTimestamp);

    void writeRstAckToDevice(TcpConnection connection, long sequenceNumber,
                             long acknowledgementNumber);

    void writeRstToDevice(TcpConnection connection, long sequenceNumber,
                          long acknowledgementNumber);

    void writeFinAckToDevice(byte[] data, TcpConnection connection, long sequenceNumber,
                             long acknowledgementNumber);

    void writeFinToDevice(TcpConnection connection, long sequenceNumber,
                          long acknowledgementNumber);
}
