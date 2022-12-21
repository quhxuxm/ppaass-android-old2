package com.ppaass.agent.protocol.message.payload;

public class DomainResolveRequestPayload {
    private String domainName;
    private int requestId;

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
}
