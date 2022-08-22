package com.ppaass.agent.service.handler.icmp;

import android.util.Log;
import com.ppaass.agent.protocol.general.icmp.IcmpPacket;
import com.ppaass.agent.protocol.general.ip.IpV4Header;
import com.ppaass.agent.service.handler.IpPacketHandler;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

public class IpV4IcmpPacketHandler {
    public void handle(IcmpPacket<?> icmpPacket, IpV4Header ipV4Header) throws InterruptedException {
        Log.d(IpPacketHandler.class.getName(),
                ">>>>>>>> Receive ICMP packet, ip v4 header: " + ipV4Header + ", icmp packet: " + icmpPacket +
                        ", icmp packet content:\n" +
                        ByteBufUtil.prettyHexDump(
                                Unpooled.wrappedBuffer(icmpPacket.getData())) + "\n");
    }
}
