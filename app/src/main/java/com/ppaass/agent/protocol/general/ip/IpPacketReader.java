package com.ppaass.agent.protocol.general.ip;

import com.ppaass.agent.protocol.general.icmp.IcmpPacketReader;
import com.ppaass.agent.protocol.general.tcp.TcpPacketReader;
import com.ppaass.agent.protocol.general.udp.UdpPacketReader;
import com.ppaass.agent.protocol.general.IProtocolConst;

import java.nio.ByteBuffer;

public class IpPacketReader {
    public static final IpPacketReader INSTANCE = new IpPacketReader();

    private IpPacketReader() {
    }

    public IpPacket parse(byte[] input) {
        ByteBuffer byteBuffer = ByteBuffer.wrap(input);
        byte versionAndHeaderLength = byteBuffer.get();
        int version = versionAndHeaderLength >> 4;
        IpHeaderVersion ipHeaderVersion = IpHeaderVersion.parse((byte) version);
        if (IpHeaderVersion.V4 != ipHeaderVersion) {
            IpPacketBuilder ipPacketBuilder = new IpPacketBuilder();
            IpV6Header ipV6Header = new IpV6Header();
            return ipPacketBuilder.header(ipV6Header).build();
        }
        int internalHeaderLength = versionAndHeaderLength & 0xf;
        IpV4HeaderBuilder ipV4HeaderBuilder = new IpV4HeaderBuilder();
        byte dsAndEcn = byteBuffer.get();
        int ds = dsAndEcn >> 2;
        int ecn = dsAndEcn & 3;
        IpDifferentiatedServices differentiatedServices = new IpDifferentiatedServices(
                ds >> 3,
                (ds & 4) != 0,
                (ds & 2) != 0,
                (ds & 1) != 0
        );
        ipV4HeaderBuilder.ds(differentiatedServices);
        IpExplicitCongestionNotification explicitCongestionNotification = new IpExplicitCongestionNotification(
                (ecn & 2) != 0,
                ecn & 1
        );
        ipV4HeaderBuilder.ecn(explicitCongestionNotification);
        int totalLength = byteBuffer.getShort() & 0xFFFF;
        ipV4HeaderBuilder.identification(byteBuffer.getShort() & 0xFFFF);
        int flagsAndOffset = byteBuffer.getShort();
        int flagsInBit = flagsAndOffset >> 13;
        IpFlags flags = new IpFlags(
                (flagsInBit & 2) != 0,
                (flagsInBit & 1) != 0
        );
        ipV4HeaderBuilder.flags(flags);
        ipV4HeaderBuilder.fragmentOffset(flagsAndOffset & 0x1FFF);
        ipV4HeaderBuilder.ttl(byteBuffer.get() & 0xFF);
        IpDataProtocol protocol = IpDataProtocol.parse(byteBuffer.get() & 0xFF);
        if (protocol == null) {
            throw new IllegalStateException("No protocol found in ip packet.");
        }
        ipV4HeaderBuilder.protocol(protocol);
        ipV4HeaderBuilder.checksum(byteBuffer.getShort() & 0xFFFF);
        byte[] sourceAddress = new byte[4];
        byteBuffer.get(sourceAddress);
        ipV4HeaderBuilder.sourceAddress(sourceAddress);
        byte[] destinationAddress = new byte[4];
        byteBuffer.get(destinationAddress);
        ipV4HeaderBuilder.destinationAddress(destinationAddress);
        byte[] optionBytes = new byte[internalHeaderLength * 4 - IProtocolConst.MIN_IP_HEADER_LENGTH];
        byteBuffer.get(optionBytes);
        ipV4HeaderBuilder.options(optionBytes);
        byte[] dataBytes = new byte[totalLength - internalHeaderLength * 4];
        byteBuffer.get(dataBytes);
        byteBuffer.clear();
        IpV4Header ipV4Header = ipV4HeaderBuilder.build();
        IpPacketBuilder ipPacketBuilder = new IpPacketBuilder();
        ipPacketBuilder.header(ipV4Header);
        if (IpDataProtocol.TCP == protocol) {
            ipPacketBuilder.data(TcpPacketReader.INSTANCE.parse(dataBytes));
            return ipPacketBuilder.build();
        }
        if (IpDataProtocol.UDP == protocol) {
            ipPacketBuilder.data(UdpPacketReader.INSTANCE.parse(dataBytes));
            return ipPacketBuilder.build();
        }
        ipPacketBuilder.data(IcmpPacketReader.INSTANCE.parse(dataBytes));
        return ipPacketBuilder.build();
    }
}
