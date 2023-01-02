package com.ppaass.agent.rust.protocol.message.payload;

import com.ppaass.agent.rust.protocol.message.address.PpaassNetAddress;

public class TcpLoopInitResponsePayload {
    private String loopKey;
    private PpaassNetAddress srcAddress;
    private PpaassNetAddress destAddress;
    private TcpLoopInitResponseType responseType;

    public String getLoopKey() {
        return loopKey;
    }

    public void setLoopKey(String loopKey) {
        this.loopKey = loopKey;
    }

    public PpaassNetAddress getSrcAddress() {
        return srcAddress;
    }

    public void setSrcAddress(PpaassNetAddress srcAddress) {
        this.srcAddress = srcAddress;
    }

    public PpaassNetAddress getDestAddress() {
        return destAddress;
    }

    public void setDestAddress(PpaassNetAddress destAddress) {
        this.destAddress = destAddress;
    }

    public TcpLoopInitResponseType getResponseType() {
        return responseType;
    }

    public void setResponseType(TcpLoopInitResponseType responseType) {
        this.responseType = responseType;
    }
}
