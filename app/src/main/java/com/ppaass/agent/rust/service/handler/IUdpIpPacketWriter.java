package com.ppaass.agent.rust.service.handler;

import com.ppaass.agent.rust.protocol.general.udp.UdpPacket;

import java.io.IOException;

public interface IUdpIpPacketWriter {
    void writeToDevice(short udpIpPacketId, UdpPacket udpPacket, byte[] sourceHost, byte[] targetHost, int udpResponseDataOffset) throws IOException;
}
