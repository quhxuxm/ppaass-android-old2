package com.ppaass.agent.service.handler.udp;

import android.net.VpnService;
import android.util.Log;
import androidx.annotation.NonNull;
import com.ppaass.agent.protocol.general.ip.*;
import com.ppaass.agent.protocol.general.udp.UdpPacket;
import com.ppaass.agent.protocol.general.udp.UdpPacketBuilder;
import com.ppaass.agent.protocol.message.PpaassMessage;
import com.ppaass.agent.protocol.message.address.PpaassNetAddress;
import com.ppaass.agent.protocol.message.address.PpaassNetAddressType;
import com.ppaass.agent.protocol.message.address.PpaassNetAddressValue;
import com.ppaass.agent.protocol.message.encryption.PpaassMessagePayloadEncryption;
import com.ppaass.agent.protocol.message.encryption.PpaassMessagePayloadEncryptionType;
import com.ppaass.agent.service.IVpnConst;
import com.ppaass.agent.service.PpaassVpnNettyTcpChannelFactory;
import com.ppaass.agent.service.handler.IUdpIpPacketWriter;
import com.ppaass.agent.service.handler.PpaassMessageDecoder;
import com.ppaass.agent.service.handler.PpaassMessageEncoder;
import com.ppaass.agent.service.handler.PpaassMessageUtil;
import com.ppaass.agent.service.handler.dns.DnsEntry;
import com.ppaass.agent.service.handler.dns.DnsRepository;
import com.ppaass.agent.util.UUIDUtil;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.dns.*;
import io.netty.util.internal.StringUtil;
import org.apache.commons.validator.routines.DomainValidator;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

public class IpV4UdpPacketHandler implements IUdpIpPacketWriter {
    private final FileOutputStream rawDeviceOutputStream;
    private final VpnService vpnService;

    private Channel proxyChannel;

    public IpV4UdpPacketHandler(FileOutputStream rawDeviceOutputStream, VpnService vpnService) throws Exception {
        this.rawDeviceOutputStream = rawDeviceOutputStream;
        this.vpnService = vpnService;

        try {
            this.proxyChannel = this.initializeProxyChannel();
        } catch (Exception e) {
            Log.e(IpV4UdpPacketHandler.class.getName(), "Fail connect to proxy on udp handler.", e);
        }
    }

