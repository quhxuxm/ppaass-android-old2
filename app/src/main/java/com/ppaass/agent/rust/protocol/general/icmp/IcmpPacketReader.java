package com.ppaass.agent.rust.protocol.general.icmp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class IcmpPacketReader {
    public static final IcmpPacketReader INSTANCE = new IcmpPacketReader();

    private IcmpPacketReader() {
    }

    public IcmpPacket<?> parse(byte[] input) {
        ByteBuf byteBuffer = Unpooled.wrappedBuffer(input);
        byte type = byteBuffer.readByte();
        IcmpQueryType queryType = IcmpQueryType.parse(type);
        if (queryType != null) {
            IcmpQueryPacket icmpPacket = new IcmpQueryPacket(queryType);
            icmpPacket.setCode(byteBuffer.readByte());
            icmpPacket.setChecksum(byteBuffer.readShort());
            icmpPacket.setUnused(byteBuffer.readInt());
            byte[] data = new byte[byteBuffer.readableBytes()];
            byteBuffer.readBytes(data);
            icmpPacket.setData(data);
            return icmpPacket;
        }
        IcmpErrorType errorType = IcmpErrorType.parse(type);
        IcmpErrorPacket icmpPacket = new IcmpErrorPacket(errorType);
        icmpPacket.setCode(byteBuffer.readByte());
        icmpPacket.setChecksum(byteBuffer.readShort());
        icmpPacket.setUnused(byteBuffer.readInt());
        byte[] data = new byte[byteBuffer.readableBytes()];
        byteBuffer.readBytes(data);
        icmpPacket.setData(data);
        return icmpPacket;
    }
}
