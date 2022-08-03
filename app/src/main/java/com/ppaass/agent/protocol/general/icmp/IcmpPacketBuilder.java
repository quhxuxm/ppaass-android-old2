package com.ppaass.agent.protocol.general.icmp;

public class IcmpPacketBuilder {
    private byte code;
    private short checksum;
    private int unused;
    private byte[] data;

    public void setCode(byte code) {
        this.code = code;
    }

    public void setChecksum(short checksum) {
        this.checksum = checksum;
    }

    public void setUnused(int unused) {
        this.unused = unused;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    public IcmpErrorPacket buildError(IcmpErrorType type) {
        IcmpErrorPacket result = new IcmpErrorPacket(type);
        result.setCode(this.code);
        result.setChecksum(this.checksum);
        result.setUnused(unused);
        result.setData(this.data);
        return result;
    }

    public IcmpQueryPacket buildQuery(IcmpQueryType type) {
        IcmpQueryPacket result = new IcmpQueryPacket(type);
        result.setCode(this.code);
        result.setChecksum(this.checksum);
        result.setUnused(unused);
        result.setData(this.data);
        return result;
    }
}
