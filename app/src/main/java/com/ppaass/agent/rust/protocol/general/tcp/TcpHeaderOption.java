package com.ppaass.agent.rust.protocol.general.tcp;

public class TcpHeaderOption {
    public enum Kind {
        EOL(0, 0),
        NOP(1, 0),
        MSS(2, 2),
        WSOPT(3, 1),
        SACK_PREMITTED(4, 0),
        SACK(5, -1),
        TSPOT(8, 8),
        MD5SIG(19, 16),
        UTO(28, 2),
        TCP_AO(29, -1),
        FOC(34, -1),
        EXPERIMENTAL1(253, -1),
        EXPERIMENTAL2(254, -1);

        public static Kind fromValue(int value) {
            for (Kind k : Kind.values()) {
                if (k.getValue() == value) {
                    return k;
                }
            }
            return null;
        }

        private final int value;
        private final int infoLength;

        Kind(int value, int infoLength) {
            this.value = value;
            this.infoLength = infoLength;
        }

        public int getValue() {
            return value;
        }

        public int getInfoLength() {
            return infoLength;
        }
    }

    private final Kind kind;
    private final byte[] info;

    public TcpHeaderOption(Kind kind, byte[] info) {
        this.kind = kind;
        if (info == null) {
            info = new byte[]{};
        }
        this.info = info;
    }

    public byte[] getInfo() {
        return info;
    }

    public Kind getKind() {
        return kind;
    }
}
