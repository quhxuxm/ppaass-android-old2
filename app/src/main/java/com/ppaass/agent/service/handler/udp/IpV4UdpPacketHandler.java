package com.ppaass.agent.service.handler.udp;

import android.net.VpnService;
import android.util.Log;
import androidx.annotation.NonNull;
import com.ppaass.agent.protocol.general.ip.*;
import com.ppaass.agent.protocol.general.udp.UdpPacket;
import com.ppaass.agent.protocol.message.*;
import com.ppaass.agent.service.IVpnConst;
import com.ppaass.agent.service.PpaassVpnTcpChannelFactory;
import com.ppaass.agent.service.handler.IUdpIpPacketWriter;
import com.ppaass.agent.service.handler.PpaassMessageDecoder;
import com.ppaass.agent.service.handler.PpaassMessageEncoder;
import com.ppaass.agent.service.handler.PpaassMessageUtil;
import com.ppaass.agent.util.UUIDUtil;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.Promise;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

public class IpV4UdpPacketHandler implements IUdpIpPacketWriter {
    private final FileOutputStream rawDeviceOutputStream;
    private final VpnService vpnService;
    private final Channel proxyChannel;

    public IpV4UdpPacketHandler(FileOutputStream rawDeviceOutputStream, VpnService vpnService)
            throws Exception {
        this.rawDeviceOutputStream = rawDeviceOutputStream;
        this.vpnService = vpnService;
        NioEventLoopGroup udpNioEventLoopGroup = new NioEventLoopGroup(8);
        Promise<Channel> udpAssociatePromise = new DefaultPromise<>(udpNioEventLoopGroup.next());
        Bootstrap udpBootstrap = this.createBootstrap(udpNioEventLoopGroup, udpAssociatePromise);
        InetSocketAddress proxyAddress =
                new InetSocketAddress(InetAddress.getByName(IVpnConst.PPAASS_PROXY_IP), IVpnConst.PPAASS_PROXY_PORT);
        udpBootstrap.connect(proxyAddress);
        this.proxyChannel = udpAssociatePromise.get();
    }

    private Bootstrap createBootstrap(NioEventLoopGroup udpNioEventLoopGroup,
                                      Promise<Channel> udpAssociateChannelPromise) {
        Bootstrap result = new Bootstrap();
        result.group(udpNioEventLoopGroup);
        result.channelFactory(new PpaassVpnTcpChannelFactory(this.vpnService));
        result.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 120 * 1000);
        result.option(ChannelOption.SO_TIMEOUT, 120 * 1000);
        result.option(ChannelOption.SO_KEEPALIVE, false);
        result.option(ChannelOption.AUTO_READ, true);
        result.option(ChannelOption.AUTO_CLOSE, true);
        result.option(ChannelOption.TCP_NODELAY, true);
        result.option(ChannelOption.SO_REUSEADDR, true);
        result.handler(new ChannelInitializer<NioSocketChannel>() {
            @Override
            protected void initChannel(@NonNull NioSocketChannel ch) {
                ch.pipeline().addLast(new PpaassMessageDecoder());
                ch.pipeline()
                        .addLast(new UdpProxyMessageHandler(IpV4UdpPacketHandler.this, udpAssociateChannelPromise));
                ch.pipeline().addLast(new PpaassMessageEncoder(false));
            }
        });
        return result;
    }

    public void handle(UdpPacket udpPacket, IpV4Header ipV4Header) throws InterruptedException {
        try {
            NetAddress sourceAddress = new NetAddress();
            sourceAddress.setHost(ipV4Header.getSourceAddress());
            sourceAddress.setPort(udpPacket.getHeader().getSourcePort());
            sourceAddress.setType(NetAddressType.IpV4);
            NetAddress targetAddress = new NetAddress();
            targetAddress.setType(NetAddressType.IpV4);
            targetAddress.setHost(ipV4Header.getDestinationAddress());
            targetAddress.setPort(udpPacket.getHeader().getDestinationPort());
            Message udpDataMessage = new Message();
            udpDataMessage.setId(UUIDUtil.INSTANCE.generateUuid());
            udpDataMessage.setUserToken(IVpnConst.PPAASS_USER_TOKEN);
            udpDataMessage.setPayloadEncryptionType(PayloadEncryptionType.Aes);
            udpDataMessage.setPayloadEncryptionToken(UUIDUtil.INSTANCE.generateUuidInBytes());
            AgentMessagePayload udpDataMessagePayload = new AgentMessagePayload();
            udpDataMessagePayload.setPayloadType(AgentMessagePayloadType.UdpData);
            udpDataMessagePayload.setSourceAddress(sourceAddress);
            udpDataMessagePayload.setTargetAddress(targetAddress);
            udpDataMessagePayload.setData(udpPacket.getData());
            udpDataMessage.setPayload(
                    PpaassMessageUtil.INSTANCE.generateAgentMessagePayloadBytes(
                            udpDataMessagePayload));
            this.proxyChannel.writeAndFlush(udpDataMessage).addListener(future -> {
                ByteBuf udpPacketDataForLog = Unpooled.wrappedBuffer(udpPacket.getData());
                if (future.isSuccess()) {
                    Log.d(IpV4UdpPacketHandler.class.getName(),
                            "Success to send udp packet to remote: " + udpPacket + ", ip header: " + ipV4Header);
                    Log.v(IpV4UdpPacketHandler.class.getName(), "Udp content going to send[SUCCESS]:\n\n" +
                            ByteBufUtil.prettyHexDump(udpPacketDataForLog) +
                            "\n\n");
                    return;
                }
                Log.d(IpV4UdpPacketHandler.class.getName(),
                        "Fail to send udp packet to remote: " + udpPacket + ", ip header: " + ipV4Header);
                Log.v(IpV4UdpPacketHandler.class.getName(), "Udp content going to send[FAIL]:\n\n" +
                        ByteBufUtil.prettyHexDump(udpPacketDataForLog) +
                        "\n\n", future.cause());
            });
        } catch (Exception e) {
            Log.e(IpV4UdpPacketHandler.class.getName(),
                    "Ip v4 udp handler have exception.", e);
        }
    }

    @Override
    public void writeToDevice(short udpIpPacketId, UdpPacket udpPacket, byte[] sourceHost, byte[] destinationHost,
                              int udpResponseDataOffset)
            throws IOException {
        IpPacketBuilder ipPacketBuilder = new IpPacketBuilder();
        ipPacketBuilder.data(udpPacket);
        IpV4HeaderBuilder ipV4HeaderBuilder = new IpV4HeaderBuilder();
        ipV4HeaderBuilder.destinationAddress(destinationHost);
        ipV4HeaderBuilder.sourceAddress(sourceHost);
        ipV4HeaderBuilder.protocol(IpDataProtocol.UDP);
        ipV4HeaderBuilder.identification(udpIpPacketId);
        ipV4HeaderBuilder.fragmentOffset(udpResponseDataOffset);
        ipPacketBuilder.header(ipV4HeaderBuilder.build());
        IpPacket ipPacket = ipPacketBuilder.build();
        Log.v(IpV4UdpPacketHandler.class.getName(), "Write udp ip packet to device: " + ipPacket);
        ByteBuffer ipPacketBytes = IpPacketWriter.INSTANCE.write(ipPacket);
        byte[] bytesWriteToDevice = new byte[ipPacketBytes.remaining()];
        ipPacketBytes.get(bytesWriteToDevice);
        ipPacketBytes.clear();
        this.rawDeviceOutputStream.write(bytesWriteToDevice);
        this.rawDeviceOutputStream.flush();
    }
}
