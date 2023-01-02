package com.ppaass.agent.rust.protocol.general.icmp;

public class IcmpQueryPacket extends IcmpPacket<IcmpQueryType> {
    public IcmpQueryPacket(IcmpQueryType type) {
        this.setType(type);
    }
}
