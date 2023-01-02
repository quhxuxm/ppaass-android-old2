package com.ppaass.agent.rust.protocol.general.icmp;

public class IcmpErrorPacket extends IcmpPacket<IcmpErrorType> {
    public IcmpErrorPacket(IcmpErrorType type) {
        this.setType(type);
    }
}
