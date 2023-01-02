package com.ppaass.agent.rust.protocol.general.ip;

public class IpPacket {
    private final IIpHeader header;
    private final IIpData data;

    IpPacket(IIpHeader header, IIpData data) {
        this.header = header;
        this.data = data;
    }

    public IIpHeader getHeader() {
        return header;
    }

    public IIpData getData() {
        return data;
    }

    @Override
    public String toString() {
        return "IpPacket{" +
                "header=" + header +
                ", data=" + data +
                '}';
    }
}
