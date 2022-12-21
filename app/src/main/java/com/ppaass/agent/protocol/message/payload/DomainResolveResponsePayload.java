package com.ppaass.agent.protocol.message.payload;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

public class DomainResolveResponsePayload {
    private int requestId;
    private String domainName;
    private List<byte[]> resolvedIpAddresses;
    private DomainResolveResponseType responseType;

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
