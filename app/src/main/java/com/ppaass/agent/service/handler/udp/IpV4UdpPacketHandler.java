package com.ppaass.agent.service.handler.udp;

import android.net.VpnService;
import android.util.Log;
import androidx.annotation.NonNull;
import com.ppaass.agent.protocol.general.ip.IpV4Header;
import com.ppaass.agent.protocol.general.udp.UdpPacket;
import com.ppaass.agent.service.IVpnConst;
import com.ppaass.agent.service.PpaassVpnUdpChannelFactory;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.AttributeKey;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;

public class IpV4UdpPacketHandler {
    private final OutputStream rawDeviceOutputStream;
    private final VpnService vpnService;
    private final Channel udpChannel;

    public IpV4UdpPacketHandler(OutputStream rawDeviceOutputStream, VpnService vpnService)
            throws Exception {
        this.rawDeviceOutputStream = rawDeviceOutputStream;
        this.vpnService = vpnService;
        Bootstrap udpBootstrap = this.createBootstrap();
        try {
            this.udpChannel = udpBootstrap.bind(0).sync().channel();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private Bootstrap createBootstrap() {
        Bootstrap result = new Bootstrap();
        result.group(new NioEventLoopGroup(64));
        result.channelFactory(new PpaassVpnUdpChannelFactory(this.vpnService));
        result.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30000);
        result.option(ChannelOption.SO_TIMEOUT, 30000);
        result.option(ChannelOption.SO_KEEPALIVE, true);
        result.option(ChannelOption.AUTO_READ, true);
        result.option(ChannelOption.AUTO_CLOSE, true);
        result.option(ChannelOption.TCP_NODELAY, true);
        result.option(ChannelOption.SO_REUSEADDR, true);
        result.handler(new ChannelInitializer<DatagramChannel>() {
            @Override
            protected void initChannel(@NonNull DatagramChannel ch) throws Exception {
                ch.pipeline().addLast(new IpV4UdpChannelHandler(rawDeviceOutputStream));
            }
        });
        return result;
    }

    public void handle(UdpPacket udpPacket, IpV4Header ipV4Header) throws InterruptedException {
        try {
            Log.d(IpV4UdpPacketHandler.class.getName(), udpPacket.toString());
            InetAddress destinationAddress =
                    InetAddress.getByAddress(ipV4Header.getDestinationAddress());
            int destinationPort = udpPacket.getHeader().getDestinationPort();
            InetSocketAddress deviceToRemoteDestinationAddress =
                    new InetSocketAddress(destinationAddress, destinationPort);
            ByteBuf udpPacketData = Unpooled.wrappedBuffer(udpPacket.getData());
            DatagramPacket datagramPacket = new DatagramPacket(udpPacketData,
                    deviceToRemoteDestinationAddress);
            AttributeKey<byte[]> udpSourceAddressKey = AttributeKey.valueOf(IVpnConst.UDP_SOURCE_ADDR);
            udpChannel.attr(udpSourceAddressKey).set(ipV4Header.getSourceAddress());
            AttributeKey<Integer> udpSourcePortKey = AttributeKey.valueOf(IVpnConst.UDP_SOURCE_PORT);
            udpChannel.attr(udpSourcePortKey).set(udpPacket.getHeader().getSourcePort());
            udpChannel.writeAndFlush(datagramPacket).addListener(future -> {
                ByteBuf udpPacketDataForLog = Unpooled.wrappedBuffer(udpPacket.getData());
                if (future.isSuccess()) {
                    Log.d(IpV4UdpPacketHandler.class.getName(),
                            "Success to send udp packet to remote: " + udpPacket + ", destination: " +
                                    deviceToRemoteDestinationAddress + ", udp content going to send:\n\n" +
                                    ByteBufUtil.prettyHexDump(udpPacketDataForLog) +
                                    "\n\n");
                    return;
                }
                Log.d(IpV4UdpPacketHandler.class.getName(),
                        "Fail to send udp packet to remote: " + udpPacket + ", destination: " +
                                deviceToRemoteDestinationAddress + ", udp content going to send:\n\n" +
                                ByteBufUtil.prettyHexDump(udpPacketDataForLog) +
                                "\n\n", future.cause());
            });
        } catch (Exception e) {
            Log.e(IpV4UdpPacketHandler.class.getName(),
                    "Ip v4 udp handler have exception.", e);
        }
    }
}
