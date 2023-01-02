package com.ppaass.agent.rust.protocol.general.udp;

import com.ppaass.agent.rust.protocol.general.IProtocolConst;

public class UdpPacketBuilder {
    private int sourcePort;
    private int destinationPort;
    private int checksum;
    private byte[] data;

    public UdpPacketBuilder sourcePort(int sourcePort) {
        this.sourcePort = sourcePort;
        return this;
    }

    public UdpPacketBuilder destinationPort(int destinationPort) {
        this.destinationPort = destinationPort;
        return this;
    }

    UdpPacketBuilder checksum(int checksum) {
        this.checksum = checksum;
        return this;
    }

    public UdpPacketBuilder data(byte[] data) {
        if (data == null) {
            this.data = new byte[]{};
            return this;
        }
        this.data = data;
        return this;
    }

    public UdpPacket build() {
        UdpHeader udpHeader = new UdpHeader();
        udpHeader.setChecksum(this.checksum);
        udpHeader.setDestinationPort(this.destinationPort);
        udpHeader.setSourcePort(this.sourcePort);
        udpHeader.setTotalLength(IProtocolConst.MIN_UDP_HEADER_LENGTH + this.data.length);
        return new UdpPacket(udpHeader, this.data);
    }
}
