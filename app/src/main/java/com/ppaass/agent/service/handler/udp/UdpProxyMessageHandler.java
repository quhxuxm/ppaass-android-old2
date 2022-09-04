package com.ppaass.agent.service.handler.udp;

import android.util.Log;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ppaass.agent.protocol.general.udp.UdpPacket;
import com.ppaass.agent.protocol.general.udp.UdpPacketBuilder;
import com.ppaass.agent.protocol.message.*;
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
import java.net.UnknownHostException;

public class UdpProxyMessageHandler extends SimpleChannelInboundHandler<Message> {
    private final ObjectMapper objectMapper;
    private final IUdpIpPacketWriter ipPacketWriter;

    public UdpProxyMessageHandler(IUdpIpPacketWriter ipPacketWriter) {
        this.ipPacketWriter = ipPacketWriter;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Message proxyMessage)
            throws IOException {
        //Relay remote data to device and use mss as the transfer unit
        ProxyMessagePayload proxyMessagePayload =
                PpaassMessageUtil.INSTANCE.parseProxyMessagePayloadBytes(proxyMessage.getPayload());
        if (ProxyMessagePayloadType.DomainResolveFail == proxyMessagePayload.getPayloadType()) {
            return;
        }
        if (ProxyMessagePayloadType.DomainResolveSuccess == proxyMessagePayload.getPayloadType()) {
            DomainResolveResponse domainResolveResponse =
                    this.objectMapper.readValue(proxyMessagePayload.getData(), DomainResolveResponse.class);
            Log.d(UdpProxyMessageHandler.class.getName(),
                    "<<<<----#### Domain resolve response: " + domainResolveResponse);
            domainResolveResponse.getAddresses().forEach(addressBytes -> {
                try {
                    DnsRepository.INSTANCE.saveAddress(domainResolveResponse.getName(),
                            InetAddress.getByAddress(addressBytes));
                } catch (UnknownHostException e) {
                    Log.e(UdpProxyMessageHandler.class.getName(),
                            "Fail to save domain name to address mapping because of error.", e);
                }
            });
            NetAddress sourceNetAddress = proxyMessagePayload.getSourceAddress();
            NetAddress targetNetAddress = proxyMessagePayload.getTargetAddress();
            InetSocketAddress sourceAddress =
                    new InetSocketAddress(InetAddress.getByAddress(sourceNetAddress.getValue().getHost()),
                            sourceNetAddress.getValue().getPort());
            InetSocketAddress targetAddress =
                    new InetSocketAddress(InetAddress.getByAddress(targetNetAddress.getValue().getHost()),
                            targetNetAddress.getValue().getPort());
            DatagramDnsResponse dnsResponse =
                    new DatagramDnsResponse(sourceAddress, targetAddress, domainResolveResponse.getId());
            DefaultDnsQuestion dnsQuestion = new DefaultDnsQuestion(domainResolveResponse.getName(), DnsRecordType.A);
            dnsResponse.addRecord(DnsSection.QUESTION, dnsQuestion);
            domainResolveResponse.getAddresses().forEach(addressBytes -> {
                DefaultDnsRawRecord answerRecord =
                        new DefaultDnsRawRecord(domainResolveResponse.getName(), DnsRecordType.A, 120,
                                Unpooled.wrappedBuffer(addressBytes));
                dnsResponse.addRecord(DnsSection.ANSWER, answerRecord);
            });
            EmbeddedChannel generateDnsResponseBytesChannel = new EmbeddedChannel();
            generateDnsResponseBytesChannel.pipeline().addLast(new DatagramDnsResponseEncoder());
            generateDnsResponseBytesChannel.writeOutbound(dnsResponse);
            DatagramPacket dnsResponseUdpPacket = generateDnsResponseBytesChannel.readOutbound();
            short udpIpPacketId = (short) (Math.random() * 10000);
            UdpPacketBuilder remoteToDeviceUdpPacketBuilder = new UdpPacketBuilder();
            remoteToDeviceUdpPacketBuilder.data(dnsResponseUdpPacket.content().array());
            remoteToDeviceUdpPacketBuilder.destinationPort(sourceNetAddress.getValue().getPort());
            remoteToDeviceUdpPacketBuilder.sourcePort(targetNetAddress.getValue().getPort());
            UdpPacket remoteToDeviceUdpPacket = remoteToDeviceUdpPacketBuilder.build();
            try {
                this.ipPacketWriter.writeToDevice(udpIpPacketId, remoteToDeviceUdpPacket,
                        targetNetAddress.getValue().getHost(),
                        sourceNetAddress.getValue().getHost(), 0);
            } catch (IOException e) {
                Log.e(IpV4UdpPacketHandler.class.getName(), "Ip v4 udp handler have exception.", e);
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        Log.e(UdpProxyMessageHandler.class.getName(),
                "<<<<---- Udp channel exception happen on remote channel",
                cause);
    }
}
