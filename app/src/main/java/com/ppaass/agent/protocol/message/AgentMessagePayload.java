package com.ppaass.agent.protocol.message;

public class AgentMessagePayload {
    private AgentMessagePayloadType payloadType;
    private NetAddress sourceAddress;
    private NetAddress targetAddress;
    private byte[] data;

    public AgentMessagePayloadType getPayloadType() {
        return payloadType;
    }

    public void setPayloadType(AgentMessagePayloadType payloadType) {
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
}
