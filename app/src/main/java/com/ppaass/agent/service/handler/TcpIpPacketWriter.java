package com.ppaass.agent.service.handler;

import com.ppaass.agent.protocol.general.tcp.TcpPacket;
import com.ppaass.agent.service.handler.tcp.TcpConnection;
import com.ppaass.agent.service.handler.tcp.TcpConnectionRepositoryKey;

import java.io.IOException;

public interface TcpIpPacketWriter {
    void write(TcpConnection tcpConnection, TcpPacket tcpPacket) throws IOException;
}
