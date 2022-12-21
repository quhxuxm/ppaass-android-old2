package com.ppaass.agent.protocol.message.address;

public abstract class APpaassNetAddressValue {
    private final int port;

    protected APpaassNetAddressValue(int port) {
        this.port = port;
    }

    public int getPort() {
        return port;
    }
}
