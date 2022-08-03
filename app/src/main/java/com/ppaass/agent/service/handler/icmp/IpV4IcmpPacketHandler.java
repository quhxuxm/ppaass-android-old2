package com.ppaass.agent.service.handler.icmp;

import android.util.Log;
import com.ppaass.agent.protocol.general.icmp.IcmpPacket;
import com.ppaass.agent.protocol.general.ip.IpV4Header;
import com.ppaass.agent.service.handler.IpPacketHandler;

public class IpV4IcmpPacketHandler {
    public void handle(IcmpPacket<?> icmpPacket, IpV4Header ipV4Header) throws InterruptedException {
        Log.d(IpPacketHandler.class.getName(), "Receive ICMP packet: " + icmpPacket);
    }
}
