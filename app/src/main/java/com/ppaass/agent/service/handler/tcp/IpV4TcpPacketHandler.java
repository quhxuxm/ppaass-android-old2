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
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class IpV4TcpPacketHandler implements TcpIpPacketWriter {
    private final OutputStream rawDeviceOutputStream;
    private final HashMap<TcpConnectionRepositoryKey, TcpConnection> connectionRepository;
    private final VpnService vpnService;
    private final ExecutorService connectionThreadPool;

    public IpV4TcpPacketHandler(OutputStream rawDeviceOutputStream, VpnService vpnService) {
        this.rawDeviceOutputStream = rawDeviceOutputStream;
        this.vpnService = vpnService;
        this.connectionRepository = new HashMap<>();
        this.connectionThreadPool = Executors.newFixedThreadPool(128);
    }

    private TcpConnection getOrCreate(TcpConnectionRepositoryKey repositoryKey, TcpPacket tcpPacket)
            throws IOException {
        TcpConnection tcpConnection = this.connectionRepository.get(repositoryKey);
        if (tcpConnection != null) {
            Log.d(IpV4TcpPacketHandler.class.getName(),
                    "Get existing tcp connection: " + tcpConnection + ", tcp packet: " + tcpPacket);
            return tcpConnection;
        }
        synchronized (this.connectionRepository) {
            tcpConnection = new TcpConnection(repositoryKey, this, connectionRepository,
                    IpV4TcpPacketHandler.this.vpnService);
            this.connectionRepository.put(repositoryKey, tcpConnection);
            this.connectionThreadPool.execute(tcpConnection);
            Log.d(IpV4TcpPacketHandler.class.getName(),
                    "Create tcp connection: " + tcpConnection + ", tcp packet: " + tcpPacket);
        }
        return tcpConnection;
    }

    public void handle(TcpPacket tcpPacket, IpV4Header ipV4Header) throws Exception {
        TcpHeader tcpHeader = tcpPacket.getHeader();
        int sourcePort = tcpHeader.getSourcePort();
        int destinationPort = tcpHeader.getDestinationPort();
        byte[] sourceAddress = ipV4Header.getSourceAddress();
        byte[] destinationAddress = ipV4Header.getDestinationAddress();
        TcpConnectionRepositoryKey tcpConnectionRepositoryKey =
                new TcpConnectionRepositoryKey(sourcePort, destinationPort, sourceAddress, destinationAddress);
        TcpConnection tcpConnection = this.getOrCreate(tcpConnectionRepositoryKey, tcpPacket);
        Log.d(IpV4TcpPacketHandler.class.getName(),
                "Do inbound for tcp connection: " + tcpConnection + ", incoming tcp packet: " + tcpPacket +
                        ", ip header: " +
                        ipV4Header);
        tcpConnection.onDeviceInbound(tcpPacket);
    }

    @Override
    public void write(TcpConnection tcpConnection, TcpPacket tcpPacket) throws IOException {
        IpPacketBuilder ipPacketBuilder = new IpPacketBuilder();
        IpV4HeaderBuilder ipV4HeaderBuilder = new IpV4HeaderBuilder();
        short identification = (short) (Math.abs(Math.random()) * 10000);
        ipV4HeaderBuilder.identification(identification);
        ipV4HeaderBuilder.destinationAddress(tcpConnection.getRepositoryKey().getSourceAddress());
        ipV4HeaderBuilder.sourceAddress(tcpConnection.getRepositoryKey().getDestinationAddress());
        ipV4HeaderBuilder.protocol(IpDataProtocol.TCP);
        ipPacketBuilder.header(ipV4HeaderBuilder.build());
        ipPacketBuilder.data(tcpPacket);
        IpPacket ipPacket = ipPacketBuilder.build();
        ByteBuffer ipPacketBytes = IpPacketWriter.INSTANCE.write(ipPacket);
        Log.v(IpV4TcpPacketHandler.class.getName(),
                "Write ip packet to device, current connection:  " + tcpConnection +
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
