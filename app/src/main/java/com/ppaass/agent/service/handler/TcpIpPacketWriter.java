package com.ppaass.agent.service.handler;

import com.ppaass.agent.protocol.general.tcp.TcpPacket;
import com.ppaass.agent.service.handler.tcp.TcpConnectionRepositoryKey;

import java.io.IOException;

public interface TcpIpPacketWriter {
    void write(TcpConnectionRepositoryKey repositoryKey, TcpPacket tcpPacket) throws IOException;
}
