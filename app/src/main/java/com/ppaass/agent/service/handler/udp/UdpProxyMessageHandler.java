package com.ppaass.agent.service.handler.udp;

import android.util.Log;
import com.ppaass.agent.protocol.general.udp.UdpPacket;
import com.ppaass.agent.protocol.general.udp.UdpPacketBuilder;
import com.ppaass.agent.protocol.message.*;
import com.ppaass.agent.service.IVpnConst;
import com.ppaass.agent.service.handler.IUdpIpPacketWriter;
import com.ppaass.agent.service.handler.PpaassMessageUtil;
import com.ppaass.agent.util.UUIDUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.dns.*;
import io.netty.util.concurrent.Promise;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

public class UdpProxyMessageHandler extends SimpleChannelInboundHandler<Message> {
    private final IUdpIpPacketWriter udpIpPacketWriter;
    private final Promise<Channel> udpAssociateChannelPromise;

    public UdpProxyMessageHandler(IUdpIpPacketWriter udpIpPacketWriter,
                                  Promise<Channel> udpAssociateChannelPromise) {
        this.udpIpPacketWriter = udpIpPacketWriter;
        this.udpAssociateChannelPromise = udpAssociateChannelPromise;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        Message udpAssociateMessage = new Message();
        udpAssociateMessage.setId(UUIDUtil.INSTANCE.generateUuid());
        udpAssociateMessage.setUserToken(IVpnConst.PPAASS_USER_TOKEN);
        udpAssociateMessage.setPayloadEncryptionType(PayloadEncryptionType.Aes);
        udpAssociateMessage.setPayloadEncryptionToken(UUIDUtil.INSTANCE.generateUuidInBytes());
        AgentMessagePayload udpAssociateMessagePayload = new AgentMessagePayload();
        udpAssociateMessagePayload.setPayloadType(AgentMessagePayloadType.UdpAssociate);
        udpAssociateMessage.setPayload(
                PpaassMessageUtil.INSTANCE.generateAgentMessagePayloadBytes(
                        udpAssociateMessagePayload));
        ctx.channel().writeAndFlush(udpAssociateMessage);
        Log.d(UdpProxyMessageHandler.class.getName(),
                "<<<<---- Udp channel write [UdpAssociate] to proxy");
    }

    private void logDnsAnswer(DefaultDnsRawRecord answer) throws UnknownHostException {
        try {
            ByteBuf answerContent = answer.content();
            String answerContentAsString = null;
            if (answerContent.isReadable()) {
                byte[] answerContentBytes = new byte[answerContent.readableBytes()];
                answerContent.getBytes(0, answerContentBytes);
                if (answer.type().equals(DnsRecordType.A)) {
                    Log.v(IpV4UdpPacketHandler.class.getName(),
                            "<<<<---- #### DNS Answer: " + answer + " ||| content: " +
                                    InetAddress.getByAddress(answerContentBytes));
                    return;
                }
                if (answer.type().equals(DnsRecordType.CNAME)) {
                    Log.v(IpV4UdpPacketHandler.class.getName(),
                            "<<<<---- #### DNS Answer: " + answer + " ||| content: " +
                                    new String(answerContentBytes, StandardCharsets.US_ASCII));
                    return;
                }
                Log.v(IpV4UdpPacketHandler.class.getName(),
                        "<<<<---- #### DNS Answer: " + answer);
            }
        } catch (Exception e) {
            Log.e(IpV4UdpPacketHandler.class.getName(), "<<<<----  Error happen when logging DNS Answer.", e);
        }
    }

