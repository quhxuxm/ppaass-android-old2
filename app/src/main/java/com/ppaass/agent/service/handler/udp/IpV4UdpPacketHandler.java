package com.ppaass.agent.service.handler.udp;

import android.net.VpnService;
import android.util.Log;
import com.ppaass.agent.protocol.general.ip.*;
import com.ppaass.agent.protocol.general.udp.UdpPacket;
import com.ppaass.agent.protocol.general.udp.UdpPacketBuilder;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;

public class IpV4UdpPacketHandler {
    private final OutputStream rawDeviceOutputStream;
    private final int writeBufferSize;
    private final VpnService vpnService;

    public IpV4UdpPacketHandler(OutputStream rawDeviceOutputStream, int writeBufferSize, VpnService vpnService)
            throws Exception {
        this.rawDeviceOutputStream = rawDeviceOutputStream;
        this.writeBufferSize = writeBufferSize;
        this.vpnService = vpnService;
    }

    public void handle(UdpPacket udpPacket, IpV4Header ipV4Header) {
        Log.d(IpV4UdpPacketHandler.class.getName(), udpPacket.toString());
        try {
            InetAddress destinationAddress = InetAddress.getByAddress(ipV4Header.getDestinationAddress());
            int destinationPort = udpPacket.getHeader().getDestinationPort();
            Log.d(IpV4UdpPacketHandler.class.getName(), "Begin to send udp packet to remote: " + udpPacket);
            DatagramChannel udpChannel = DatagramChannel.open();
            udpChannel.socket().bind(null);
            udpChannel.connect(new InetSocketAddress(destinationAddress, destinationPort));
            udpChannel.configureBlocking(false);
            this.vpnService.protect(udpChannel.socket());
            udpChannel.write(ByteBuffer.wrap(udpPacket.getData()));
            ByteBuffer packetFromRemoteToDeviceBuffer = ByteBuffer.allocate(65535);
            int packetFromRemoteToDeviceBufferLength = udpChannel.read(packetFromRemoteToDeviceBuffer);
            udpChannel.disconnect();
            udpChannel.close();
            UdpPacketBuilder remoteToDeviceUdpPacketBuilder = new UdpPacketBuilder();
            byte[] packetFromRemoteToDeviceBytes = new byte[packetFromRemoteToDeviceBufferLength];
            packetFromRemoteToDeviceBuffer.get(packetFromRemoteToDeviceBytes);
            remoteToDeviceUdpPacketBuilder.data(packetFromRemoteToDeviceBytes);
            remoteToDeviceUdpPacketBuilder.destinationPort(udpPacket.getHeader().getSourcePort());
            remoteToDeviceUdpPacketBuilder.sourcePort(udpPacket.getHeader().getDestinationPort());
            UdpPacket remoteToDeviceUdpPacket = remoteToDeviceUdpPacketBuilder.build();
            Log.d(IpV4UdpPacketHandler.class.getName(),
                    "Success receive remote udp packet: " + remoteToDeviceUdpPacket);
            IpPacketBuilder ipPacketBuilder = new IpPacketBuilder();
            ipPacketBuilder.data(remoteToDeviceUdpPacket);
            IpV4HeaderBuilder ipV4HeaderBuilder = new IpV4HeaderBuilder();
            ipV4HeaderBuilder.destinationAddress(ipV4Header.getSourceAddress());
            ipV4HeaderBuilder.sourceAddress(ipV4Header.getDestinationAddress());
            ipV4HeaderBuilder.protocol(IpDataProtocol.UDP);
            ipPacketBuilder.header(ipV4HeaderBuilder.build());
            IpPacket ipPacket = ipPacketBuilder.build();
            byte[] ipPacketBytes = IpPacketWriter.INSTANCE.write(ipPacket);
            this.rawDeviceOutputStream.write(ipPacketBytes);
            this.rawDeviceOutputStream.flush();
        } catch (Exception e) {
            Log.e(IpV4UdpPacketHandler.class.getName(),
                    "Fail to handle udp packet because of exception, udp packet: " + udpPacket, e);
        }
    }
}
