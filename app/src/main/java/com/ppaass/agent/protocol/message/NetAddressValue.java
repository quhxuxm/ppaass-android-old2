package com.ppaass.agent.protocol.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Arrays;

public class NetAddressValue {
    private final int port;
    private final byte[] host;

    @JsonCreator
    public NetAddressValue(
            @JsonProperty("host")
            byte[] host,
            @JsonProperty("port")
            int port) {
        this.port = port;
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public byte[] getHost() {
        return host;
    }

    @Override
    public String toString() {
        return "NetAddress{" +
                ", host=" + Arrays.toString(host) +
                ", port=" + port +
                '}';
    }
}
