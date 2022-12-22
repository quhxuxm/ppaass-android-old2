package com.ppaass.agent.service.handler.udp;

import android.util.Log;
import com.ppaass.agent.protocol.general.udp.UdpPacket;
import com.ppaass.agent.protocol.general.udp.UdpPacketBuilder;
import com.ppaass.agent.protocol.message.PpaassMessage;
import com.ppaass.agent.protocol.message.PpaassMessageProxyPayload;
import com.ppaass.agent.protocol.message.PpaassMessageProxyPayloadType;
import com.ppaass.agent.protocol.message.address.PpaassNetAddress;
import com.ppaass.agent.protocol.message.payload.DomainResolveResponseType;
import com.ppaass.agent.service.handler.IUdpIpPacketWriter;
import com.ppaass.agent.service.handler.PpaassMessageUtil;
import com.ppaass.agent.service.handler.dns.DnsRepository;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.dns.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;

public class UdpProxyMessageHandler extends SimpleChannelInboundHandler<PpaassMessage> {

    private final IUdpIpPacketWriter ipPacketWriter;

    public UdpProxyMessageHandler(IUdpIpPacketWriter ipPacketWriter) {
        this.ipPacketWriter = ipPacketWriter;

    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, PpaassMessage proxyMessage) throws IOException {
        //Relay remote data to device and use mss as the transfer unit
        PpaassMessageProxyPayload proxyMessagePayload = PpaassMessageUtil.INSTANCE.convertBytesToProxyMessagePayload(proxyMessage.getPayloadBytes());
        if (PpaassMessageProxyPayloadType.DomainNameResolve == proxyMessagePayload.getPayloadType()) {
            var domainResolveResponse = PpaassMessageUtil.INSTANCE.parseDomainNameResolveResponseMessage(proxyMessagePayload.getData());
            if (domainResolveResponse.getResponseType() == DomainResolveResponseType.Success) {
                Log.d(UdpProxyMessageHandler.class.getName(), "<<<<----#### Domain resolve response: " + domainResolveResponse);
                domainResolveResponse.getResolvedIpAddresses().forEach(addressBytes -> {
                    DnsRepository.INSTANCE.saveAddresses(domainResolveResponse.getDomainName(), Collections.singletonList(addressBytes));
                });

                PpaassNetAddress srcAddress = domainResolveResponse.getSrcAddress();
                PpaassNetAddress destAddress = domainResolveResponse.getDestAddress();

                InetSocketAddress srcInetAddress = new InetSocketAddress(InetAddress.getByAddress(srcAddress.getValue().getIp()), srcAddress.getValue().getPort());
                InetSocketAddress destInetAddress = new InetSocketAddress(InetAddress.getByAddress(destAddress.getValue().getIp()), destAddress.getValue().getPort());
                DatagramDnsResponse dnsResponse = new DatagramDnsResponse(srcInetAddress, destInetAddress, Integer.parseInt(domainResolveResponse.getRequestId()));
                DefaultDnsQuestion dnsQuestion = new DefaultDnsQuestion(domainResolveResponse.getDomainName(), DnsRecordType.A);
                dnsResponse.addRecord(DnsSection.QUESTION, dnsQuestion);
                domainResolveResponse.getResolvedIpAddresses().forEach(addressBytes -> {
                    DefaultDnsRawRecord answerRecord = new DefaultDnsRawRecord(domainResolveResponse.getDomainName(), DnsRecordType.A, 120, Unpooled.wrappedBuffer(addressBytes));
                    dnsResponse.addRecord(DnsSection.ANSWER, answerRecord);
                });
                EmbeddedChannel generateDnsResponseBytesChannel = new EmbeddedChannel();
                generateDnsResponseBytesChannel.pipeline().addLast(new DatagramDnsResponseEncoder());
                generateDnsResponseBytesChannel.writeOutbound(dnsResponse);
                DatagramPacket dnsResponseUdpPacket = generateDnsResponseBytesChannel.readOutbound();
                short udpIpPacketId = (short) (Math.random() * 10000);
                UdpPacketBuilder remoteToDeviceUdpPacketBuilder = new UdpPacketBuilder();
                remoteToDeviceUdpPacketBuilder.data(dnsResponseUdpPacket.content().array());
                remoteToDeviceUdpPacketBuilder.destinationPort(srcAddress.getValue().getPort());
                remoteToDeviceUdpPacketBuilder.sourcePort(destAddress.getValue().getPort());
                UdpPacket remoteToDeviceUdpPacket = remoteToDeviceUdpPacketBuilder.build();
                try {
                    this.ipPacketWriter.writeToDevice(udpIpPacketId, remoteToDeviceUdpPacket, srcInetAddress.getAddress().getAddress(), destInetAddress.getAddress().getAddress(), 0);
                } catch (IOException e) {
                    Log.e(IpV4UdpPacketHandler.class.getName(), "Ip v4 udp handler have exception.", e);
                }
            }
            return;
        }

    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        Log.e(UdpProxyMessageHandler.class.getName(), "<<<<---- Udp channel exception happen on remote channel", cause);
    }
}
