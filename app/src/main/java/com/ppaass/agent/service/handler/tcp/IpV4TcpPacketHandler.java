package com.ppaass.agent.service.handler.tcp;

import android.net.VpnService;
import android.util.Log;
import com.ppaass.agent.protocol.general.ip.*;
import com.ppaass.agent.protocol.general.tcp.TcpHeader;
import com.ppaass.agent.protocol.general.tcp.TcpPacket;
import com.ppaass.agent.service.handler.TcpIpPacketWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
        this.connectionRepository = new ConcurrentHashMap<>();
        this.connectionThreadPool = Executors.newFixedThreadPool(128);
    }

    public void handle(TcpPacket tcpPacket, IpV4Header ipV4Header) throws Exception {
        TcpHeader tcpHeader = tcpPacket.getHeader();
        int sourcePort = tcpHeader.getSourcePort();
        int destinationPort = tcpHeader.getDestinationPort();
        byte[] sourceAddress = ipV4Header.getSourceAddress();
        byte[] destinationAddress = ipV4Header.getDestinationAddress();
        TcpConnectionRepositoryKey tcpConnectionRepositoryKey =
                new TcpConnectionRepositoryKey(sourcePort, destinationPort, sourceAddress, destinationAddress);
        TcpConnection tcpConnection = this.connectionRepository.computeIfAbsent(tcpConnectionRepositoryKey, key -> {
            TcpConnection result;
            try {
                result = new TcpConnection(key, this, connectionRepository, IpV4TcpPacketHandler.this.vpnService);
            } catch (IOException e) {
                Log.e(IpV4TcpPacketHandler.class.getName(), "Fail to create tcp connection because of error.", e);
                return null;
            }
            Log.d(IpV4TcpPacketHandler.class.getName(),
                    "Create tcp connection: " + result + ", incoming tcp packet: " + tcpPacket + ", ip header: " +
                            ipV4Header);
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
    public void write(TcpConnection tcpConnection, TcpPacket tcpPacket) throws IOException {
        IpPacketBuilder ipPacketBuilder = new IpPacketBuilder();
        ipPacketBuilder.data(tcpPacket);
        IpV4HeaderBuilder ipV4HeaderBuilder = new IpV4HeaderBuilder();
        short identification = (short) (Math.random() * 10000);
        ipV4HeaderBuilder.identification(identification);
        ipV4HeaderBuilder.destinationAddress(tcpConnection.getRepositoryKey().getSourceAddress());
        ipV4HeaderBuilder.sourceAddress(tcpConnection.getRepositoryKey().getDestinationAddress());
        ipV4HeaderBuilder.protocol(IpDataProtocol.TCP);
        ipPacketBuilder.header(ipV4HeaderBuilder.build());
        IpPacket ipPacket = ipPacketBuilder.build();
        ByteBuffer ipPacketBytes = IpPacketWriter.INSTANCE.write(ipPacket);
        Log.v(IpV4TcpPacketHandler.class.getName(),
                "Write ip packet to device, current connection:  " + tcpConnection +
                        ", output ip packet: " + ipPacket);
        byte[] bytesWriteToDevice = new byte[ipPacketBytes.remaining()];
        ipPacketBytes.get(bytesWriteToDevice);
        ipPacketBytes.clear();
        this.rawDeviceOutputStream.write(bytesWriteToDevice);
        this.rawDeviceOutputStream.flush();
    }
}
