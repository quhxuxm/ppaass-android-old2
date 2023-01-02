package com.ppaass.agent.rust.protocol.message;

import com.ppaass.agent.rust.protocol.message.encryption.PpaassMessagePayloadEncryption;

public class PpaassMessage {
    private String id;
    private String userToken;
    private PpaassMessagePayloadEncryption payloadEncryption;
    private byte[] payloadBytes;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserToken() {
        return userToken;
    }

    public void setUserToken(String userToken) {
        this.userToken = userToken;
    }

    public PpaassMessagePayloadEncryption getPayloadEncryption() {
        return payloadEncryption;
    }

    public void setPayloadEncryption(PpaassMessagePayloadEncryption payloadEncryption) {
        this.payloadEncryption = payloadEncryption;
    }

    public byte[] getPayloadBytes() {
        return payloadBytes;
    }

    public void setPayloadBytes(byte[] payloadBytes) {
        this.payloadBytes = payloadBytes;
    }
}
