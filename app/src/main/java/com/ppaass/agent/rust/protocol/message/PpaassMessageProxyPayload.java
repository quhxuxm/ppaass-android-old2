package com.ppaass.agent.rust.protocol.message;

public class PpaassMessageProxyPayload {
    private PpaassMessageProxyPayloadType payloadType;

    private byte[] data;

    public PpaassMessageProxyPayloadType getPayloadType() {
        return payloadType;
    }

    public void setPayloadType(PpaassMessageProxyPayloadType payloadType) {
        this.payloadType = payloadType;
    }


    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }


}
