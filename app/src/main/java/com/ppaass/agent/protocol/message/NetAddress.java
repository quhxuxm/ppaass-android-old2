package com.ppaass.agent.protocol.message;

import java.util.Arrays;

public class NetAddress {
    private int port;
    private byte[] host;
    private NetAddressType type;

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public byte[] getHost() {
        return host;
    }

    public void setHost(byte[] host) {
        this.host = host;
    }

    public NetAddressType getType() {
        return type;
    }

    public void setType(NetAddressType type) {
        this.type = type;
    }

    @Override
    public String toString() {
        return "NetAddress{" +
                "type=" + type +
                ", host=" + Arrays.toString(host) +
                ", port=" + port +
                '}';
    }
}
