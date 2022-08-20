package com.ppaass.agent.service.handler;

import com.ppaass.agent.service.handler.tcp.TcpConnection;

public interface ITcpIpPacketWriter {
    void writeSyncAckToDevice(TcpConnection connection, long sequenceNumber, long acknowledgementNumber);

    void writeAckToDevice(byte[] ackData, TcpConnection connection, long sequenceNumber,
                          long acknowledgementNumber);

    void writeRstAckToDevice(TcpConnection connection, long sequenceNumber,
                             long acknowledgementNumber);

    void writeRstToDevice(TcpConnection connection, long sequenceNumber,
                          long acknowledgementNumber);

    void writeFinAckToDevice(TcpConnection connection, long sequenceNumber,
                             long acknowledgementNumber);

    void writeFinToDevice(TcpConnection connection, long sequenceNumber,
                          long acknowledgementNumber);
}
