package com.ppaass.agent.protocol.general.icmp;

import java.nio.ByteBuffer;

public class IcmpPacketReader {
    public static final IcmpPacketReader INSTANCE = new IcmpPacketReader();

    private IcmpPacketReader() {
    }

    public IcmpPacket<?> parse(byte[] input) {
        ByteBuffer byteBuffer = ByteBuffer.wrap(input);
        byte type = byteBuffer.get();
        IcmpQueryType queryType = IcmpQueryType.parse(type);
        if (queryType != null) {
            IcmpQueryPacket icmpPacket = new IcmpQueryPacket(queryType);
            icmpPacket.setCode(byteBuffer.get());
            icmpPacket.setChecksum(byteBuffer.getShort());
            icmpPacket.setUnused(byteBuffer.getInt());
            byte[] data = new byte[byteBuffer.remaining()];
            byteBuffer.get(data);
            icmpPacket.setData(data);
            return icmpPacket;
        }
        IcmpErrorType errorType = IcmpErrorType.parse(type);
        IcmpErrorPacket icmpPacket = new IcmpErrorPacket(errorType);
        icmpPacket.setCode(byteBuffer.get());
        icmpPacket.setChecksum(byteBuffer.getShort());
        icmpPacket.setUnused(byteBuffer.getInt());
        byte[] data = new byte[byteBuffer.remaining()];
        byteBuffer.get(data);
        icmpPacket.setData(data);
        return icmpPacket;
    }
}
