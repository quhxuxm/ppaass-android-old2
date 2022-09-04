package com.ppaass.agent.protocol.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class PayloadEncryption {
    private byte[] token;
    private PayloadEncryptionType type;

    @JsonCreator
    public PayloadEncryption(
            @JsonProperty("type")
            PayloadEncryptionType type,
            @JsonProperty("token")
            byte[] token) {
        this.token = token;
        this.type = type;
    }

    public byte[] getToken() {
        return token;
    }

    public void setToken(byte[] token) {
        this.token = token;
    }

    public PayloadEncryptionType getType() {
        return type;
    }

    public void setType(PayloadEncryptionType type) {
        this.type = type;
    }
}
