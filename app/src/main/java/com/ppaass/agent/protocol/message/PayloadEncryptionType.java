package com.ppaass.agent.protocol.message;

public enum PayloadEncryptionType {
    Plain((byte) 0), Aes((byte) 2);
    private final byte value;

    PayloadEncryptionType(byte value) {
        this.value = value;
    }

    public byte getValue() {
        return value;
    }

    public static PayloadEncryptionType from(byte value) {
        if (Plain.getValue() == value) {
            return Plain;
        }
        if (Aes.getValue() == value) {
            return Aes;
        }
        throw new UnsupportedOperationException();
    }
}
