package com.ppaass.agent.service;

import android.util.Log;
import com.ppaass.agent.protocol.general.udp.UdpPacket;

import java.io.FileOutputStream;
import java.io.OutputStream;

public class UdpPacketHandler {
    private final OutputStream rawVpnOutputStream;

    public UdpPacketHandler(OutputStream rawVpnOutputStream) {
        this.rawVpnOutputStream = rawVpnOutputStream;
    }

    public void handle(UdpPacket udpPacket) {
        Log.d(UdpPacketHandler.class.getName(), udpPacket.toString());
    }
}
