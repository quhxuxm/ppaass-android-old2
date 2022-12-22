package com.ppaass.agent.protocol.message.address;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class PpaassNetAddress {
    private final PpaassNetAddressValue value;
    private final PpaassNetAddressType type;

    @JsonCreator
    public PpaassNetAddress(
            @JsonProperty("type")
            PpaassNetAddressType type,
            @JsonProperty("value")
            PpaassNetAddressValue value) {
        this.value = value;
        this.type = type;
    }

    public PpaassNetAddressValue getValue() {
        return value;
    }

    public PpaassNetAddressType getType() {
        return type;
    }


}
