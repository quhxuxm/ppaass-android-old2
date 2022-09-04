package com.ppaass.agent.protocol.message;

public class Message {
    private String id;
    private String refId;
    private String connectionId;
    private String userToken;
    private PayloadEncryption payloadEncryption;
    private byte[] payload;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRefId() {
        return refId;
    }

    public void setRefId(String refId) {
        this.refId = refId;
    }

    public String getConnectionId() {
        return connectionId;
    }

    public void setConnectionId(String connectionId) {
        this.connectionId = connectionId;
    }

    public String getUserToken() {
        return userToken;
    }

    public void setUserToken(String userToken) {
        this.userToken = userToken;
    }

    public PayloadEncryption getPayloadEncryption() {
        return payloadEncryption;
    }

    public void setPayloadEncryption(PayloadEncryption payloadEncryption) {
        this.payloadEncryption = payloadEncryption;
    }

    public byte[] getPayload() {
        return payload;
    }

    public void setPayload(byte[] payload) {
        this.payload = payload;
    }

    @Override
    public String toString() {
        return "Message{" +
                "id='" + id + '\'' +
                ", refId='" + refId + '\'' +
                ", connectionId='" + connectionId + '\'' +
                ", userToken='" + userToken + '\'' +
                ", payloadEncryption=" + payloadEncryption +
                ", payload data size=" + payload.length +
                '}';
    }
}
