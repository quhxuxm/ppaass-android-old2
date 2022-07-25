package com.ppaass.agent.protocol.general.tcp;

import com.ppaass.agent.protocol.general.IProtocolConst;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class TcpPacketBuilder {
    private int sourcePort;
    private int destinationPort;
    private long sequenceNumber;
    private long acknowledgementNumber;
    private int resolve;
    private boolean urg;
    private boolean ack;
    private boolean psh;
    private boolean rst;
    private boolean syn;
    private boolean fin;
    private int window;
    private int urgPointer;
    private final List<TcpHeaderOption> options;
    private byte[] data;
    private int checksum;

    public TcpPacketBuilder() {
        this.options = new ArrayList<>();
        this.data = new byte[]{};
        this.window = 0;
        this.urgPointer = 0;
        this.resolve = 0;
    }

    public TcpPacketBuilder sourcePort(int sourcePort) {
        this.sourcePort = sourcePort;
        return this;
    }

    public TcpPacketBuilder destinationPort(int destinationPort) {
        this.destinationPort = destinationPort;
        return this;
    }

    public TcpPacketBuilder sequenceNumber(long sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
        return this;
    }

    public TcpPacketBuilder acknowledgementNumber(long acknowledgementNumber) {
        this.acknowledgementNumber = acknowledgementNumber;
        return this;
    }

    public TcpPacketBuilder resolve(int resolve) {
        this.resolve = resolve;
        return this;
    }

    public TcpPacketBuilder urg(boolean urg) {
        this.urg = urg;
        return this;
    }

    public TcpPacketBuilder ack(boolean ack) {
        this.ack = ack;
        return this;
    }

    public TcpPacketBuilder psh(boolean psh) {
        this.psh = psh;
        return this;
    }

    public TcpPacketBuilder rst(boolean rst) {
        this.rst = rst;
        return this;
    }

    public TcpPacketBuilder syn(boolean syn) {
        this.syn = syn;
        return this;
    }

    public TcpPacketBuilder fin(boolean fin) {
        this.fin = fin;
        return this;
    }

    public TcpPacketBuilder window(int window) {
        this.window = window;
        return this;
    }

    public TcpPacketBuilder urgPointer(int urgPointer) {
        this.urgPointer = urgPointer;
        return this;
    }

    public TcpPacketBuilder addOption(TcpHeaderOption option) {
        this.options.add(option);
        return this;
    }

    TcpPacketBuilder checksum(int checksum) {
        this.checksum = checksum;
        return this;
    }

    public TcpPacketBuilder data(byte[] data) {
        if (data == null) {
            this.data = new byte[]{};
            return this;
        }
        this.data = data;
        return this;
    }

    public TcpPacket build() {
        TcpHeader
                tcpHeader = new TcpHeader();
        tcpHeader.setSourcePort(this.sourcePort);
        tcpHeader.setDestinationPort(this.destinationPort);
        tcpHeader.setSequenceNumber(this.sequenceNumber);
        tcpHeader.setAcknowledgementNumber(this.acknowledgementNumber);
        tcpHeader.setAck(this.ack);
        tcpHeader.setSyn(this.syn);
        tcpHeader.setFin(this.fin);
        tcpHeader.setPsh(this.psh);
        tcpHeader.setRst(this.rst);
        tcpHeader.setUrg(this.urg);
        tcpHeader.getOptions().addAll(this.options);
        ByteBuffer optionAndPaddingByteBuffer = ByteBuffer.allocate(40);
        for (TcpHeaderOption option : tcpHeader.getOptions()) {
            if (option.getKind() == TcpHeaderOption.Kind.EOL) {
                break;
            }
            if (option.getKind() == TcpHeaderOption.Kind.NOP) {
                optionAndPaddingByteBuffer
                        .put((byte) TcpHeaderOption.Kind.NOP.getValue());
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
        int bytesNumber = optionAndPaddingByteBuffer.position();
        int paddingByteNumber = 0;
        if (bytesNumber % 4 != 0) {
            paddingByteNumber = 4 - (bytesNumber % 4);
        }
        for (int i = 0; i < paddingByteNumber; i++) {
            optionAndPaddingByteBuffer.put((byte) 0);
        }
        optionAndPaddingByteBuffer.flip();
        int offset = (optionAndPaddingByteBuffer.remaining() + IProtocolConst.MIN_TCP_HEADER_LENGTH) / 4;
        optionAndPaddingByteBuffer.clear();
        tcpHeader.setOffset(offset);
        tcpHeader.setResolve(this.resolve);
        tcpHeader.setUrgPointer(this.urgPointer);
        tcpHeader.setWindow(this.window);
        tcpHeader.setChecksum(this.checksum);
        return new TcpPacket(tcpHeader, this.data);
    }
}

