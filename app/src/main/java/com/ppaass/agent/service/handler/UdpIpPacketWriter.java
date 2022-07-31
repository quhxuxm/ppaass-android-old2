package com.ppaass.agent.service.handler;

import com.ppaass.agent.protocol.general.tcp.TcpPacket;
import com.ppaass.agent.protocol.general.udp.UdpPacket;
import com.ppaass.agent.service.handler.tcp.TcpConnection;

import java.io.IOException;

public interface UdpIpPacketWriter {
    void write(UdpPacket udpPacket) throws IOException;
}
