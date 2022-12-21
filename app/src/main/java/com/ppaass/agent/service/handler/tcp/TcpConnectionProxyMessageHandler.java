package com.ppaass.agent.service.handler.tcp;

import android.util.Log;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ppaass.agent.protocol.message.*;
import com.ppaass.agent.protocol.message.address.PpaassNetAddress;
import com.ppaass.agent.protocol.message.address.PpaassNetAddressIpValue;
import com.ppaass.agent.protocol.message.address.PpaassNetAddressType;
import com.ppaass.agent.protocol.message.encryption.PpaassMessagePayloadEncryption;
import com.ppaass.agent.protocol.message.encryption.PpaassMessagePayloadEncryptionType;
import com.ppaass.agent.protocol.message.payload.TcpLoopInitRequestPayload;
import com.ppaass.agent.protocol.message.payload.TcpLoopInitResponsePayload;
import com.ppaass.agent.protocol.message.payload.TcpLoopInitResponseType;
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

import java.io.IOException;

public class TcpConnectionProxyMessageHandler extends SimpleChannelInboundHandler<PpaassMessage> {
    private final ITcpIpPacketWriter tcpIpPacketWriter;
    private final Promise<Channel> proxyChannelPromise;
    private static final ObjectMapper OBJ_MAPPER = new ObjectMapper();

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
        PpaassMessage messageConnectToRemote = new PpaassMessage();
        messageConnectToRemote.setId(UUIDUtil.INSTANCE.generateUuid());
        messageConnectToRemote.setUserToken(IVpnConst.PPAASS_PROXY_USER_TOKEN);
        messageConnectToRemote.setPayloadEncryption(
                new PpaassMessagePayloadEncryption(PpaassMessagePayloadEncryptionType.Aes, UUIDUtil.INSTANCE.generateUuidInBytes()));
        PpaassMessageAgentPayload connectToRemoteMessagePayload = new PpaassMessageAgentPayload();
        connectToRemoteMessagePayload.setPayloadType(PpaassMessageAgentPayloadType.TcpLoopInit);

        var tcpLoopInitRequest = new TcpLoopInitRequestPayload();


        PpaassNetAddress sourceAddress = new PpaassNetAddress(PpaassNetAddressType.IpV4, new PpaassNetAddressIpValue(
                tcpConnection.getRepositoryKey().getSourceAddress(),
                tcpConnection.getRepositoryKey().getSourcePort()
        ));
        tcpLoopInitRequest.setSrcAddress(sourceAddress);
        PpaassNetAddress targetAddress = new PpaassNetAddress(PpaassNetAddressType.IpV4, new PpaassNetAddressIpValue(
                tcpConnection.getRepositoryKey().getDestinationAddress(),
                tcpConnection.getRepositoryKey().getDestinationPort()
        ));
        tcpLoopInitRequest.setDestAddress(targetAddress);
        connectToRemoteMessagePayload.setData(OBJ_MAPPER.writeValueAsBytes(tcpLoopInitRequest));
        messageConnectToRemote.setPayloadBytes(
                PpaassMessageUtil.INSTANCE.generateAgentMessagePayloadBytes(
                        connectToRemoteMessagePayload));
        ctx.channel().writeAndFlush(messageConnectToRemote);
        Log.d(TcpConnectionProxyMessageHandler.class.getName(),
                "---->>>> Tcp connection write [TcpConnect] to proxy, current connection:  " +
                        tcpConnection);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, PpaassMessage proxyMessage) throws IOException {
        AttributeKey<TcpConnection> tcpConnectionKey = AttributeKey.valueOf(IVpnConst.TCP_CONNECTION);
        TcpConnection tcpConnection = ctx.channel().attr(tcpConnectionKey).get();
        tcpConnection.setLatestActiveTime();
        var tcpInboundPacketTimestampAttr = ctx.channel().attr(IVpnConst.TCP_INBOUND_PACKET_TIMESTAMP);
        byte[] tcpInboundPacketTimestamp = null;
        if (tcpInboundPacketTimestampAttr != null) {
            tcpInboundPacketTimestamp = tcpInboundPacketTimestampAttr.get();
        }
        //Relay remote data to device and use mss as the transfer unit
        byte[] proxyMessagePayloadBytes = proxyMessage.getPayloadBytes();

        PpaassMessageProxyPayload proxyMessagePayload =
                PpaassMessageUtil.INSTANCE.parseProxyMessagePayloadBytes(proxyMessagePayloadBytes);
        if (PpaassMessageProxyPayloadType.IdleHeartbeat == proxyMessagePayload.getPayloadType()) {
            return;
        }

        if (PpaassMessageProxyPayloadType.TcpLoopInit == proxyMessagePayload.getPayloadType()) {
            var tcpLoopInitResponsePayloadBytes = proxyMessagePayload.getData();
            var tcpLoopInitResponsePayload = OBJ_MAPPER.readValue(tcpLoopInitResponsePayloadBytes, TcpLoopInitResponsePayload.class);
            if (tcpLoopInitResponsePayload.getResponseType() == TcpLoopInitResponseType.Success) {
                this.proxyChannelPromise.setSuccess(ctx.channel());
                Log.d(TcpConnectionProxyMessageHandler.class.getName(),
                        "<<<<---- Tcp connection connected to proxy already, current connection:  " +
                                tcpConnection);
                return;
            }
            this.proxyChannelPromise.setFailure(new IllegalStateException("Proxy connect to remote fail"));
            Log.e(TcpConnectionProxyMessageHandler.class.getName(),
                    "<<<<---- Tcp connection fail connected to proxy already, current connection:  " +
                            tcpConnection);
            return;
        }

        if (PpaassMessageProxyPayloadType.TcpDataSuccess == proxyMessagePayload.getPayloadType()) {
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
