package com.ppaass.agent.service;

import android.util.Log;
import com.ppaass.agent.protocol.general.tcp.TcpPacket;

import java.io.FileOutputStream;

public class TcpPacketHandler {
    private final FileOutputStream rawVpnOutputStream;

    public TcpPacketHandler(FileOutputStream rawVpnOutputStream) {
        this.rawVpnOutputStream = rawVpnOutputStream;
    }

    public void handle(TcpPacket tcpPacket) {
        Log.d(TcpPacketHandler.class.getName(), tcpPacket.toString());
    }
}
