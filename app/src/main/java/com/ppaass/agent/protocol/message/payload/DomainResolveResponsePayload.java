package com.ppaass.agent.protocol.message.payload;

import com.ppaass.agent.protocol.message.address.PpaassNetAddress;

import java.util.List;

public class DomainResolveResponsePayload {
    private int requestId;
    private String domainName;
    private List<byte[]> resolvedIpAddresses;
    private DomainResolveResponseType responseType;
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

    public int getRequestId() {
        return requestId;
    }

    public void setRequestId(int requestId) {
        this.requestId = requestId;
    }

    public String getDomainName() {
        return domainName;
    }

    public void setDomainName(String domainName) {
        this.domainName = domainName;
    }

    public List<byte[]> getResolvedIpAddresses() {
        return resolvedIpAddresses;
    }

    public void setResolvedIpAddresses(List<byte[]> resolvedIpAddresses) {
        this.resolvedIpAddresses = resolvedIpAddresses;
    }

    public DomainResolveResponseType getResponseType() {
        return responseType;
    }

    public void setResponseType(DomainResolveResponseType responseType) {
        this.responseType = responseType;
    }
}
