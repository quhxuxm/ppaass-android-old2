package com.ppaass.agent.rust.service;

import io.netty.util.AttributeKey;

public interface IVpnConst {
    int TCP_CONNECTION_IDLE_TIMEOUT_MS = 2 * 60 * 1000;
    int DNS_IDLE_TIMEOUT_MS = Integer.MAX_VALUE;
    int TCP_CONNECTION_NUMBER = 256;
    int MTU = 1500;
    int READ_BUFFER_SIZE = 16384;
    //For VPN the mtu can be larger than 1500, but should not more than 65535
    int TCP_MSS = MTU - 40;
    int TCP_WINDOW = 65535;
    AttributeKey<byte[]> TCP_INBOUND_PACKET_TIMESTAMP = AttributeKey.valueOf("TCP_INBOUND_PACKET_TIMESTAMP");
//    String DNS = "149.28.219.182";
    String DNS = "192.168.31.1";
//    String DNS = "10.246.128.21";
    String PPAASS_PROXY_IP = "149.28.219.182";
    // String PPAASS_PROXY_IP = "192.168.31.200";
    short PPAASS_PROXY_PORT = 80;
    // short PPAASS_PROXY_PORT = 9097;
    String PPAASS_PROTOCOL_FLAG = "__PPAASS__";
    String TCP_CONNECTION = "TCP_CONNECTION";
    String PPAASS_PROXY_USER_TOKEN = "user1";
    boolean COMPRESS = true;
}
