package com.ppaass.agent.service;

import android.net.VpnService;
import android.util.Log;
import io.netty.channel.ChannelFactory;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.io.IOException;
import java.nio.channels.SocketChannel;

public class PpaassVpnNettyTcpChannelFactory implements ChannelFactory<NioSocketChannel> {
    private final VpnService vpnService;

    public PpaassVpnNettyTcpChannelFactory(VpnService vpnService) {
        this.vpnService = vpnService;
    }

    @Override
    public NioSocketChannel newChannel() {
        try {
            var javaSocketChannel = SocketChannel.open();
            this.vpnService.protect(javaSocketChannel.socket());
            return new NioSocketChannel(javaSocketChannel);
        } catch (IOException e) {
            Log.e(PpaassVpnNettyTcpChannelFactory.class.getName(), "Fail to create tcp channel because of exception.", e);
            throw new IllegalStateException("Fail to create tcp channel because of exception.", e);
        }
    }
}
