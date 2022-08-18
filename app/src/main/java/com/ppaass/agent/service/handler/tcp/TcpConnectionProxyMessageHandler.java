package com.ppaass.agent.service.handler.tcp;

import android.util.Log;
import com.ppaass.agent.protocol.message.Message;
import com.ppaass.agent.protocol.message.ProxyMessagePayload;
import com.ppaass.agent.protocol.message.ProxyMessagePayloadType;
import com.ppaass.agent.service.IVpnConst;
import com.ppaass.agent.service.handler.TcpIpPacketWriter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Promise;

public class TcpConnectionProxyMessageHandler extends SimpleChannelInboundHandler<Message> {
    private final TcpIpPacketWriter tcpIpPacketWriter;
    private final Promise<Boolean> remoteConnectStatusPromise;

    public TcpConnectionProxyMessageHandler(TcpIpPacketWriter tcpIpPacketWriter,
                                            Promise<Boolean> remoteConnectStatusPromise) {
        this.tcpIpPacketWriter = tcpIpPacketWriter;
        this.remoteConnectStatusPromise = remoteConnectStatusPromise;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        AttributeKey<TcpConnection> tcpConnectionKey = AttributeKey.valueOf(IVpnConst.TCP_CONNECTION);
        TcpConnection tcpConnection = ctx.channel().attr(tcpConnectionKey).get();
        Log.d(TcpConnectionProxyMessageHandler.class.getName(),
                "<<<<---- Tcp connection connected, begin to relay remote data to device, current connection:  " +
                        tcpConnection);
    }
//    @Override
//    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
//        AttributeKey<TcpConnection> tcpConnectionKey = AttributeKey.valueOf(IVpnConst.TCP_CONNECTION);
//        TcpConnection tcpConnection = ctx.channel().attr(tcpConnectionKey).get();
//        if (tcpConnection == null) {
//            ctx.close();
//            return;
//        }
//        if (tcpConnection.getStatus() == TcpConnectionStatus.CLOSED) {
//            Log.d(TcpConnection.class.getName(),
//                    "<<<<---- Tcp connection CLOSED already, current connection:  " +
//                            tcpConnection);
//            ctx.close();
//            return;
//        }
//        if (tcpConnection.getStatus() == TcpConnectionStatus.CLOSED_WAIT) {
//            this.tcpIpPacketWriter.writeFinAckToDevice(tcpConnection, tcpConnection.getCurrentSequenceNumber(),
//                    tcpConnection.getCurrentAcknowledgementNumber());
//            tcpConnection.setStatus(TcpConnectionStatus.LAST_ACK);
//            tcpConnection.setCurrentSequenceNumber(tcpConnection.getCurrentSequenceNumber() + 1);
//            Log.d(TcpConnection.class.getName(),
//                    "<<<<---- Move tcp connection from CLOSED_WAIT to  LAST_ACK, current connection:  " +
//                            tcpConnection);
//        }
//    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Message proxyMessage) {
        AttributeKey<TcpConnection> tcpConnectionKey = AttributeKey.valueOf(IVpnConst.TCP_CONNECTION);
        TcpConnection tcpConnection = ctx.channel().attr(tcpConnectionKey).get();
        //Relay remote data to device and use mss as the transfer unit
        byte[] proxyMessagePayloadBytes = proxyMessage.getPayload();
        ProxyMessagePayload proxyMessagePayload =
                PpaassMessageUtil.INSTANCE.parseProxyMessagePayloadBytes(proxyMessagePayloadBytes);
        if (ProxyMessagePayloadType.TcpConnectSuccess == proxyMessagePayload.getPayloadType()) {
            this.remoteConnectStatusPromise.setSuccess(true);
            return;
        }
        if (ProxyMessagePayloadType.TcpConnectFail == proxyMessagePayload.getPayloadType()) {
            this.remoteConnectStatusPromise.setFailure(new IllegalStateException("Proxy connect to remote fail"));
            return;
        }
        if (ProxyMessagePayloadType.TcpData == proxyMessagePayload.getPayloadType()) {
            ByteBuf remoteDataBuf = Unpooled.wrappedBuffer(proxyMessagePayload.getData());
            while (remoteDataBuf.isReadable()) {
                int mssDataLength = Math.min(IVpnConst.TCP_MSS, remoteDataBuf.readableBytes());
                byte[] mssData = new byte[mssDataLength];
                remoteDataBuf.readBytes(mssData);
                this.tcpIpPacketWriter.writeAckToDevice(mssData, tcpConnection,
                        tcpConnection.getCurrentSequenceNumber(), tcpConnection.getCurrentAcknowledgementNumber());
                // Data should write to device first then increase the sequence number
                if (!tcpConnection.compareAndSetCurrentSequenceNumber(tcpConnection.getCurrentSequenceNumber(),
                        tcpConnection.getCurrentSequenceNumber() + mssData.length)) {
                    Log.e(TcpConnectionProxyMessageHandler.class.getName(),
                            "<<<<---- Fail to receive remote data write ack to device because of concurrent set on connection sequence, current connection: " +
                                    tcpConnection + ", remote data size: " + mssData.length);
                    return;
                }
                Log.d(TcpConnectionProxyMessageHandler.class.getName(),
                        "<<<<---- Receive remote data write ack to device, current connection: " +
                                tcpConnection + ", remote data size: " + mssData.length);
                Log.v(TcpConnectionProxyMessageHandler.class.getName(),
                        "<<<<---- Remote data:\n\n" + ByteBufUtil.prettyHexDump(Unpooled.wrappedBuffer(mssData)) +
                                "\n\n");
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
                "<<<<---- Tcp connection remote channel closed, current connection: " + tcpConnection);
        tcpConnection.finallyCloseTcpConnection();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        AttributeKey<TcpConnection> tcpConnectionKey = AttributeKey.valueOf(IVpnConst.TCP_CONNECTION);
        TcpConnection tcpConnection = ctx.channel().attr(tcpConnectionKey).get();
        Log.e(TcpConnectionProxyMessageHandler.class.getName(),
                "<<<<---- Tcp connection exception happen on remote channel, current connection: " +
                        tcpConnection, cause);
        ctx.close();
    }
}
