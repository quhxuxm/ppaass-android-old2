package com.ppaass.agent.service.handler.tcp;

import java.util.Arrays;
import java.util.Objects;

public class TcpConnectionRepositoryKey {
    private final int sourcePort;
    private final int destinationPort;
    private final byte[] sourceAddress;
    private final byte[] destinationAddress;

    public TcpConnectionRepositoryKey(int sourcePort, int destinationPort, byte[] sourceAddress,
                                      byte[] destinationAddress) {
        this.sourcePort = sourcePort;
        this.destinationPort = destinationPort;
        this.sourceAddress = sourceAddress;
        this.destinationAddress = destinationAddress;
    }

    public int getSourcePort() {
        return sourcePort;
    }

    public int getDestinationPort() {
        return destinationPort;
    }

    public byte[] getSourceAddress() {
        return sourceAddress;
    }

    public byte[] getDestinationAddress() {
        return destinationAddress;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TcpConnectionRepositoryKey that = (TcpConnectionRepositoryKey) o;
        return sourcePort == that.sourcePort && destinationPort == that.destinationPort &&
                Arrays.equals(sourceAddress, that.sourceAddress) &&
                Arrays.equals(destinationAddress, that.destinationAddress);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(sourcePort, destinationPort);
        result = 31 * result + Arrays.hashCode(sourceAddress);
        result = 31 * result + Arrays.hashCode(destinationAddress);
        return result;
    }

    @Override
    public String toString() {
        return "TcpConnectionRepositoryKey{" +
                "sourceAddress=" + Arrays.toString(sourceAddress) +
                ", sourcePort=" + sourcePort +
                ", destinationAddress=" + Arrays.toString(destinationAddress) +
                ", destinationPort=" + destinationPort +
                '}';
    }
}
