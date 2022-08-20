package com.ppaass.agent.protocol.general.udp;

import com.ppaass.agent.protocol.general.ip.IIpData;

public class UdpPacket implements IIpData {
    private final UdpHeader header;
    private final byte[] data;

    UdpPacket(UdpHeader header, byte[] data) {
        this.header = header;
        if (data == null) {
            this.data = new byte[]{};
        } else {
            this.data = data;
        }
    }

    public byte[] getData() {
        return data;
    }

    public UdpHeader getHeader() {
        return header;
    }

    @Override
    public String toString() {
        return "UdpPacket{" +
                "header=" + header +
                ", data size=" + data.length +
                '}';
    }
}
