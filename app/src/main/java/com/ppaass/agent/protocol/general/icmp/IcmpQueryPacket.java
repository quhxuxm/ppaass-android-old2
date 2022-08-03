package com.ppaass.agent.protocol.general.icmp;

public class IcmpQueryPacket extends IcmpPacket<IcmpQueryType> {
    public IcmpQueryPacket(IcmpQueryType type) {
        this.setType(type);
    }
}
