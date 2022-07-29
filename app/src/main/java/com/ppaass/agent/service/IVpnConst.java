package com.ppaass.agent.service;

public interface IVpnConst {
    int WRITE_BUFFER_SIZE = 65535;
    int READ_BUFFER_SIZE = 65535;
    int READ_REMOTE_BUFFER_SIZE = 65535;
    int MTU = 1500;
    short TCP_MSS = MTU - 40;
    int TCP_WINDOW = 65535;
}
