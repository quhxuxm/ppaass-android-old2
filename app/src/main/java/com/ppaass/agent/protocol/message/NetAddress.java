package com.ppaass.agent.protocol.message;

public class NetAddress {
    private short port;
    private byte[] host;
    private NetAddressType type;

    public short getPort() {
        return port;
    }

    public void setPort(short port) {
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
}
