package com.ppaass.agent.service;

public interface IVpnConst {
    int WRITE_BUFFER_SIZE = 65535;
    int READ_BUFFER_SIZE = 655350;
    int READ_REMOTE_BUFFER_SIZE = 65535;
    //For VPN the mtu can be larger than 1500, but should not more than 65535
    int MTU = 1500;
    int TCP_MSS = MTU - 40;
    int TCP_WINDOW = 65535;
    int UDP_HEADER_LENGTH = 8;
//         String DNS = "192.168.31.1";
//    String DNS = "10.246.128.21";
        String DNS = "149.28.219.182";
//    String DNS = "8.8.8.8";
//    String DNS = "127.0.0.53";
//        String PPAASS_PROXY_IP="192.168.31.200";
    String PPAASS_PROXY_IP = "149.28.219.182";
//        String PPAASS_PROXY_IP = "10.175.4.220";
//    short PPAASS_PROXY_PORT = 9097;
    short PPAASS_PROXY_PORT = 80;
    String PPAASS_USER_TOKEN = "user1";
    String PPAASS_PROTOCOL_FLAG = "__PPAASS__";
    String TCP_CONNECTION = "TCP_CONNECTION";
    String UDP_SOURCE_ADDR = "UDP_SOURCE_ADDR";
    String UDP_SOURCE_PORT = "UDP_SOURCE_PORT";
    String PPAASS_PROXY_USER_TOKEN = "user1";
}
