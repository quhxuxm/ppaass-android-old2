package com.ppaass.agent.rust.protocol.message.payload;

public class HeartbeatRequestPayload {
    private long timestamp;

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
