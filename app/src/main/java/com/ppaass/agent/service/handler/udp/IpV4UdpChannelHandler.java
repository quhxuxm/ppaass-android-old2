package com.ppaass.agent.service.handler.udp;

import android.util.Log;
import com.ppaass.agent.protocol.general.ip.*;
import com.ppaass.agent.protocol.general.udp.UdpPacket;
import com.ppaass.agent.protocol.general.udp.UdpPacketBuilder;
import com.ppaass.agent.service.IVpnConst;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.AttributeKey;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

public class IpV4UdpChannelHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    private final OutputStream rawDeviceOutputStream;

    public IpV4UdpChannelHandler(OutputStream rawDeviceOutputStream) {
        this.rawDeviceOutputStream = rawDeviceOutputStream;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) throws Exception {
        short udpResponseId = (short) (Math.random() * 10000);
        int udpResponseDataOffset = 0;
        AttributeKey<Integer> udpSourcePortKey = AttributeKey.valueOf(IVpnConst.UDP_SOURCE_PORT);
        Integer udpSourcePort = ctx.channel().attr(udpSourcePortKey).get();
        if (udpSourcePort == null) {
            Log.e(IpV4UdpChannelHandler.class.getName(), "Fail to get udp source port from channel.");
            return;
        }
        AttributeKey<byte[]> udpSourceAddrKey = AttributeKey.valueOf(IVpnConst.UDP_SOURCE_ADDR);
        byte[] udpSourceAddr = ctx.channel().attr(udpSourceAddrKey).get();
        if (udpSourceAddr == null) {
            Log.e(IpV4UdpChannelHandler.class.getName(), "Fail to get udp source address from channel.");
            return;
        }
        InetSocketAddress udpDstAddress = msg.sender();
        ByteBuf remoteToDeviceUdpPacketContent = msg.content();
        Log.d(IpV4UdpChannelHandler.class.getName(),
                "Success receive remote udp packet [" + udpResponseId + "], destination: " +
                        udpDstAddress);
        Log.v(IpV4UdpChannelHandler.class.getName(),
                "Udp content received:\n\n" +
                        ByteBufUtil.prettyHexDump(remoteToDeviceUdpPacketContent) + "\n\n");
        while (remoteToDeviceUdpPacketContent.isReadable()) {
            int contentLength = Math.min(IVpnConst.MTU - IVpnConst.UDP_HEADER_LENGTH,
                    remoteToDeviceUdpPacketContent.readableBytes());
            byte[] packetFromRemoteToDeviceContent =
                    new byte[contentLength];
            remoteToDeviceUdpPacketContent.readBytes(packetFromRemoteToDeviceContent);
            UdpPacketBuilder remoteToDeviceUdpPacketBuilder = new UdpPacketBuilder();
            remoteToDeviceUdpPacketBuilder.data(packetFromRemoteToDeviceContent);
            remoteToDeviceUdpPacketBuilder.destinationPort(udpSourcePort);
            remoteToDeviceUdpPacketBuilder.sourcePort(udpDstAddress.getPort());
            UdpPacket remoteToDeviceUdpPacket = remoteToDeviceUdpPacketBuilder.build();
            IpPacketBuilder ipPacketBuilder = new IpPacketBuilder();
            ipPacketBuilder.data(remoteToDeviceUdpPacket);
            IpV4HeaderBuilder ipV4HeaderBuilder = new IpV4HeaderBuilder();
            ipV4HeaderBuilder.destinationAddress(udpSourceAddr);
            ipV4HeaderBuilder.sourceAddress(udpDstAddress.getAddress().getAddress());
            ipV4HeaderBuilder.protocol(IpDataProtocol.UDP);
            ipV4HeaderBuilder.identification(udpResponseId);
            ipV4HeaderBuilder.fragmentOffset(udpResponseDataOffset);
            udpResponseDataOffset += contentLength;
            ipPacketBuilder.header(ipV4HeaderBuilder.build());
            IpPacket ipPacket = ipPacketBuilder.build();
            ByteBuffer ipPacketBytes = IpPacketWriter.INSTANCE.write(ipPacket);
            byte[] bytesWriteToDevice = new byte[ipPacketBytes.remaining()];
            ipPacketBytes.get(bytesWriteToDevice);
            ipPacketBytes.clear();
            this.rawDeviceOutputStream.write(bytesWriteToDevice);
            this.rawDeviceOutputStream.flush();
        }
    }
}
