package com.ppaass.agent.protocol.message.address;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class PpaassNetAddressDomainValue extends APpaassNetAddressValue {

    private final String host;

    @JsonCreator
    public PpaassNetAddressDomainValue(@JsonProperty("host") String host, @JsonProperty("port") int port) {
        super(port);

        this.host = host;
    }

    public String getHost() {
        return host;
    }
}
