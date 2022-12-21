package com.ppaass.agent.protocol.message;

import com.ppaass.agent.protocol.message.address.PpaassNetAddress;

public class PpaassMessageAgentPayload {
    private PpaassMessageAgentPayloadType payloadType;

    private byte[] data;

    public PpaassMessageAgentPayload() {
    }

    public PpaassMessageAgentPayloadType getPayloadType() {
        return payloadType;
    }

    public void setPayloadType(PpaassMessageAgentPayloadType payloadType) {
        this.payloadType = payloadType;
    }


    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }
}
