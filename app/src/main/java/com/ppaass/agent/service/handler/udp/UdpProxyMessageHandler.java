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
import io.netty.util.concurrent.Promise;

import java.io.IOException;

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
        NetAddress fakeSourceAddress = new NetAddress();
        fakeSourceAddress.setHost(new byte[]{1, 1, 1, 1});
        fakeSourceAddress.setPort((short) 1);
        fakeSourceAddress.setType(NetAddressType.IpV4);
        udpAssociateMessagePayload.setSourceAddress(fakeSourceAddress);
        udpAssociateMessage.setPayload(
                PpaassMessageUtil.INSTANCE.generateAgentMessagePayloadBytes(
                        udpAssociateMessagePayload));
        ctx.channel().writeAndFlush(udpAssociateMessage);
        Log.d(UdpProxyMessageHandler.class.getName(),
                "<<<<---- Udp channel write [UdpAssociate] to proxy");
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
                    "<<<<---- Udp associate success");
            return;
        }
        if (ProxyMessagePayloadType.UdpAssociateFail == proxyMessagePayload.getPayloadType()) {
            this.udpAssociateChannelPromise.setFailure(
                    new IllegalStateException("Proxy udp associate fail"));
            Log.e(UdpProxyMessageHandler.class.getName(),
                    "<<<<---- Udp associate fail, source address");
            return;
        }
        if (ProxyMessagePayloadType.UdpData == proxyMessagePayload.getPayloadType()) {
            NetAddress udpDestinationAddress = proxyMessagePayload.getTargetAddress();
            NetAddress udpSourceAddress = proxyMessagePayload.getSourceAddress();
            ByteBuf remoteToDeviceUdpPacketContent = Unpooled.wrappedBuffer(proxyMessagePayload.getData());
            Log.v(UdpProxyMessageHandler.class.getName(),
                    "Udp content received, source address: [" + udpSourceAddress + "], destination address: [" +
                            udpDestinationAddress + "], udp packet content:\n\n" +
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
                remoteToDeviceUdpPacketBuilder.sourcePort(udpDestinationAddress.getPort());
                UdpPacket remoteToDeviceUdpPacket = remoteToDeviceUdpPacketBuilder.build();
                this.udpIpPacketWriter.writeToDevice(udpIpPacketId, remoteToDeviceUdpPacket,
                        udpDestinationAddress.getHost(),
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
    }
}