    private void logDnsResponse(ProxyMessagePayload proxyMessagePayload) throws UnknownHostException {
        NetAddress proxyMessageTargetAddress = proxyMessagePayload.getTargetAddress();
        NetAddress proxyMessageSourceAddress = proxyMessagePayload.getSourceAddress();
        InetSocketAddress udpDestAddress =
                new InetSocketAddress(InetAddress.getByAddress(proxyMessageTargetAddress.getHost()),
                        proxyMessageTargetAddress.getPort());
        InetSocketAddress udpSourceAddress =
                new InetSocketAddress(InetAddress.getByAddress(proxyMessageSourceAddress.getHost()),
                        proxyMessageSourceAddress.getPort());
        DatagramPacket dnsPacket =
                new DatagramPacket(Unpooled.wrappedBuffer(proxyMessagePayload.getData()), udpSourceAddress,
                        udpDestAddress
                );
        EmbeddedChannel logChannel = new EmbeddedChannel();
        logChannel.pipeline().addLast(new DatagramDnsResponseDecoder());
        logChannel.writeInbound(dnsPacket);
        DnsResponse dnsResponse = logChannel.readInbound();
        Log.v(IpV4UdpPacketHandler.class.getName(), "<<<<---- DNS Response: " + dnsResponse);
        int numberOfAnswer = dnsResponse.count(DnsSection.ANSWER);
        for (int i = 0; i < numberOfAnswer; i++) {
            DefaultDnsRawRecord answer = dnsResponse.recordAt(DnsSection.ANSWER, i);
            logDnsAnswer(answer);
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Message proxyMessage) throws IOException {
        //Relay remote data to device and use mss as the transfer unit
        byte[] proxyMessagePayloadBytes = proxyMessage.getPayload();
        ProxyMessagePayload proxyMessagePayload =
                PpaassMessageUtil.INSTANCE.parseProxyMessagePayloadBytes(proxyMessagePayloadBytes);
        if (ProxyMessagePayloadType.UdpAssociateSuccess == proxyMessagePayload.getPayloadType()) {
            this.udpAssociateChannelPromise.setSuccess(ctx.channel());
            Log.d(UdpProxyMessageHandler.class.getName(),
                    "<<<<---- Udp associate success, proxy message: " + proxyMessage);
            return;
        }
        if (ProxyMessagePayloadType.UdpAssociateFail == proxyMessagePayload.getPayloadType()) {
            this.udpAssociateChannelPromise.setFailure(
                    new IllegalStateException("Proxy udp associate fail"));
            Log.e(UdpProxyMessageHandler.class.getName(),
                    "<<<<---- Udp associate fail, proxy message: " + proxyMessage);
            ctx.channel().close();
            return;
        }
        if (ProxyMessagePayloadType.UdpData == proxyMessagePayload.getPayloadType()) {
            this.logDnsResponse(proxyMessagePayload);
            NetAddress udpTargetAddress = proxyMessagePayload.getTargetAddress();
            NetAddress udpSourceAddress = proxyMessagePayload.getSourceAddress();
            ByteBuf remoteToDeviceUdpPacketContent = Unpooled.wrappedBuffer(proxyMessagePayload.getData());
            Log.v(UdpProxyMessageHandler.class.getName(),
                    "<<<<---- Udp content received, source address: [" + udpSourceAddress +
                            "], target address: [" +
                            udpTargetAddress + "], udp packet content:\n\n" +
                            ByteBufUtil.prettyHexDump(remoteToDeviceUdpPacketContent) + "\n\n");
            int udpResponseDataOffset = 0;
            short udpIpPacketId = (short) (Math.random() * 10000);
            while (remoteToDeviceUdpPacketContent.isReadable()) {
                int contentLength = Math.min(IVpnConst.MTU - IVpnConst.UDP_HEADER_LENGTH,
                        remoteToDeviceUdpPacketContent.readableBytes());
                byte[] packetFromRemoteToDeviceContent =
                        new byte[contentLength];
                remoteToDeviceUdpPacketContent.readBytes(packetFromRemoteToDeviceContent);
                UdpPacketBuilder remoteToDeviceUdpPacketBuilder = new UdpPacketBuilder();
                remoteToDeviceUdpPacketBuilder.data(packetFromRemoteToDeviceContent);
                remoteToDeviceUdpPacketBuilder.destinationPort(udpSourceAddress.getPort());
                remoteToDeviceUdpPacketBuilder.sourcePort(udpTargetAddress.getPort());
                UdpPacket remoteToDeviceUdpPacket = remoteToDeviceUdpPacketBuilder.build();
                this.udpIpPacketWriter.writeToDevice(udpIpPacketId, remoteToDeviceUdpPacket,
                        udpTargetAddress.getHost(),
                        udpSourceAddress.getHost(), udpResponseDataOffset);
                udpResponseDataOffset += contentLength;
            }
            return;
        }
        if (ProxyMessagePayloadType.HeartbeatSuccess == proxyMessagePayload.getPayloadType()) {
            return;
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        Log.e(UdpProxyMessageHandler.class.getName(),
                "<<<<---- Udp channel exception happen on remote channel",
                cause);
        ctx.channel().close();
    }
}
