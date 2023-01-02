package com.ppaass.agent.rust.protocol.general.udp;

import com.ppaass.agent.rust.protocol.general.IProtocolConst;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class UdpPacketReader {
    public static final UdpPacketReader INSTANCE = new UdpPacketReader();

    private UdpPacketReader() {
    }

    public UdpPacket parse(byte[] input) {
        ByteBuf byteBuf = Unpooled.wrappedBuffer(input);
        UdpPacketBuilder udpPacketBuilder = new UdpPacketBuilder();
        udpPacketBuilder.sourcePort(byteBuf.readShort() & 0xFFFF);
        udpPacketBuilder.destinationPort(byteBuf.readShort() & 0xFFFF);
        int totalLength = byteBuf.readShort() & 0xFFFF;
        int checksum = byteBuf.readShort() & 0xFFFF;
        udpPacketBuilder.checksum(checksum);
        byte[] data = new byte[totalLength - IProtocolConst.MIN_UDP_HEADER_LENGTH];
        byteBuf.readBytes(data);
        udpPacketBuilder.data(data);
        UdpPacket result = udpPacketBuilder.build();
        if (result.getHeader().getTotalLength() != totalLength) {
            throw new IllegalStateException("The total length in the input data do not match.");
        }
        return result;
    }
}
