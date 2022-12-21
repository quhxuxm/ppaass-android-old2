package com.ppaass.agent.protocol.message.payload;

import com.ppaass.agent.protocol.message.address.PpaassNetAddress;

public class TcpLoopInitRequestPayload {
    private PpaassNetAddress srcAddress;
    private PpaassNetAddress destAddress;

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
}
