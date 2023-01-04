package com.ppaass.agent;

import android.util.Log;
import com.ppaass.agent.rust.protocol.general.ip.IpPacketReader;
import com.ppaass.agent.rust.protocol.general.ip.IpV4Header;
import com.ppaass.agent.rust.protocol.general.udp.UdpPacket;
import com.ppaass.agent.rust.service.handler.udp.IpV4UdpPacketHandler;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.dns.DatagramDnsQueryDecoder;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

public class DnsTest {
    @Test
    public void test() throws DecoderException, UnknownHostException {
        byte[] udpPacketContent = Hex.decodeHex("4500004f75a4000080112a640aaf04dc0af68015fb350035003bd9bc20a801000001000000000000117272312d2d2d736e2d6f3039377a6e736c0b676f6f676c65766964656f03636f6d0000010001");
        var ipPacket = IpPacketReader.INSTANCE.parse(udpPacketContent);
        var ipHeader = (IpV4Header) ipPacket.getHeader();
        var udpPacket = (UdpPacket) ipPacket.getData();

        InetSocketAddress udpDestAddress = new InetSocketAddress(InetAddress.getByAddress(ipHeader.getDestinationAddress()), udpPacket.getHeader().getDestinationPort());
        InetSocketAddress udpSourceAddress = new InetSocketAddress(InetAddress.getByAddress(ipHeader.getSourceAddress()), udpPacket.getHeader().getSourcePort());
        DatagramPacket dnsPacket = new DatagramPacket(Unpooled.wrappedBuffer(udpPacket.getData()), udpDestAddress, udpSourceAddress);
        EmbeddedChannel parseDnsQueryChannel = new EmbeddedChannel();
        parseDnsQueryChannel.pipeline().addLast(new DatagramDnsQueryDecoder());
        parseDnsQueryChannel.writeInbound(dnsPacket);
        DatagramDnsQuery dnsQuery = parseDnsQueryChannel.readInbound();
        System.out.println("---->>>> DNS Query (UDP): " + dnsQuery);
        System.out.println(udpPacket);
    }
}
