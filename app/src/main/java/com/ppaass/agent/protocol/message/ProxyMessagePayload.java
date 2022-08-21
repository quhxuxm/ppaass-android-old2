package com.ppaass.agent.protocol.message;

public class ProxyMessagePayload {
    private ProxyMessagePayloadType payloadType;
    private NetAddress sourceAddress;
    private NetAddress targetAddress;
    private byte[] data;

    public ProxyMessagePayloadType getPayloadType() {
        return payloadType;
    }

    public void setPayloadType(ProxyMessagePayloadType payloadType) {
        this.payloadType = payloadType;
    }

    public NetAddress getSourceAddress() {
        return sourceAddress;
    }

    public void setSourceAddress(NetAddress sourceAddress) {
        this.sourceAddress = sourceAddress;
    }

    public NetAddress getTargetAddress() {
        return targetAddress;
    }

    public void setTargetAddress(NetAddress targetAddress) {
        this.targetAddress = targetAddress;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    @Override
    public String toString() {
        return "ProxyMessagePayload{" +
                "payloadType=" + payloadType +
                ", sourceAddress=" + sourceAddress +
                ", targetAddress=" + targetAddress +
                ", data size=" + data.length +
                '}';
    }
}
