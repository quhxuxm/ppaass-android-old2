package com.ppaass.agent.service.handler.tcp;

import android.net.VpnService;
import android.util.Log;
import com.ppaass.agent.protocol.general.ip.*;
import com.ppaass.agent.protocol.general.tcp.TcpHeader;
import com.ppaass.agent.protocol.general.tcp.TcpPacket;
import com.ppaass.agent.service.handler.TcpIpPacketWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class IpV4TcpPacketHandler implements TcpIpPacketWriter {
    private final OutputStream rawDeviceOutputStream;
    private final int writeBufferSize;
    private final Map<TcpConnectionRepositoryKey, TcpConnection> connectionRepository;
    private final VpnService vpnService;
    private final ExecutorService connectionThreadPool;

    public IpV4TcpPacketHandler(OutputStream rawDeviceOutputStream, int writeBufferSize, VpnService vpnService) {
        this.rawDeviceOutputStream = rawDeviceOutputStream;
        this.writeBufferSize = writeBufferSize;
        this.vpnService = vpnService;
        this.connectionRepository = new HashMap<>();
        this.connectionThreadPool = Executors.newFixedThreadPool(128);
    }

    public void handle(TcpPacket tcpPacket, IpV4Header ipV4Header) throws Exception {
        Log.d(IpV4TcpPacketHandler.class.getName(), tcpPacket.toString());
        TcpHeader tcpHeader = tcpPacket.getHeader();
        int sourcePort = tcpHeader.getSourcePort();
        int destinationPort = tcpHeader.getDestinationPort();
        byte[] sourceAddress = ipV4Header.getSourceAddress();
        byte[] destinationAddress = ipV4Header.getDestinationAddress();
        TcpConnectionRepositoryKey tcpConnectionRepositoryKey =
                new TcpConnectionRepositoryKey(sourcePort, destinationPort, sourceAddress, destinationAddress);
        TcpConnection tcpConnection = this.connectionRepository.computeIfAbsent(tcpConnectionRepositoryKey, key -> {
            TcpConnection result = new TcpConnection(key, this, connectionRepository);
            this.vpnService.protect(result.getRemoteSocket());
            this.connectionThreadPool.execute(result);
            return result;
        });
        try {
            tcpConnection.onDeviceInbound(tcpPacket);
        } catch (Exception e) {
            Log.e(IpV4TcpPacketHandler.class.getName(), "Fail to put tcp connection inbound because of exception.", e);
        }
    }

    @Override
    public void write(TcpConnectionRepositoryKey repositoryKey, TcpPacket tcpPacket) throws IOException {
        IpPacketBuilder ipPacketBuilder = new IpPacketBuilder();
        ipPacketBuilder.data(tcpPacket);
        IpV4HeaderBuilder ipV4HeaderBuilder = new IpV4HeaderBuilder();
        ipV4HeaderBuilder.destinationAddress(repositoryKey.getSourceAddress());
        ipV4HeaderBuilder.sourceAddress(repositoryKey.getDestinationAddress());
        ipV4HeaderBuilder.protocol(IpDataProtocol.TCP);
        ipPacketBuilder.header(ipV4HeaderBuilder.build());
        IpPacket ipPacket = ipPacketBuilder.build();
        byte[] ipPacketBytes = IpPacketWriter.INSTANCE.write(ipPacket);
        this.rawDeviceOutputStream.write(ipPacketBytes);
    }
}
