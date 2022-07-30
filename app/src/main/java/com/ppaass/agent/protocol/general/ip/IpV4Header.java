package com.ppaass.agent.protocol.general.ip;

import java.util.Arrays;

public class IpV4Header implements IIpHeader {
    private final IpHeaderVersion version;
    private int internetHeaderLength;
    private IpDifferentiatedServices ds;
    private IpExplicitCongestionNotification ecn;
    private int totalLength;
    private int identification;
    private IpFlags flags;
    private int fragmentOffset;
    private int ttl;
    private IpDataProtocol protocol;
    private int checksum;
    private byte[] sourceAddress;
    private byte[] destinationAddress;
    private byte[] options;

    IpV4Header() {
        this.version = IpHeaderVersion.V4;
        this.ttl = 64;
        this.internetHeaderLength = 5;
        this.options = new byte[]{};
        this.ds = new IpDifferentiatedServices(0, false, false, false);
        this.ecn = new IpExplicitCongestionNotification(false, 0);
        this.fragmentOffset = 0;
        this.identification = 0;
        this.flags = new IpFlags(true, false);
        this.totalLength = 0;
    }

    @Override
    public IpHeaderVersion getVersion() {
        return this.version;
    }

    public int getInternetHeaderLength() {
        return internetHeaderLength;
    }

    void setInternetHeaderLength(int internetHeaderLength) {
        this.internetHeaderLength = internetHeaderLength;
    }

    public IpDifferentiatedServices getDs() {
        return ds;
    }

    void setDs(IpDifferentiatedServices ds) {
        this.ds = ds;
    }

    public IpExplicitCongestionNotification getEcn() {
        return ecn;
    }

    void setEcn(IpExplicitCongestionNotification ecn) {
        this.ecn = ecn;
    }

    public int getTotalLength() {
        return totalLength;
    }

    void setTotalLength(int totalLength) {
        this.totalLength = totalLength;
    }

    public int getIdentification() {
        return identification;
    }

    void setIdentification(int identification) {
        this.identification = identification;
    }

    public IpFlags getFlags() {
        return flags;
    }

    void setFlags(IpFlags flags) {
        this.flags = flags;
    }

    public int getFragmentOffset() {
        return fragmentOffset;
    }

    void setFragmentOffset(int fragmentOffset) {
        this.fragmentOffset = fragmentOffset;
    }

    public int getTtl() {
        return ttl;
    }

    void setTtl(int ttl) {
        this.ttl = ttl;
    }

    public IpDataProtocol getProtocol() {
        return protocol;
    }

    void setProtocol(IpDataProtocol protocol) {
        this.protocol = protocol;
    }

    public int getChecksum() {
        return checksum;
    }

    void setChecksum(int checksum) {
        this.checksum = checksum;
    }

    public byte[] getSourceAddress() {
        return sourceAddress;
    }

    void setSourceAddress(byte[] sourceAddress) {
        this.sourceAddress = sourceAddress;
    }

    public byte[] getDestinationAddress() {
        return destinationAddress;
    }

    void setDestinationAddress(byte[] destinationAddress) {
        this.destinationAddress = destinationAddress;
    }

    public byte[] getOptions() {
        return options;
    }

    void setOptions(byte[] options) {
        if (options == null) {
            this.options = new byte[]{};
            return;
        }
        this.options = options;
    }

    @Override
    public String toString() {
        int[] sourceAddressForPrint = new int[4];
        for (int i = 0; i < 4; i++) {
            if (sourceAddress[i] < 0) {
                sourceAddressForPrint[i] = 256 + sourceAddress[i];
            } else {
                sourceAddressForPrint[i] = sourceAddress[i];
            }
        }
        int[] destinationAddressForPrint = new int[4];
        for (int i = 0; i < 4; i++) {
            if (destinationAddress[i] < 0) {
                destinationAddressForPrint[i] = 256 + destinationAddress[i];
            } else {
                destinationAddressForPrint[i] = destinationAddress[i];
            }
        }
        return "IpV4Header{" + "version=" + version + ", internetHeaderLength=" + internetHeaderLength + ", ds=" + ds +
                ", ecn=" + ecn + ", totalLength=" + totalLength + ", identification=" + identification + ", flags=" +
                flags + ", fragmentOffset=" + fragmentOffset + ", ttl=" + ttl + ", protocol=" + protocol +
                ", checksum=" + checksum + ", sourceAddress=" + Arrays.toString(sourceAddressForPrint) +
                ", destinationAddress=" + Arrays.toString(destinationAddressForPrint) + ", options=" +
                Arrays.toString(options) + '}';
    }
}
