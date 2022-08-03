package com.ppaass.agent.protocol.general.ip;

import com.ppaass.agent.protocol.general.icmp.IcmpPacket;
import com.ppaass.agent.protocol.general.tcp.TcpPacket;
import com.ppaass.agent.protocol.general.udp.UdpPacket;

public class IpPacketBuilder {
    private IIpHeader header;
    private IIpData data;

    public IpPacketBuilder header(IIpHeader header) {
        this.header = header;
        return this;
    }

    public IpPacketBuilder data(IIpData data) {
        this.data = data;
        return this;
    }

    public IpPacket build() {
        if (this.header.getVersion() != IpHeaderVersion.V4) {
            return new IpPacket(this.header, null);
        }
        IpV4Header header = (IpV4Header) this.header;
        if (IpDataProtocol.TCP == header.getProtocol()) {
            TcpPacket tcpPacket = (TcpPacket) this.data;
            header.setTotalLength(header.getInternetHeaderLength() * 4 + tcpPacket.getHeader().getOffset() * 4 +
                    tcpPacket.getData().length);
            return new IpPacket(header, tcpPacket);
        }
        if (IpDataProtocol.UDP == header.getProtocol()) {
            UdpPacket udpPacket = (UdpPacket) this.data;
            header.setTotalLength(header.getInternetHeaderLength() * 4 + udpPacket.getHeader().getTotalLength());
            return new IpPacket(header, udpPacket);
        }
        if (IpDataProtocol.ICMP == header.getProtocol()) {
            IcmpPacket icmpPacket = (IcmpPacket) this.data;
            header.setTotalLength(header.getInternetHeaderLength() * 4 + icmpPacket.getTotalLength());
            return new IpPacket(header, icmpPacket);
        }
        throw new IllegalStateException("Illegal protocol found in ip packet.");
    }
}
