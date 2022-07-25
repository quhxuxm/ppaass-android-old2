package com.ppaass.agent.protocol.general.udp;

import com.ppaass.agent.protocol.general.IProtocolConst;

import java.nio.ByteBuffer;

public class UdpPacketReader {
    public static final UdpPacketReader INSTANCE = new UdpPacketReader();

    private UdpPacketReader() {
    }

    public UdpPacket parse(byte[] input) {
        ByteBuffer byteBuffer = ByteBuffer.wrap(input);
        UdpPacketBuilder udpPacketBuilder = new UdpPacketBuilder();
        udpPacketBuilder.sourcePort(byteBuffer.getShort() & 0xFFFF);
        udpPacketBuilder.destinationPort(byteBuffer.getShort() & 0xFFFF);
        int totalLength = byteBuffer.getShort() & 0xFFFF;
        int checksum = byteBuffer.getShort() & 0xFFFF;
        udpPacketBuilder.checksum(checksum);
        byte[] data = new byte[input.length - IProtocolConst.MIN_UDP_HEADER_LENGTH];
        byteBuffer.get(data);
        udpPacketBuilder.data(data);
        UdpPacket result = udpPacketBuilder.build();
        if (result.getHeader().getTotalLength() != totalLength) {
            throw new IllegalStateException("The total length in the input data do not match.");
        }
        byteBuffer.clear();
        return result;
    }
}
