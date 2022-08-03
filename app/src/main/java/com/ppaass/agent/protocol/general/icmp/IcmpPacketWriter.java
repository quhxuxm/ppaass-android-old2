package com.ppaass.agent.protocol.general.icmp;

import com.ppaass.agent.protocol.general.ChecksumUtil;

import java.nio.ByteBuffer;

public class IcmpPacketWriter {
    public int generateChecksum(IcmpPacket packet) {
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(8 + packet.getData().length);
        byteBuffer.put(packet.getType().getValue());
        byteBuffer.put(packet.getCode());
        byteBuffer.putShort((short) 0);
        byteBuffer.putInt(packet.getUnused());
        byteBuffer.put(packet.getData());
        byteBuffer.flip();
        return ChecksumUtil.INSTANCE.checksum(byteBuffer);
    }

    public ByteBuffer write(IcmpPacket packet) {
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(8 + packet.getData().length);
        byteBuffer.put(packet.getType().getValue());
        byteBuffer.put(packet.getCode());
        int checksum = this.generateChecksum(packet);
        byteBuffer.putShort((short) (checksum & 0xFFFF));
        byteBuffer.putInt(packet.getUnused());
        byteBuffer.put(packet.getData());
        byteBuffer.flip();
        return byteBuffer;
    }
}
