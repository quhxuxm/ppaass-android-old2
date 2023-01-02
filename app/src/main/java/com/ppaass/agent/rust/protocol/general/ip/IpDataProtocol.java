package com.ppaass.agent.rust.protocol.general.ip;

public enum IpDataProtocol {
    TCP(6), UDP( 17), ICMP( 1);
    private final int value;

    public static IpDataProtocol parse(int value) {
        for (IpDataProtocol v : IpDataProtocol.values()) {
            if (v.value == value) {
                return v;
            }
        }
        return null;
    }

    IpDataProtocol(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
