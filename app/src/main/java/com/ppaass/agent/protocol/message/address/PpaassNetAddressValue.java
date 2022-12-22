package com.ppaass.agent.protocol.message.address;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class PpaassNetAddressValue {
    private final int port;
    private final byte[] ip;

    @JsonCreator
    public PpaassNetAddressValue(@JsonProperty("ip") byte[] ip, @JsonProperty("port") int port) {
        this.port=port;
        this.ip = ip;
    }

    public byte[] getIp() {
        return ip;
    }


    public int getPort() {
        return port;
    }
}
