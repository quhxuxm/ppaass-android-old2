package com.ppaass.agent.service.handler.tcp;

import android.util.Log;
import com.ppaass.agent.protocol.message.*;
import com.ppaass.agent.service.IVpnConst;
import com.ppaass.agent.service.handler.ITcpIpPacketWriter;
import com.ppaass.agent.service.handler.PpaassMessageUtil;
import com.ppaass.agent.util.UUIDUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Promise;

public class TcpConnectionProxyMessageHandler extends SimpleChannelInboundHandler<Message> {
    private final ITcpIpPacketWriter tcpIpPacketWriter;
    private final Promise<Channel> proxyChannelPromise;

    public TcpConnectionProxyMessageHandler(ITcpIpPacketWriter tcpIpPacketWriter,
                                            Promise<Channel> proxyChannelPromise) {
        this.tcpIpPacketWriter = tcpIpPacketWriter;
        this.proxyChannelPromise = proxyChannelPromise;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        AttributeKey<TcpConnection> tcpConnectionKey = AttributeKey.valueOf(IVpnConst.TCP_CONNECTION);
        TcpConnection tcpConnection = ctx.channel().attr(tcpConnectionKey).get();
        Log.d(TcpConnectionProxyMessageHandler.class.getName(),
                "---->>>> Tcp connection activated, begin to relay remote data to device, current connection:  " +
                        tcpConnection);
        Message messageConnectToRemote = new Message();
        messageConnectToRemote.setId(UUIDUtil.INSTANCE.generateUuid());
        messageConnectToRemote.setUserToken(IVpnConst.PPAASS_PROXY_USER_TOKEN);
        messageConnectToRemote.setPayloadEncryption(
                new PayloadEncryption(PayloadEncryptionType.Aes, UUIDUtil.INSTANCE.generateUuidInBytes()));
        AgentMessagePayload connectToRemoteMessagePayload = new AgentMessagePayload();
        connectToRemoteMessagePayload.setPayloadType(AgentMessagePayloadType.TcpConnect);
        NetAddress sourceAddress = new NetAddress(NetAddressType.IpV4, new NetAddressValue(
                tcpConnection.getRepositoryKey().getSourceAddress(),
                tcpConnection.getRepositoryKey().getSourcePort()
        ));
        connectToRemoteMessagePayload.setSourceAddress(sourceAddress);
        NetAddress targetAddress = new NetAddress(NetAddressType.IpV4, new NetAddressValue(
                tcpConnection.getRepositoryKey().getDestinationAddress(),
                tcpConnection.getRepositoryKey().getDestinationPort()
        ));
        connectToRemoteMessagePayload.setTargetAddress(targetAddress);
        messageConnectToRemote.setPayload(
                PpaassMessageUtil.INSTANCE.generateAgentMessagePayloadBytes(
                        connectToRemoteMessagePayload));
        ctx.channel().writeAndFlush(messageConnectToRemote);
        Log.d(TcpConnectionProxyMessageHandler.class.getName(),
                "---->>>> Tcp connection write [TcpConnect] to proxy, current connection:  " +
                        tcpConnection);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Message proxyMessage) {
        AttributeKey<TcpConnection> tcpConnectionKey = AttributeKey.valueOf(IVpnConst.TCP_CONNECTION);
        TcpConnection tcpConnection = ctx.channel().attr(tcpConnectionKey).get();
        tcpConnection.setLatestActiveTime();
        var tcpInboundPacketTimestampAttr = ctx.channel().attr(IVpnConst.TCP_INBOUND_PACKET_TIMESTAMP);
        byte[] tcpInboundPacketTimestamp = null;
        if (tcpInboundPacketTimestampAttr != null) {
            tcpInboundPacketTimestamp = tcpInboundPacketTimestampAttr.get();
        }
        //Relay remote data to device and use mss as the transfer unit
        byte[] proxyMessagePayloadBytes = proxyMessage.getPayload();
        ProxyMessagePayload proxyMessagePayload =
                PpaassMessageUtil.INSTANCE.parseProxyMessagePayloadBytes(proxyMessagePayloadBytes);
        if (ProxyMessagePayloadType.TcpConnectSuccess == proxyMessagePayload.getPayloadType()) {
            this.proxyChannelPromise.setSuccess(ctx.channel());
            Log.d(TcpConnectionProxyMessageHandler.class.getName(),
                    "<<<<---- Tcp connection connected to proxy already, current connection:  " +
                            tcpConnection);
            return;
        }
        if (ProxyMessagePayloadType.TcpConnectFail == proxyMessagePayload.getPayloadType()) {
            this.proxyChannelPromise.setFailure(new IllegalStateException("Proxy connect to remote fail"));
            Log.e(TcpConnectionProxyMessageHandler.class.getName(),
                    "<<<<---- Tcp connection fail connected to proxy already, current connection:  " +
                            tcpConnection);
            return;
        }
        if (ProxyMessagePayloadType.TcpDataSuccess == proxyMessagePayload.getPayloadType()) {
            ByteBuf remoteDataBuf = Unpooled.wrappedBuffer(proxyMessagePayload.getData());
            while (remoteDataBuf.isReadable()) {
                int mssDataLength = Math.min(IVpnConst.TCP_MSS, remoteDataBuf.readableBytes());
                byte[] mssData = new byte[mssDataLength];
                remoteDataBuf.readBytes(mssData);
                Log.d(TcpConnectionProxyMessageHandler.class.getName(),
                        "<<<<---- Receive remote data write ack to device, current connection: " +
                                tcpConnection + ", remote data size: " + mssData.length);
                Log.v(TcpConnectionProxyMessageHandler.class.getName(),
                        "<<<<---- Remote data for current connection: " + tcpConnection + ", remote data:" +
                                ByteBufUtil.prettyHexDump(Unpooled.wrappedBuffer(mssData)));
                this.tcpIpPacketWriter.writeAckToDevice(mssData, tcpConnection,
                        tcpConnection.getCurrentSequenceNumber().get(),
                        tcpConnection.getCurrentAcknowledgementNumber().get(), tcpInboundPacketTimestamp);
                // Data should write to device first then increase the sequence number
                tcpConnection.getCurrentSequenceNumber().getAndAdd(mssData.length);
            }
            return;
        }
        if (ProxyMessagePayloadType.HeartbeatSuccess == proxyMessagePayload.getPayloadType()) {
            return;
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        AttributeKey<TcpConnection> tcpConnectionKey = AttributeKey.valueOf(IVpnConst.TCP_CONNECTION);
        TcpConnection tcpConnection = ctx.channel().attr(tcpConnectionKey).get();
        Log.d(TcpConnectionProxyMessageHandler.class.getName(),
                "<<<<---- Tcp connection remote channel closed, current connection: " +
                        tcpConnection);
        if (tcpConnection.getStatus().get() == TcpConnectionStatus.ESTABLISHED) {
            tcpConnection.getStatus().set(TcpConnectionStatus.FIN_WAIT1);
            this.tcpIpPacketWriter.writeFinToDevice(tcpConnection,
                    tcpConnection.getCurrentSequenceNumber().get(),
                    tcpConnection.getCurrentAcknowledgementNumber().get());
            Log.d(TcpConnectionProxyMessageHandler.class.getName(),
                    "<<<<---- Tcp connection remote channel closed send Fin to device, current connection: " +
                            tcpConnection);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        AttributeKey<TcpConnection> tcpConnectionKey = AttributeKey.valueOf(IVpnConst.TCP_CONNECTION);
        TcpConnection tcpConnection = ctx.channel().attr(tcpConnectionKey).get();
        Log.e(TcpConnectionProxyMessageHandler.class.getName(),
                "<<<<---- Tcp connection exception happen on remote channel, current connection: " +
                        tcpConnection, cause);
    }
}
