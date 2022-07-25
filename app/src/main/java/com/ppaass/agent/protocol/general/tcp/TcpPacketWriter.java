package com.ppaass.agent.protocol.general.tcp;

import com.ppaass.agent.protocol.general.ChecksumUtil;
import com.ppaass.agent.protocol.general.ip.IpDataProtocol;
import com.ppaass.agent.protocol.general.ip.IpV4Header;
import com.ppaass.agent.protocol.general.ip.IpV6Header;

import java.nio.ByteBuffer;

public class TcpPacketWriter {
    public static final TcpPacketWriter INSTANCE = new TcpPacketWriter();

    private TcpPacketWriter() {
    }

    private int convertBoolean(boolean value) {
        if (value) {
            return 1;
        }
        return 0;
    }

    public byte[] write(TcpPacket packet, IpV4Header ipHeader) {
        ByteBuffer fakeHeaderByteBuffer = ByteBuffer.allocate(12);
        fakeHeaderByteBuffer.put(ipHeader.getSourceAddress());
        fakeHeaderByteBuffer.put(ipHeader.getDestinationAddress());
        fakeHeaderByteBuffer.put((byte) 0);
        fakeHeaderByteBuffer.put((byte) IpDataProtocol.TCP.getValue());
        fakeHeaderByteBuffer.putShort((short) (packet.getHeader().getOffset() * 4 + packet.getData().length));
        fakeHeaderByteBuffer.flip();
        ByteBuffer byteBufferForChecksum =
                ByteBuffer.allocate(packet.getHeader().getOffset() * 4 + 12 + packet.getData().length);
        byteBufferForChecksum.put(fakeHeaderByteBuffer);
        byte[] tcpPacketBytesForChecksum = this.writeWithGivenChecksum(packet, 0);
        byteBufferForChecksum.put(tcpPacketBytesForChecksum);
        byteBufferForChecksum.flip();
        int checksum = ChecksumUtil.INSTANCE.checksum(byteBufferForChecksum.array());
        byteBufferForChecksum.clear();
        return this.writeWithGivenChecksum(packet, checksum);
    }

    public byte[] write(TcpPacket packet, IpV6Header ipHeader) {
        throw new UnsupportedOperationException("Do not support IPv6");
    }

    private byte[] writeWithGivenChecksum(TcpPacket packet, int checksum) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(packet.getHeader().getOffset() * 4 + packet.getData().length);
        byteBuffer.putShort((short) packet.getHeader().getSourcePort());
        byteBuffer.putShort((short) packet.getHeader().getDestinationPort());
        byteBuffer.putInt((int) packet.getHeader().getSequenceNumber());
        byteBuffer.putInt((int) packet.getHeader().getAcknowledgementNumber());
        int offsetAndResolvedAndUAPRSF = (packet.getHeader().getOffset() << 6) | (packet.getHeader().getResolve());
        offsetAndResolvedAndUAPRSF = offsetAndResolvedAndUAPRSF << 6;
        int flags = (this.convertBoolean(packet.getHeader().isUrg()) << 5) |
                (this.convertBoolean(packet.getHeader().isAck()) << 4) |
                (this.convertBoolean(packet.getHeader().isPsh()) << 3) |
                (this.convertBoolean(packet.getHeader().isRst()) << 2) |
                (this.convertBoolean(packet.getHeader().isSyn()) << 1) |
                this.convertBoolean(packet.getHeader().isFin());
        offsetAndResolvedAndUAPRSF = offsetAndResolvedAndUAPRSF | flags;
        byteBuffer.putShort((short) offsetAndResolvedAndUAPRSF);
        byteBuffer.putShort((short) packet.getHeader().getWindow());
        byteBuffer.putShort((short) checksum);
        byteBuffer.putShort((short) packet.getHeader().getUrgPointer());
        ByteBuffer optionAndPaddingByteBuffer = ByteBuffer.allocate(40);
        for (TcpHeaderOption option : packet.getHeader().getOptions()) {
            if (option.getKind() == TcpHeaderOption.Kind.EOL) {
                break;
            }
            if (option.getKind() == TcpHeaderOption.Kind.NOP) {
                optionAndPaddingByteBuffer.put((byte) TcpHeaderOption.Kind.NOP.getValue());
                continue;
            }
            optionAndPaddingByteBuffer.put((byte) option.getKind().getValue());
            if (option.getKind().getInfoLength() == -1) {
                optionAndPaddingByteBuffer.put((byte) (option.getInfo().length + 2));
            } else {
                optionAndPaddingByteBuffer.put((byte) (option.getKind().getInfoLength() + 2));
            }
            optionAndPaddingByteBuffer.put(option.getInfo());
        }
        int bytesNumber = 0;
        if (optionAndPaddingByteBuffer.position() > 0) {
            bytesNumber = optionAndPaddingByteBuffer.position();
        }
        int paddingByteNumber = 0;
        if (bytesNumber % 4 != 0) {
            paddingByteNumber = 4 - (bytesNumber % 4);
        }
        for (int i = 0; i < paddingByteNumber; i++) {
            optionAndPaddingByteBuffer.put((byte) 0);
        }
        optionAndPaddingByteBuffer.flip();
        byte[] optionAndPaddingBytes = new byte[optionAndPaddingByteBuffer.remaining()];
        optionAndPaddingByteBuffer.get(optionAndPaddingBytes);
        byteBuffer.put(optionAndPaddingBytes);
        byteBuffer.put(packet.getData());
        byteBuffer.flip();
        byte[] result = byteBuffer.array();
        byteBuffer.clear();
        return result;
    }
}
