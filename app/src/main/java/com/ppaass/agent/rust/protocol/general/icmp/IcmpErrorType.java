package com.ppaass.agent.rust.protocol.general.icmp;

public enum IcmpErrorType implements IIcmpType {
    Error_Unreachable((byte) 3),
    Error_SourceClosed((byte) 4),
    Error_Redirect((byte) 5),
    Error_Timeout((byte) 11),
    Error_ParameterProblem((byte) 12),
    Error_AddressRequest((byte) 17),
    Error_AddressReply((byte) 18),
    ;
    private final byte value;

    IcmpErrorType(byte value) {
        this.value = value;
    }

    public byte getValue() {
        return value;
    }

    public static IcmpErrorType parse(int value) {
        for (IcmpErrorType v : IcmpErrorType.values()) {
            if (v.value == value) {
                return v;
            }
        }
        return null;
    }
}
