package com.ppaass.agent.rust.protocol.general.icmp;

import com.ppaass.agent.rust.protocol.general.ip.IIpData;

public abstract class IcmpPacket<T extends IIcmpType> implements IIpData {
    private byte code;
    private short checksum;
    private int unused;
    private byte[] data;
    private T type;

    protected IcmpPacket() {
    }

    public T getType() {
        return type;
    }

    protected void setType(T type) {
        this.type = type;
    }

    public byte getCode() {
        return code;
    }

    public void setCode(byte code) {
        this.code = code;
    }

    public short getChecksum() {
        return checksum;
    }

    public void setChecksum(short checksum) {
        this.checksum = checksum;
    }

    public void setUnused(int unused) {
        this.unused = unused;
    }

    public int getUnused() {
        return unused;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    public int getTotalLength() {
        return 8 + this.data.length;
    }

    @Override
    public String toString() {
        return "IcmpPacket{" +
                "type=" + type +
                ", code=" + code +
                ", checksum=" + checksum +
                ", unused=" + unused +
                ", data size=" + data.length +
                '}';
    }
}
