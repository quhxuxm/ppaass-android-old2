package com.ppaass.agent.rust.protocol.general.ip;

public class IpV6Header implements IIpHeader {
    private final IpHeaderVersion version;

    public IpV6Header() {
        this.version = IpHeaderVersion.V6;
    }

    @Override
    public IpHeaderVersion getVersion() {
        return this.version;
    }
}
