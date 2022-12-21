package com.ppaass.agent.protocol.message.address;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class PpaassNetAddressIpValue extends APpaassNetAddressValue {

    private final byte[] ip;

    @JsonCreator
    public PpaassNetAddressIpValue(@JsonProperty("ip") byte[] ip, @JsonProperty("port") int port) {
        super(port);

        this.ip = ip;
    }

    public byte[] getIp() {
        return ip;
    }

}
