package com.ppaass.agent.protocol.general;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class ChecksumUtil {
    public static final ChecksumUtil INSTANCE = new ChecksumUtil();

    private ChecksumUtil() {
    }
    //    public int checksum(byte[] bytesToDoChecksum) {
//        int sum = 0;
//        ByteBuffer byteBuffer = ByteBuffer.allocate(bytesToDoChecksum.length);
//        byteBuffer.order(ByteOrder.BIG_ENDIAN);
//        byteBuffer.put(bytesToDoChecksum);
//        byteBuffer.flip();
//        while (byteBuffer.remaining() > 1) {
//            int currentShort = byteBuffer.getShort() & 0xFFFF;
//            sum += currentShort;
//        }
//        if (byteBuffer.remaining() == 1) {
//            byte finalByte = byteBuffer.get();
//            int finalShort = (finalByte & 0xFF) << 8;
//            sum += finalShort;
//        }
//        while (sum >> 16 > 0) {
//            int tmp = sum & 0xFFFF;
//            sum = (tmp + (sum >> 16));
//        }
//        sum = ~sum;
//        return sum;
//    }

    public short checksum(ByteBuffer byteBuffer) {
        byteBuffer.order(ByteOrder.BIG_ENDIAN);
        int sum = 0;
        if (byteBuffer.remaining() % 2 != 0) {
            byteBuffer.put((byte) 0);
        }
        while (byteBuffer.remaining() > 0) {
            int currentShort = byteBuffer.getShort() & 0xFFFF;
            sum += currentShort;
        }
        while (sum >> 16 > 0) {
            sum = ((sum & 0xFFFF) + (sum >> 16));
        }
        sum = ~sum;
        sum = sum & 0xFFFF;
        return (short) sum;
    }
}
