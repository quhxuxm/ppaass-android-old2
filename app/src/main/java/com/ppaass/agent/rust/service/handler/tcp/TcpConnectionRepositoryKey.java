package com.ppaass.agent.rust.service.handler.tcp;

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
        return "TcpConnectionRepositoryKey{" + "sourceAddress=" + Arrays.toString(sourceAddressForPrint) +
                ", sourcePort=" + sourcePort + ", destinationAddress=" + Arrays.toString(destinationAddressForPrint) +
                ", destinationPort=" + destinationPort + '}';
    }
}
