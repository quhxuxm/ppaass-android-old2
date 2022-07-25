package com.ppaass.agent.service;

import android.util.Log;
import com.ppaass.agent.protocol.general.tcp.TcpPacket;

import java.io.FileOutputStream;
import java.io.OutputStream;

public class TcpPacketHandler {
    private final OutputStream rawVpnOutputStream;

    public TcpPacketHandler(OutputStream rawVpnOutputStream) {
        this.rawVpnOutputStream = rawVpnOutputStream;
    }

    public void handle(TcpPacket tcpPacket) {
        Log.d(TcpPacketHandler.class.getName(), tcpPacket.toString());
    }
}
