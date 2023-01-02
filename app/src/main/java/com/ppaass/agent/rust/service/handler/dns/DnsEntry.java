package com.ppaass.agent.rust.service.handler.dns;

import java.util.List;

public class DnsEntry {
    private String name;
    private List<byte[]> addresses;
    private Long lastAccessTime;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<byte[]> getAddresses() {
        return addresses;
    }

    public void setAddresses(List<byte[]> addresses) {
        this.addresses = addresses;
    }

    public Long getLastAccessTime() {
        return lastAccessTime;
    }

    public void setLastAccessTime(Long lastAccessTime) {
        this.lastAccessTime = lastAccessTime;
    }
}
