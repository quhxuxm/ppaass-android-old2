package com.ppaass.agent.rust.protocol.general.tcp;

import com.ppaass.agent.rust.protocol.general.IProtocolConst;

import java.nio.ByteBuffer;

public class TcpPacketReader {
    public static final TcpPacketReader INSTANCE = new TcpPacketReader();

    private TcpPacketReader() {
    }

    public TcpPacket parse(byte[] input) {
        ByteBuffer byteBuffer = ByteBuffer.wrap(input);
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        tcpPacketBuilder.sourcePort(byteBuffer.getShort() & 0xFFFF);
        tcpPacketBuilder.destinationPort(byteBuffer.getShort() & 0xFFFF);
        tcpPacketBuilder.sequenceNumber(byteBuffer.getInt() & 0xFFFFFFFFL);
        tcpPacketBuilder.acknowledgementNumber(byteBuffer.getInt() & 0xFFFFFFFFL);
        int offsetAndResolvedAndUAPRSF = byteBuffer.getShort() & 0xFFFF;
        int offset = offsetAndResolvedAndUAPRSF >> 12;
        tcpPacketBuilder.resolve((offsetAndResolvedAndUAPRSF >> 6) & 0x3F);
        tcpPacketBuilder.urg(((offsetAndResolvedAndUAPRSF >> 5) & 1) != 0);
        tcpPacketBuilder.ack(((offsetAndResolvedAndUAPRSF >> 4) & 1) != 0);
        tcpPacketBuilder.psh(((offsetAndResolvedAndUAPRSF >> 3) & 1) != 0);
        tcpPacketBuilder.rst(((offsetAndResolvedAndUAPRSF >> 2) & 1) != 0);
        tcpPacketBuilder.syn(((offsetAndResolvedAndUAPRSF >> 1) & 1) != 0);
        tcpPacketBuilder.fin((offsetAndResolvedAndUAPRSF & 1) != 0);
        tcpPacketBuilder.window(byteBuffer.getShort() & 0xFFFF);
        int checksum = byteBuffer.getShort() & 0xFFFF;
        tcpPacketBuilder.checksum(checksum);
        tcpPacketBuilder.urgPointer(byteBuffer.getShort() & 0xFFFF);
        int headerLength = offset * 4;
        byte[] optionAndPadding = new byte[headerLength - IProtocolConst.MIN_TCP_HEADER_LENGTH];
        byteBuffer.get(optionAndPadding);
        ByteBuffer optionAndPaddingBuffer = ByteBuffer.wrap(optionAndPadding);
        while (optionAndPaddingBuffer.hasRemaining()) {
            int optionKind = optionAndPaddingBuffer.get() & 0xFF;
            TcpHeaderOption.Kind tcpHeaderOptionKind = TcpHeaderOption.Kind.fromValue(optionKind);
            if (tcpHeaderOptionKind == TcpHeaderOption.Kind.EOL) {
                break;
            }
            if (tcpHeaderOptionKind == TcpHeaderOption.Kind.NOP) {
                tcpPacketBuilder.addOption(new TcpHeaderOption(TcpHeaderOption.Kind.NOP, new byte[]{}));
                continue;
            }
            if (tcpHeaderOptionKind == null) {
                throw new IllegalStateException(
                        "The option kind is not exist, option kind value=" + optionKind);
            }
            int infoLengthInDefinition = tcpHeaderOptionKind.getInfoLength();
            int totalOptionLengthInByte = optionAndPaddingBuffer.get() & 0xFF;
            if (infoLengthInDefinition != -1) {
                if (totalOptionLengthInByte != (infoLengthInDefinition + 2)) {
                    continue;
                }
            }
            byte[] infoBytes = new byte[totalOptionLengthInByte - 2];
            optionAndPaddingBuffer.get(infoBytes);
            tcpPacketBuilder.addOption(new TcpHeaderOption(tcpHeaderOptionKind, infoBytes));
        }
        int dataLength = input.length - headerLength;
        byte[] data = new byte[dataLength];
        byteBuffer.get(data);
        tcpPacketBuilder.data(data);
        TcpPacket result = tcpPacketBuilder.build();
        if (result.getHeader().getOffset() != offset) {
            throw new IllegalStateException(
                    "The offset in the input data do not match, result.offest=" + result.getHeader().getOffset() +
                            ", offset=" + offset);
        }
        byteBuffer.clear();
        return result;
    }
}
