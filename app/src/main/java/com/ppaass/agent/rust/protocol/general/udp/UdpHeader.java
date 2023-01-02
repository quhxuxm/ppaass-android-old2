package com.ppaass.agent.rust.protocol.general.udp;

public class UdpHeader {
    private int sourcePort;
    private int destinationPort;
    private int totalLength;
    private int checksum;

    UdpHeader() {
    }

    public int getSourcePort() {
        return sourcePort;
    }

    void setSourcePort(int sourcePort) {
        this.sourcePort = sourcePort;
    }

    public int getDestinationPort() {
        return destinationPort;
    }

    void setDestinationPort(int destinationPort) {
        this.destinationPort = destinationPort;
    }

    public int getTotalLength() {
        return totalLength;
    }

    void setTotalLength(int totalLength) {
        this.totalLength = totalLength;
    }

    public int getChecksum() {
        return checksum;
    }

    void setChecksum(int checksum) {
        this.checksum = checksum;
    }

    @Override
    public String toString() {
        return "UdpHeader{" +
                "sourcePort=" + sourcePort +
                ", destinationPort=" + destinationPort +
                ", totalLength=" + totalLength +
                ", checksum=" + checksum +
                '}';
    }
}
