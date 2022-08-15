package com.ppaass.agent.protocol.message;

public enum NetAddressType {
    IpV4((byte) 0), IpV6((byte) 1), Domain((byte) 2);
    private final byte value;

    NetAddressType(byte value) {
        this.value = value;
    }

    public byte getValue() {
        return value;
    }

    public static NetAddressType from(byte value) {
        if (IpV4.getValue() == value) {
            return IpV4;
        }
        if (IpV6.getValue() == value) {
            return IpV6;
        }
        if (Domain.getValue() == value) {
            return Domain;
        }
        throw new UnsupportedOperationException();
    }
}
