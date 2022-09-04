package com.ppaass.agent.protocol.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class NetAddress {
    private final NetAddressValue value;
    private final NetAddressType type;

    @JsonCreator
    public NetAddress(
            @JsonProperty("type")
            NetAddressType type,
            @JsonProperty("value")
            NetAddressValue value) {
        this.value = value;
        this.type = type;
    }

    public NetAddressValue getValue() {
        return value;
    }

    public NetAddressType getType() {
        return type;
    }

    @Override
    public String toString() {
        return "NetAddress{" +
                "type=" + type +
                ", value=" + value +
                '}';
    }
}
