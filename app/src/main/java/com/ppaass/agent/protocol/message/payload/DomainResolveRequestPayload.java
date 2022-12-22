package com.ppaass.agent.protocol.message.payload;

import com.ppaass.agent.protocol.message.address.PpaassNetAddress;

public class DomainResolveRequestPayload {
    private String domainName;
    private int requestId;
    private PpaassNetAddress srcAddress;
    private PpaassNetAddress destAddress;

    public String getDomainName() {
        return domainName;
    }

    public void setDomainName(String domainName) {
        this.domainName = domainName;
    }

    public int getRequestId() {
        return requestId;
    }

    public void setRequestId(int requestId) {
        this.requestId = requestId;
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
}
