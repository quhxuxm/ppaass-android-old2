package com.ppaass.agent.service;

import android.net.VpnService;
import android.util.Log;
import io.netty.channel.ChannelFactory;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.io.IOException;
import java.nio.channels.SocketChannel;

public class PpaassVpnTcpChannelFactory implements ChannelFactory<NioSocketChannel> {
    private final VpnService vpnService;

    public PpaassVpnTcpChannelFactory(VpnService vpnService) {
        this.vpnService = vpnService;
    }

    @Override
    public NioSocketChannel newChannel() {
        try {
            SocketChannel javaSocketChannel = SocketChannel.open();
            this.vpnService.protect(javaSocketChannel.socket());
            return new NioSocketChannel(javaSocketChannel);
        } catch (IOException e) {
            Log.e(PpaassVpnTcpChannelFactory.class.getName(), "Fail to create tcp channel because of exception.", e);
            throw new IllegalStateException("Fail to create tcp channel because of exception.", e);
        }
    }
}
