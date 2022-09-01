package com.ppaass.agent.service;

public interface IVpnConst {
    int TCP_CONNECTION_IDLE_TIMEOUT_MS = 120 * 1000;
    int DNS_IDLE_TIMEOUT_MS = 120 * 1000;
    int TCP_CONNECTION_NUMBER = 256;
    int MTU = 1500;
    int READ_BUFFER_SIZE = MTU + 40;
    //For VPN the mtu can be larger than 1500, but should not more than 65535
    int TCP_MSS = MTU - 40;
    int TCP_WINDOW = 65535;
    String DNS = "149.28.219.182";
    String PPAASS_PROXY_IP = "149.28.219.182";
    short PPAASS_PROXY_PORT = 80;
    String PPAASS_USER_TOKEN = "user1";
    String PPAASS_PROTOCOL_FLAG = "__PPAASS__";
    String TCP_CONNECTION = "TCP_CONNECTION";
    String PPAASS_PROXY_USER_TOKEN = "user1";
}
