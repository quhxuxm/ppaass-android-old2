package com.ppaass.agent.protocol.message.encryption;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class PpaassMessagePayloadEncryption {
    private byte[] token;
    private PpaassMessagePayloadEncryptionType type;

    @JsonCreator
    public PpaassMessagePayloadEncryption(
            @JsonProperty("type")
            PpaassMessagePayloadEncryptionType type,
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

    public PpaassMessagePayloadEncryptionType getType() {
        return type;
    }

    public void setType(PpaassMessagePayloadEncryptionType type) {
        this.type = type;
    }
}
