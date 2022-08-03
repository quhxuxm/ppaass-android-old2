package com.ppaass.agent.protocol.general.icmp;

public enum IcmpQueryType implements IIcmpType {
    Info_EchReply((byte) 0),
    Info_EchoRequest((byte) 8),
    Info_RouterLsa((byte) 9),
    Info_RouterRequest((byte) 10),
    Info_TimestampRequest((byte) 13),
    Info_TimestampReply((byte) 14),
    Info_InfoRequest((byte) 15),
    Info_InfoReply((byte) 16),
    ;
    private final byte value;

    IcmpQueryType(byte value) {
        this.value = value;
    }

    public byte getValue() {
        return value;
    }

    public static IcmpQueryType parse(int value) {
        for (IcmpQueryType v : IcmpQueryType.values()) {
            if (v.value == value) {
                return v;
            }
        }
        return null;
    }
}
