package com.ppaass.agent.rust.protocol.message.payload;

import com.ppaass.agent.rust.protocol.message.address.PpaassNetAddress;

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