    private Channel initializeProxyChannel() throws Exception {
        NioEventLoopGroup proxyEventLoopGroup = new NioEventLoopGroup(3);
        Bootstrap proxyBootstrap = new Bootstrap();
        proxyBootstrap.group(proxyEventLoopGroup);
        proxyBootstrap.channelFactory(new PpaassVpnNettyTcpChannelFactory(this.vpnService));
        proxyBootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 20 * 1000);
        proxyBootstrap.option(ChannelOption.SO_TIMEOUT, 20 * 1000);
        proxyBootstrap.option(ChannelOption.SO_KEEPALIVE, true);
        proxyBootstrap.option(ChannelOption.AUTO_READ, true);
        proxyBootstrap.option(ChannelOption.AUTO_CLOSE, false);
        proxyBootstrap.option(ChannelOption.TCP_NODELAY, true);
        proxyBootstrap.option(ChannelOption.SO_REUSEADDR, true);
        proxyBootstrap.handler(new ChannelInitializer<NioSocketChannel>() {
            @Override
            protected void initChannel(@NonNull NioSocketChannel ch) {
                ch.pipeline().addLast(new PpaassMessageDecoder());
                ch.pipeline().addLast(new UdpProxyMessageHandler(IpV4UdpPacketHandler.this));
                ch.pipeline().addLast(new PpaassMessageEncoder(IVpnConst.COMPRESS));
            }
        });
        return proxyBootstrap.connect(IVpnConst.PPAASS_PROXY_IP, IVpnConst.PPAASS_PROXY_PORT).sync().channel();
    }

    private DatagramDnsQuery parseDnsQuery(UdpPacket udpPacket, IpV4Header ipHeader) {
        try {
            InetSocketAddress udpDestAddress = new InetSocketAddress(InetAddress.getByAddress(ipHeader.getDestinationAddress()), udpPacket.getHeader().getDestinationPort());
            InetSocketAddress udpSourceAddress = new InetSocketAddress(InetAddress.getByAddress(ipHeader.getSourceAddress()), udpPacket.getHeader().getSourcePort());
            DatagramPacket dnsPacket = new DatagramPacket(Unpooled.wrappedBuffer(udpPacket.getData()), udpDestAddress, udpSourceAddress);
            EmbeddedChannel parseDnsQueryChannel = new EmbeddedChannel();
            parseDnsQueryChannel.pipeline().addLast(new DatagramDnsQueryDecoder());
            parseDnsQueryChannel.writeInbound(dnsPacket);
            DatagramDnsQuery dnsQuery = parseDnsQueryChannel.readInbound();
            Log.v(IpV4UdpPacketHandler.class.getName(), "---->>>> DNS Query: " + dnsQuery);
            return dnsQuery;
        } catch (Exception e) {
            Log.e(IpV4UdpPacketHandler.class.getName(), "---->>>> Error happen when logging DNS Query, data:\n" + ByteBufUtil.prettyHexDump(Unpooled.wrappedBuffer(udpPacket.getData())) + "\n.", e);
            return null;
        }
    }

    public void handle(UdpPacket udpPacket, IpV4Header ipV4Header) throws Exception {
        DatagramDnsQuery dnsQuery = this.parseDnsQuery(udpPacket, ipV4Header);
        if (dnsQuery == null) {
            Log.e(IpV4UdpPacketHandler.class.getName(), "---->>>> Ignore udp packet because of invalid dns query: " + udpPacket + ", ip header: " + ipV4Header);
            return;
        }
        DefaultDnsQuestion dnsQuestion = dnsQuery.recordAt(DnsSection.QUESTION);
        if (dnsQuestion == null) {
            Log.e(IpV4UdpPacketHandler.class.getName(), "---->>>> Ignore udp packet because of no dns question: " + udpPacket + ", ip header: " + ipV4Header);
            return;
        }
        int dnsQueryId = dnsQuery.id();
        String dnsQueryName = dnsQuestion.name();
        if (StringUtil.isNullOrEmpty(dnsQueryName)) {
            return;
        }
        if (!DomainValidator.getInstance().isValid(dnsQueryName)) {
            return;
        }
        DnsEntry cachedDnsEntry = DnsRepository.INSTANCE.getAddress(dnsQueryName);
        if (cachedDnsEntry != null) {
            cachedDnsEntry.setLastAccessTime(System.currentTimeMillis());
            InetSocketAddress sourceAddress = new InetSocketAddress(InetAddress.getByAddress(ipV4Header.getSourceAddress()), udpPacket.getHeader().getSourcePort());
            InetSocketAddress targetAddress = new InetSocketAddress(InetAddress.getByAddress(ipV4Header.getDestinationAddress()), udpPacket.getHeader().getDestinationPort());
            DatagramDnsResponse cachedDnsResponse = new DatagramDnsResponse(sourceAddress, targetAddress, dnsQueryId);
            cachedDnsResponse.addRecord(DnsSection.QUESTION, dnsQuestion);
            cachedDnsEntry.getAddresses().forEach(inetAddress -> {
                DefaultDnsRawRecord answerRecord = new DefaultDnsRawRecord(dnsQueryName, DnsRecordType.A, 120, Unpooled.wrappedBuffer(inetAddress));
                cachedDnsResponse.addRecord(DnsSection.ANSWER, answerRecord);
            });
            var generateDnsResponseBytesChannel = new EmbeddedChannel();
            generateDnsResponseBytesChannel.pipeline().addLast(new DatagramDnsResponseEncoder());
            generateDnsResponseBytesChannel.writeOutbound(cachedDnsResponse);
            DatagramPacket dnsResponseUdpPacket = generateDnsResponseBytesChannel.readOutbound();
            short udpIpPacketId = (short) (Math.random() * 10000);
            UdpPacketBuilder remoteToDeviceUdpPacketBuilder = new UdpPacketBuilder();
            remoteToDeviceUdpPacketBuilder.data(dnsResponseUdpPacket.content().array());
            remoteToDeviceUdpPacketBuilder.destinationPort(udpPacket.getHeader().getSourcePort());
            remoteToDeviceUdpPacketBuilder.sourcePort(udpPacket.getHeader().getDestinationPort());
            UdpPacket remoteToDeviceUdpPacket = remoteToDeviceUdpPacketBuilder.build();
            try {
                this.writeToDevice(udpIpPacketId, remoteToDeviceUdpPacket, ipV4Header.getDestinationAddress(), ipV4Header.getSourceAddress(), 0);
            } catch (IOException e) {
                Log.e(IpV4UdpPacketHandler.class.getName(), "Ip v4 udp handler have exception.", e);
            }
            dnsQuery.release();
            return;
        }
        try {
            PpaassNetAddress srcAddress = new PpaassNetAddress(PpaassNetAddressType.IpV4, new PpaassNetAddressValue(ipV4Header.getSourceAddress(), udpPacket.getHeader().getSourcePort()));
            PpaassNetAddress destAddress = new PpaassNetAddress(PpaassNetAddressType.IpV4, new PpaassNetAddressValue(ipV4Header.getDestinationAddress(), udpPacket.getHeader().getDestinationPort()));
            PpaassMessage domainResolveMessage = PpaassMessageUtil.INSTANCE.generateDomainNameResolveRequestMessage(dnsQueryName, dnsQueryId, srcAddress, destAddress,

                    IVpnConst.PPAASS_PROXY_USER_TOKEN, new PpaassMessagePayloadEncryption(PpaassMessagePayloadEncryptionType.Aes, UUIDUtil.INSTANCE.generateUuidInBytes()));
            if (this.proxyChannel == null || !this.proxyChannel.isActive()) {
                this.proxyChannel = this.initializeProxyChannel();
            }
            this.proxyChannel.writeAndFlush(domainResolveMessage);
            Log.d(IpV4UdpPacketHandler.class.getName(), "---->>>> Send udp packet to remote: " + udpPacket + ", ip header: " + ipV4Header);
        } catch (Exception e) {
            Log.e(IpV4UdpPacketHandler.class.getName(), "Ip v4 udp handler have exception.", e);
            if (this.proxyChannel != null) {
                this.proxyChannel.close();
            }
        }
    }

    @Override
    public void writeToDevice(short udpIpPacketId, UdpPacket udpPacket, byte[] sourceHost, byte[] destinationHost, int udpResponseDataOffset) throws IOException {
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
        Log.v(IpV4UdpPacketHandler.class.getName(), "<<<<<<<< Write udp ip packet to device: " + ipPacket);
        ByteBuffer ipPacketBytes = IpPacketWriter.INSTANCE.write(ipPacket);
        byte[] bytesWriteToDevice = new byte[ipPacketBytes.remaining()];
        ipPacketBytes.get(bytesWriteToDevice);
        ipPacketBytes.clear();
//        synchronized (this.rawDeviceOutputStream) {
        this.rawDeviceOutputStream.write(bytesWriteToDevice);
        this.rawDeviceOutputStream.flush();
//        }
    }
}
