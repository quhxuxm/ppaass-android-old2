package com.ppaass.agent.service.handler.udp;

import com.ppaass.agent.protocol.general.ip.IpV4Header;
import com.ppaass.agent.protocol.general.udp.UdpPacket;

class IpV4UdpPacketWrapper {
    private final UdpPacket udpPacket;
    private final IpV4Header ipV4Header;

    public IpV4UdpPacketWrapper(UdpPacket udpPacket, IpV4Header ipV4Header) {
        this.udpPacket = udpPacket;
        this.ipV4Header = ipV4Header;
    }

    public IpV4Header getIpV4Header() {
        return ipV4Header;
    }

    public UdpPacket getUdpPacket() {
        return udpPacket;
    }
}
