package com.ppaass.agent.rust.protocol.message.payload;

import com.ppaass.agent.rust.protocol.message.address.PpaassNetAddress;

public class DomainResolveRequestPayload {
    private String domainName;
    private String requestId;
    private PpaassNetAddress srcAddress;
    private PpaassNetAddress destAddress;

    public String getDomainName() {
        return domainName;
    }

    public void setDomainName(String domainName) {
        this.domainName = domainName;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
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
