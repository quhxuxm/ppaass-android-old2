package com.ppaass.agent.rust.protocol.general.ip;

public enum IpHeaderVersion {
    V4(4), V6(6);
    private final int value;

    public static IpHeaderVersion parse(int value) {
        for (IpHeaderVersion v : IpHeaderVersion.values()) {
            if (v.value == value) {
                return v;
            }
        }
        return null;
    }

    IpHeaderVersion(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
