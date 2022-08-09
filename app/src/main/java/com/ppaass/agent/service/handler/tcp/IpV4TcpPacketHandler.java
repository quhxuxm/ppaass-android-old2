package com.ppaass.agent.service.handler.tcp;

import android.net.VpnService;
import android.util.Log;
import androidx.annotation.NonNull;
import com.ppaass.agent.protocol.general.ip.*;
import com.ppaass.agent.protocol.general.tcp.TcpHeader;
import com.ppaass.agent.protocol.general.tcp.TcpPacket;
import com.ppaass.agent.protocol.general.tcp.TcpPacketReader;
import com.ppaass.agent.protocol.general.tcp.TcpPacketWriter;
import com.ppaass.agent.service.PpaassVpnTcpChannelFactory;
import com.ppaass.agent.service.handler.TcpIpPacketWriter;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class IpV4TcpPacketHandler implements TcpIpPacketWriter {
    private static final Random RANDOM = new Random();
    private final OutputStream rawDeviceOutputStream;
    private final Map<TcpConnectionRepositoryKey, TcpConnection> connectionRepository;
    private final VpnService vpnService;
    private final ExecutorService connectionThreadPool;
    private int ipIdentifier;
    private final Bootstrap remoteBootstrap;

    public IpV4TcpPacketHandler(OutputStream rawDeviceOutputStream, VpnService vpnService) {
        this.rawDeviceOutputStream = rawDeviceOutputStream;
        this.vpnService = vpnService;
        this.connectionRepository = new ConcurrentHashMap<>();
        this.connectionThreadPool = Executors.newFixedThreadPool(32);
        this.ipIdentifier = RANDOM.nextInt(Short.MAX_VALUE * 2 + 2);
        this.remoteBootstrap = this.createBootstrap();
    }

    private Bootstrap createBootstrap() {
        Bootstrap result = new Bootstrap();
        result.group(new NioEventLoopGroup(32));
        result.channelFactory(new PpaassVpnTcpChannelFactory(this.vpnService));
        result.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30000);
        result.option(ChannelOption.SO_TIMEOUT, 30000);
        result.option(ChannelOption.SO_KEEPALIVE, true);
        result.option(ChannelOption.AUTO_READ, true);
        result.option(ChannelOption.AUTO_CLOSE, true);
        result.option(ChannelOption.TCP_NODELAY, true);
        result.option(ChannelOption.SO_REUSEADDR, true);
        result.handler(new ChannelInitializer<NioSocketChannel>() {
            @Override
            protected void initChannel(@NonNull NioSocketChannel ch) throws Exception {
                ch.pipeline().addLast(new TcpConnectionRelayRemoteHandler());
            }
        });
        return result;
    }

    private TcpConnection retrieveTcpConnection(TcpConnectionRepositoryKey repositoryKey, TcpPacket tcpPacket) {
        return this.connectionRepository.computeIfAbsent(repositoryKey, key -> {
            TcpConnection result = null;
            try {
                result = new TcpConnection(repositoryKey, this, connectionRepository,
                        20000, 20000,
                        this.remoteBootstrap);
            } catch (IOException e) {
                Log.e(IpV4TcpPacketHandler.class.getName(),
                        ">>>>>>>> Fail to create tcp connection: " + result + ", tcp packet: " + tcpPacket, e);
                throw new IllegalStateException("Fail to create tcp connection.", e);
            }
            result.start();
            this.connectionThreadPool.execute(result);
            Log.d(IpV4TcpPacketHandler.class.getName(),
                    ">>>>>>>> Create tcp connection: " + result + ", tcp packet: " + tcpPacket);
            return result;
        });
    }

    private int generateChecksumToVerify(TcpPacket tcpPacket, IpV4Header ipV4Header) {
        ByteBuffer tcpPacketBuf = TcpPacketWriter.INSTANCE.write(tcpPacket, ipV4Header);
        byte[] tcpPacketBytes = new byte[tcpPacketBuf.remaining()];
        tcpPacketBuf.get(tcpPacketBytes);
        TcpPacket tcpPacketToVerify = TcpPacketReader.INSTANCE.parse(tcpPacketBytes);
        return tcpPacketToVerify.getHeader().getChecksum();
    }

    public void handle(TcpPacket tcpPacket, IpV4Header ipV4Header) throws Exception {
        int generatedChecksumToVerify = this.generateChecksumToVerify(tcpPacket, ipV4Header);
        if (generatedChecksumToVerify != tcpPacket.getHeader().getChecksum()) {
            Log.e(IpV4TcpPacketHandler.class.getName(),
                    ">>>>>>>> Fail to verify checksum for tcp packet: " + tcpPacket +
                            ", ip header: " +
                            ipV4Header + ", generated check sum: " + generatedChecksumToVerify +
                            ", incoming checksum: " + tcpPacket.getHeader().getChecksum());
            return;
        }
        TcpHeader tcpHeader = tcpPacket.getHeader();
        int sourcePort = tcpHeader.getSourcePort();
        int destinationPort = tcpHeader.getDestinationPort();
        byte[] sourceAddress = ipV4Header.getSourceAddress();
        byte[] destinationAddress = ipV4Header.getDestinationAddress();
        TcpConnectionRepositoryKey tcpConnectionRepositoryKey =
                new TcpConnectionRepositoryKey(sourcePort, destinationPort, sourceAddress, destinationAddress);
        TcpConnection tcpConnection = this.retrieveTcpConnection(tcpConnectionRepositoryKey, tcpPacket);
        tcpConnection.onDeviceInbound(tcpPacket);
        Log.v(IpV4TcpPacketHandler.class.getName(),
                ">>>>>>>> Do inbound for tcp connection: " + tcpConnection + ", incoming tcp packet: " + tcpPacket +
                        ", ip header: " +
                        ipV4Header);
    }

    @Override
    public void write(TcpConnection tcpConnection, TcpPacket tcpPacket) throws IOException {
        IpPacketBuilder ipPacketBuilder = new IpPacketBuilder();
        IpV4HeaderBuilder ipV4HeaderBuilder = new IpV4HeaderBuilder();
        int identification = this.ipIdentifier;
        this.ipIdentifier++;
        ipV4HeaderBuilder.identification(identification);
        ipV4HeaderBuilder.destinationAddress(tcpConnection.getRepositoryKey().getSourceAddress());
        ipV4HeaderBuilder.sourceAddress(tcpConnection.getRepositoryKey().getDestinationAddress());
        ipV4HeaderBuilder.protocol(IpDataProtocol.TCP);
        ipV4HeaderBuilder.ttl(64);
        ipV4HeaderBuilder.flags(new IpFlags(false, false));
        ipPacketBuilder.header(ipV4HeaderBuilder.build());
        ipPacketBuilder.data(tcpPacket);
        IpPacket ipPacket = ipPacketBuilder.build();
        ByteBuffer ipPacketBytes = IpPacketWriter.INSTANCE.write(ipPacket);
        Log.v(IpV4TcpPacketHandler.class.getName(),
                "<<<<<<<< Write ip packet to device, current connection:  " + tcpConnection +
                        ", output ip packet: " + ipPacket);
        byte[] bytesWriteToDevice = new byte[ipPacketBytes.remaining()];
        ipPacketBytes.get(bytesWriteToDevice);
        ipPacketBytes.clear();
        synchronized (this.rawDeviceOutputStream) {
            this.rawDeviceOutputStream.write(bytesWriteToDevice);
            this.rawDeviceOutputStream.flush();
        }
    }
}
