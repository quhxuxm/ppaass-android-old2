package com.ppaass.agent.protocol.message.address;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class PpaassNetAddress {
    private final APpaassNetAddressValue value;
    private final PpaassNetAddressType type;

    @JsonCreator
    public PpaassNetAddress(
            @JsonProperty("type")
            PpaassNetAddressType type,
            @JsonProperty("value")
            APpaassNetAddressValue value) {
        this.value = value;
        this.type = type;
    }

    public APpaassNetAddressValue getValue() {
        return value;
    }

    public PpaassNetAddressType getType() {
        return type;
    }

    @Override
    public String toString() {
        return "PpaassNetAddress{" +
                "type=" + type +
                ", value=" + value +
                '}';
    }
}
