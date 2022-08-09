package com.ppaass.agent.service;

import android.net.VpnService;
import android.util.Log;
import io.netty.channel.ChannelFactory;
import io.netty.channel.socket.nio.NioDatagramChannel;

import java.io.IOException;
import java.nio.channels.DatagramChannel;

public class PpaassVpnUdpChannelFactory implements ChannelFactory<NioDatagramChannel> {
    private final VpnService vpnService;

    public PpaassVpnUdpChannelFactory(VpnService vpnService) {
        this.vpnService = vpnService;
    }

    @Override
    public NioDatagramChannel newChannel() {
        try {
            DatagramChannel javaDatagramChannel = DatagramChannel.open();
            this.vpnService.protect(javaDatagramChannel.socket());
            return new NioDatagramChannel(javaDatagramChannel);
        } catch (IOException e) {
            Log.e(PpaassVpnUdpChannelFactory.class.getName(), "Fail to create udp channel because of exception.", e);
            throw new IllegalStateException("Fail to create udp channel because of exception.", e);
        }
    }
}
