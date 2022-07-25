package com.ppaass.agent.service;

import android.util.Log;
import com.ppaass.agent.protocol.general.udp.UdpPacket;

import java.io.FileOutputStream;

public class UdpPacketHandler {
    private final FileOutputStream rawVpnOutputStream;

    public UdpPacketHandler(FileOutputStream rawVpnOutputStream) {
        this.rawVpnOutputStream = rawVpnOutputStream;
    }

    public void handle(UdpPacket udpPacket) {
        Log.d(UdpPacketHandler.class.getName(), udpPacket.toString());
    }
}
