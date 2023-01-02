package com.ppaass.agent.rust.protocol.general.tcp;

import com.ppaass.agent.rust.protocol.general.ChecksumUtil;
import com.ppaass.agent.rust.protocol.general.ip.IpDataProtocol;
import com.ppaass.agent.rust.protocol.general.ip.IpV4Header;
import com.ppaass.agent.rust.protocol.general.ip.IpV6Header;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class TcpPacketWriter {
    private static final int FAKE_HEADER_LENGTH = 12;
    public static final TcpPacketWriter INSTANCE = new TcpPacketWriter();

    private TcpPacketWriter() {
    }

    private int convertBoolean(boolean value) {
        if (value) {
            return 1;
        }
        return 0;
    }

    private ByteBuffer generateFakeHeader(TcpPacket tcpPacket, IpV4Header ipV4Header) {
        ByteBuffer tcpFakeHeaderBytes = ByteBuffer.allocateDirect(FAKE_HEADER_LENGTH);
        tcpFakeHeaderBytes.order(ByteOrder.BIG_ENDIAN);
        tcpFakeHeaderBytes.put(ipV4Header.getSourceAddress());
        tcpFakeHeaderBytes.put(ipV4Header.getDestinationAddress());
        tcpFakeHeaderBytes.put((byte) 0);
        tcpFakeHeaderBytes.put((byte) IpDataProtocol.TCP.getValue());
        tcpFakeHeaderBytes.putShort(
                (short) ((tcpPacket.getHeader().getOffset() * 4 + tcpPacket.getData().length) & 0xFFFF));
        tcpFakeHeaderBytes.flip();
        return tcpFakeHeaderBytes;
    }

    public ByteBuffer generateFakeHeader(TcpPacket tcpPacket, IpV6Header ipV6Header) {
        throw new UnsupportedOperationException("Do not support IPv6");
    }

    public ByteBuffer write(TcpPacket packet, IpV4Header ipHeader) {
        ByteBuffer tcpFakeHeaderBytes = this.generateFakeHeader(packet, ipHeader);
        ByteBuffer byteBufferForChecksum =
                ByteBuffer.allocateDirect(
                        packet.getHeader().getOffset() * 4 + FAKE_HEADER_LENGTH + packet.getData().length);
        byteBufferForChecksum.order(ByteOrder.BIG_ENDIAN);
        byteBufferForChecksum.put(tcpFakeHeaderBytes);
        ByteBuffer tcpPacketBytesForChecksum = this.writeWithGivenChecksum(packet, 0);
        tcpPacketBytesForChecksum.order(ByteOrder.BIG_ENDIAN);
        byteBufferForChecksum.put(tcpPacketBytesForChecksum);
        byteBufferForChecksum.flip();
        int checksum = ChecksumUtil.INSTANCE.checksum(byteBufferForChecksum);
        byteBufferForChecksum.clear();
        return this.writeWithGivenChecksum(packet, checksum);
    }

    public byte[] write(TcpPacket packet, IpV6Header ipHeader) {
        throw new UnsupportedOperationException("Do not support IPv6");
    }

    private ByteBuffer writeWithGivenChecksum(TcpPacket packet, int checksum) {
        ByteBuffer resultBuf = ByteBuffer.allocateDirect(packet.getHeader().getOffset() * 4 + packet.getData().length);
        resultBuf.order(ByteOrder.BIG_ENDIAN);
        resultBuf.putShort((short) (packet.getHeader().getSourcePort() & 0xFFFF));
        resultBuf.putShort((short) (packet.getHeader().getDestinationPort() & 0xFFFF));
        resultBuf.putInt((int) packet.getHeader().getSequenceNumber());
        resultBuf.putInt((int) packet.getHeader().getAcknowledgementNumber());
        int offsetAndResolvedAndUAPRSF = (packet.getHeader().getOffset() << 6) | (packet.getHeader().getResolve());
        offsetAndResolvedAndUAPRSF = offsetAndResolvedAndUAPRSF << 6;
        int flags = (this.convertBoolean(packet.getHeader().isUrg()) << 5) |
                (this.convertBoolean(packet.getHeader().isAck()) << 4) |
                (this.convertBoolean(packet.getHeader().isPsh()) << 3) |
                (this.convertBoolean(packet.getHeader().isRst()) << 2) |
                (this.convertBoolean(packet.getHeader().isSyn()) << 1) |
                this.convertBoolean(packet.getHeader().isFin());
        offsetAndResolvedAndUAPRSF = offsetAndResolvedAndUAPRSF | flags;
        resultBuf.putShort((short) (offsetAndResolvedAndUAPRSF & 0xFFFF));
        resultBuf.putShort((short) (packet.getHeader().getWindow() & 0xFFFF));
        resultBuf.putShort((short) (checksum & 0xFFFF));
        resultBuf.putShort((short) (packet.getHeader().getUrgPointer() & 0xFFFF));
        ByteBuffer optionAndPaddingByteBuffer = ByteBuffer.allocateDirect(40);
        optionAndPaddingByteBuffer.order(ByteOrder.BIG_ENDIAN);
        for (TcpHeaderOption option : packet.getHeader().getOptions()) {
            if (option.getKind() == TcpHeaderOption.Kind.EOL) {
                break;
            }
            if (option.getKind() == TcpHeaderOption.Kind.NOP) {
                optionAndPaddingByteBuffer.put((byte) (TcpHeaderOption.Kind.NOP.getValue() & 0xFF));
                continue;
            }
            optionAndPaddingByteBuffer.put((byte) (option.getKind().getValue() & 0xFF));
            if (option.getKind().getInfoLength() == -1) {
                optionAndPaddingByteBuffer.put((byte) ((option.getInfo().length + 2) & 0xFF));
            } else {
                optionAndPaddingByteBuffer.put((byte) ((option.getKind().getInfoLength() + 2) & 0xFF));
            }
            if (option.getInfo() != null) {
                optionAndPaddingByteBuffer.put(option.getInfo());
            }
        }
        int bytesNumber = Math.max(optionAndPaddingByteBuffer.position(), 0);
        int paddingByteNumber = 0;
        if (bytesNumber % 4 != 0) {
            paddingByteNumber = 4 - (bytesNumber % 4);
        }
        for (int i = 0; i < paddingByteNumber; i++) {
            optionAndPaddingByteBuffer.put((byte) 0);
        }
        optionAndPaddingByteBuffer.flip();
        resultBuf.put(optionAndPaddingByteBuffer);
        resultBuf.put(packet.getData());
        resultBuf.flip();
        return resultBuf;
    }
}
