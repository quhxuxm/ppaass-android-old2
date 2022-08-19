package com.ppaass.agent.service.handler.tcp;

import android.net.VpnService;
import android.util.Log;
import androidx.annotation.NonNull;
import com.ppaass.agent.protocol.general.tcp.TcpHeader;
import com.ppaass.agent.protocol.general.tcp.TcpPacket;
import com.ppaass.agent.protocol.message.*;
import com.ppaass.agent.service.IVpnConst;
import com.ppaass.agent.service.PpaassVpnTcpChannelFactory;
import com.ppaass.agent.service.handler.TcpIpPacketWriter;
import com.ppaass.agent.util.UUIDUtil;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.Promise;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Comparator;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class TcpConnection implements Runnable {
    private static final AtomicInteger INITIAL_SEQ = new AtomicInteger();

    static {
        // Set the initial sequence number, which will be used across all tcp connection,
        // it is increase for each tcp connection
        Random random = new Random();
        int nextRandom = Math.abs(random.nextInt());
        INITIAL_SEQ.set(nextRandom);
    }

    private final String id;
    private final AtomicLong latestActiveTime;
    private final TcpConnectionRepositoryKey repositoryKey;
    private Channel proxyChannel;
    private final TcpIpPacketWriter tcpIpPacketWriter;
    private final BlockingQueue<TcpPacket> deviceInboundQueue;
    private final ITcpConnectionManager connectionManager;
    private final AtomicReference<TcpConnectionStatus> status;
    private final AtomicLong currentSequenceNumber;
    private final AtomicLong currentAcknowledgementNumber;
    private final AtomicLong deviceInitialSequenceNumber;
    private final AtomicLong vpnInitialSequenceNumber;
    private final VpnService vpnService;
    private final Bootstrap remoteBootstrap;
    private final Promise<Boolean> remoteConnectStatusPromise;

    public TcpConnection(TcpConnectionRepositoryKey repositoryKey, TcpIpPacketWriter tcpIpPacketWriter,
                         ITcpConnectionManager connectionManager,
                         VpnService vpnService) {
        this.id = UUID.randomUUID().toString().replace("-", "");
        this.latestActiveTime = new AtomicLong(System.currentTimeMillis());
        this.repositoryKey = repositoryKey;
        this.status = new AtomicReference<>(TcpConnectionStatus.LISTEN);
        this.currentAcknowledgementNumber = new AtomicLong(0);
        this.currentSequenceNumber = new AtomicLong(0);
        this.deviceInitialSequenceNumber = new AtomicLong(0);
        this.vpnInitialSequenceNumber = new AtomicLong(0);
        this.deviceInboundQueue =
                new PriorityBlockingQueue<>(1024, Comparator.comparingLong(p -> p.getHeader().getSequenceNumber()));
        this.tcpIpPacketWriter = tcpIpPacketWriter;
        this.connectionManager = connectionManager;
        this.vpnService = vpnService;
        this.remoteBootstrap = this.createBootstrap();
        this.remoteConnectStatusPromise = new DefaultPromise<>(this.remoteBootstrap.config().group().next());
    }

    public void run() {
        while (this.status.get() != TcpConnectionStatus.CLOSED) {
            try {
                TcpPacket deviceInboundTcpPacket = this.deviceInboundQueue.take();
                this.setLatestActiveTime();
                Log.d(TcpConnection.class.getName(),
                        ">>>>>>>> Success take tcp packet from device inbound, current connection: " + this +
                                "; device inbound tcp packet: " + deviceInboundTcpPacket +
                                "; device inbound queue size: " + deviceInboundQueue.size());
                TcpHeader deviceInboundTcpHeader = deviceInboundTcpPacket.getHeader();
                if (deviceInboundTcpHeader.isRst()) {
                    // Receive rst , close the connection directly.
                    Log.d(TcpConnection.class.getName(),
                            "Receive rst, close connection, current connection: " + this +
                                    "; device inbound tcp packet: " + deviceInboundTcpPacket);
                    this.tcpIpPacketWriter.writeRstAckToDevice(this, this.currentSequenceNumber.get(),
                            this.currentAcknowledgementNumber.get());
                    this.finallyCloseTcpConnection();
                    return;
                }
                if (deviceInboundTcpHeader.isSyn() && !deviceInboundTcpHeader.isAck()) {
                    // Receive sync from device inbound, the connection status must be LISTEN,
                    // for other status it is duplicate packet.
                    if (this.status.get() == TcpConnectionStatus.SYNC_RCVD ||
                            this.status.get() == TcpConnectionStatus.ESTABLISHED) {
                        Log.w(TcpConnection.class.getName(),
                                "Receive duplicate syn from device, current connection: " + this +
                                        "; device inbound tcp packet: " + deviceInboundTcpPacket);
                        continue;
                    }
                    if (this.status.get() != TcpConnectionStatus.LISTEN) {
                        Log.e(TcpConnection.class.getName(),
                                "Receive duplicate syn from device, current connection[LISTEN]: " + this +
                                        "; device inbound tcp packet: " + deviceInboundTcpPacket);
                        this.tcpIpPacketWriter.writeRstToDevice(this, this.currentSequenceNumber.get(),
                                this.currentAcknowledgementNumber.get());
                        this.finallyCloseTcpConnection();
                        continue;
                    }
                    //Initialize ack number and seq number
                    this.deviceInitialSequenceNumber.set(deviceInboundTcpHeader.getSequenceNumber());
                    this.currentAcknowledgementNumber.set(deviceInboundTcpHeader.getSequenceNumber());
                    long vpnIsn = this.generateVpnInitialSequenceNumber();
                    this.currentSequenceNumber.set(vpnIsn);
                    this.vpnInitialSequenceNumber.set(vpnIsn);
                    this.status.set(TcpConnectionStatus.SYNC_RCVD);
                    this.tcpIpPacketWriter.writeSyncAckToDevice(this, this.currentSequenceNumber.get(),
                            this.currentAcknowledgementNumber.get() + 1);
                    Log.d(TcpConnection.class.getName(),
                            ">>>>>>>> Receive sync and do sync ack, current connection: " + this +
                                    "; device inbound tcp packet: " + deviceInboundTcpPacket);
                    continue;
                }
                if (deviceInboundTcpHeader.isAck() && !deviceInboundTcpHeader.isFin()) {
                    if (deviceInboundTcpHeader.isRst()) {
                        // Receive rst and ack, close the connection directly.
                        Log.d(TcpConnection.class.getName(),
                                "Receive rst and ack, close connection, current connection: " + this +
                                        "; device inbound tcp packet: " + deviceInboundTcpPacket);
                        this.finallyCloseTcpConnection();
                        return;
                    }
                    if (deviceInboundTcpHeader.getSequenceNumber() < this.currentAcknowledgementNumber.get()) {
                        // This is a resend packet from device, which means previous device data is received by vpn
                        // but client sent again because of timer expired, in this case vpn should ignore this packet.
                        Log.d(TcpConnection.class.getName(),
                                ">>>>>>>> Receive ack of resend packet(client timer expired), ignore this packet and continue next, current connection: " +
                                        this + "; device inbound tcp packet: " + deviceInboundTcpPacket);
                        continue;
                    }
                    // Device receive vpn data and do ack and vpn receive the corresponding ack or
                    // this is a resend packet from device, which means previous device data not received by vpn.
                    if (this.status.get() == TcpConnectionStatus.SYNC_RCVD) {
                        // Receive ack of previous sync-ack.
                        if (this.currentSequenceNumber.get() + 1 !=
                                deviceInboundTcpHeader.getAcknowledgementNumber()) {
                            Log.e(TcpConnection.class.getName(),
                                    ">>>>>>>> Connection current seq number do not match incoming ack number:  " +
                                            this + "; device inbound tcp packet: " + deviceInboundTcpPacket);
                            tcpIpPacketWriter.writeRstToDevice(this,
                                    deviceInboundTcpHeader.getAcknowledgementNumber(),
                                    deviceInboundTcpHeader.getSequenceNumber());
                            this.finallyCloseTcpConnection();
                            return;
                        }
                        if (this.currentAcknowledgementNumber.get() + 1 !=
                                deviceInboundTcpHeader.getSequenceNumber()) {
                            Log.e(TcpConnection.class.getName(),
                                    ">>>>>>>> Connection current ack number do not match incoming seq number:  " +
                                            this + "; device inbound tcp packet: " + deviceInboundTcpPacket);
                            tcpIpPacketWriter.writeRstToDevice(this,
                                    deviceInboundTcpHeader.getAcknowledgementNumber(),
                                    deviceInboundTcpHeader.getSequenceNumber());
                            this.finallyCloseTcpConnection();
                            return;
                        }
                        this.proxyChannel = this.connectProxy();
                        if (this.proxyChannel == null) {
                            Log.e(TcpConnection.class.getName(),
                                    ">>>>>>>> Connect to proxy fail(NULL), current connection:  " + this +
                                            "; device inbound tcp packet: " + deviceInboundTcpPacket);
                            tcpIpPacketWriter.writeRstToDevice(this,
                                    deviceInboundTcpHeader.getAcknowledgementNumber(),
                                    deviceInboundTcpHeader.getSequenceNumber());
                            this.finallyCloseTcpConnection();
                            return;
                        }
                        //Connect to Ppaass Proxy start
                        boolean completeInTime = this.remoteConnectStatusPromise.await(10000);
                        if (completeInTime && this.remoteConnectStatusPromise.isSuccess()) {
                            Log.d(TcpConnection.class.getName(),
                                    ">>>>>>>> Connect to remote through proxy success, current connection: " +
                                            this + "; device inbound tcp packet: " + deviceInboundTcpPacket);
                            this.currentSequenceNumber.getAndIncrement();
                            this.currentAcknowledgementNumber.getAndIncrement();
                            this.status.set(TcpConnectionStatus.ESTABLISHED);
                            Log.d(TcpConnection.class.getName(),
                                    ">>>>>>>> Receive ack and remote connection established; current connection: " +
                                            this + "; device inbound tcp packet: " + deviceInboundTcpPacket);
                            continue;
                        }
                        Log.e(TcpConnection.class.getName(),
                                ">>>>>>>> Connect to remote through proxy fail, current connection: " + this +
                                        "; device inbound tcp packet: " + deviceInboundTcpPacket);
                        tcpIpPacketWriter.writeRstToDevice(this,
                                deviceInboundTcpHeader.getAcknowledgementNumber(),
                                deviceInboundTcpHeader.getSequenceNumber());
                        this.finallyCloseTcpConnection();
                        return;
                    }
                    if (this.status.get() == TcpConnectionStatus.ESTABLISHED) {
                        Log.d(TcpConnection.class.getName(),
                                ">>>>>>>> Receive ACK on established connection - (PSH=" +
                                        deviceInboundTcpPacket.getHeader().isPsh() +
                                        ") and forward device inbound data to remote, current connection:  " +
                                        this + "; device inbound tcp packet: " + deviceInboundTcpPacket +
                                        "; device inbound queue size: " + this.deviceInboundQueue.size());
                        if (deviceInboundTcpPacket.getData().length > 0) {
                            Message messageRelayToRemote = new Message();
                            messageRelayToRemote.setId(UUIDUtil.INSTANCE.generateUuid());
                            messageRelayToRemote.setUserToken(IVpnConst.PPAASS_PROXY_USER_TOKEN);
                            messageRelayToRemote.setPayloadEncryptionType(PayloadEncryptionType.Aes);
                            messageRelayToRemote.setPayloadEncryptionToken(UUIDUtil.INSTANCE.generateUuidInBytes());
                            AgentMessagePayload agentMessagePayload = new AgentMessagePayload();
                            agentMessagePayload.setData(deviceInboundTcpPacket.getData());
                            agentMessagePayload.setPayloadType(AgentMessagePayloadType.TcpData);
                            NetAddress sourceAddress = new NetAddress();
                            sourceAddress.setHost(this.repositoryKey.getSourceAddress());
                            sourceAddress.setPort((short) this.repositoryKey.getSourcePort());
                            sourceAddress.setType(NetAddressType.IpV4);
                            agentMessagePayload.setSourceAddress(sourceAddress);
                            NetAddress targetAddress = new NetAddress();
                            targetAddress.setHost(this.repositoryKey.getDestinationAddress());
                            targetAddress.setPort((short) this.repositoryKey.getDestinationPort());
                            targetAddress.setType(NetAddressType.IpV4);
                            agentMessagePayload.setTargetAddress(targetAddress);
                            messageRelayToRemote.setPayload(
                                    PpaassMessageUtil.INSTANCE.generateAgentMessagePayloadBytes(
                                            agentMessagePayload));
                            this.proxyChannel.writeAndFlush(messageRelayToRemote);
                            this.currentAcknowledgementNumber.getAndAdd(deviceInboundTcpPacket.getData().length);
                            this.tcpIpPacketWriter.writeAckToDevice(null, this, this.currentSequenceNumber.get(),
                                    this.currentAcknowledgementNumber.get());
                        }
                        continue;
                    }
                    if (this.status.get() == TcpConnectionStatus.FIN_WAIT1) {
                        this.status.set(TcpConnectionStatus.FIN_WAIT2);
                        continue;
                    }
                    if (this.status.get() == TcpConnectionStatus.LAST_ACK) {
                        // Finally close the tcp connection after receive Fin from client.
                        Log.d(TcpConnection.class.getName(),
                                ">>>>>>>> Receive last ack, close connection: " + this +
                                        "; device inbound tcp packet: " + deviceInboundTcpPacket);
                        this.finallyCloseTcpConnection();
                        return;
                    }
                    Log.e(TcpConnection.class.getName(),
                            ">>>>>>>> Incorrect tcp header(1), close connection; current connection: " + this +
                                    "; device inbound tcp packet: " + deviceInboundTcpPacket);
                    this.tcpIpPacketWriter.writeRstToDevice(this,
                            deviceInboundTcpHeader.getAcknowledgementNumber(),
                            deviceInboundTcpHeader.getSequenceNumber());
                    this.finallyCloseTcpConnection();
                    return;
                }
                if (deviceInboundTcpHeader.isFin()) {
                    //Receive Fin on established status.
                    if (this.status.get() == TcpConnectionStatus.ESTABLISHED) {
                        //After fin, no more device data will be sent to vpn
                        Log.d(TcpConnection.class.getName(),
                                ">>>>>>>> Receive FIN when connection ESTABLISHED, current connection: " + this +
                                        "; device inbound tcp packet: " + deviceInboundTcpPacket);
                        this.status.set(TcpConnectionStatus.CLOSED_WAIT);
                        this.tcpIpPacketWriter.writeAckToDevice(null, this, this.currentSequenceNumber.get(),
                                this.currentAcknowledgementNumber.get() + 1);
                        if (deviceInboundTcpPacket.getData().length > 0) {
                            if (deviceInboundTcpHeader.getSequenceNumber() < this.currentAcknowledgementNumber.get()) {
                                // This is a resend packet from device, which means previous device data is received by vpn
                                // but client sent again because of timer expired, in this case vpn should ignore this packet.
                                Log.d(TcpConnection.class.getName(),
                                        ">>>>>>>> Receive FIN with data of resend packet(client timer expired), ignore this packet and continue next, current connection: " +
                                                this + "; device inbound tcp packet: " + deviceInboundTcpPacket);
                                continue;
                            }
                            if (deviceInboundTcpHeader.getSequenceNumber() >= this.currentAcknowledgementNumber.get()) {
                                Log.d(TcpConnection.class.getName(),
                                        ">>>>>>>> Receive FIN with data on established connection - (PSH=" +
                                                deviceInboundTcpPacket.getHeader().isPsh() +
                                                ") and forward device inbound data to remote, current connection:  " +
                                                this + "; device inbound tcp packet: " + deviceInboundTcpPacket +
                                                "; device inbound queue size: " + this.deviceInboundQueue.size());
                                Message messageRelayToRemote = new Message();
                                messageRelayToRemote.setId(UUIDUtil.INSTANCE.generateUuid());
                                messageRelayToRemote.setUserToken(IVpnConst.PPAASS_PROXY_USER_TOKEN);
                                messageRelayToRemote.setPayloadEncryptionType(PayloadEncryptionType.Aes);
                                messageRelayToRemote.setPayloadEncryptionToken(UUIDUtil.INSTANCE.generateUuidInBytes());
                                AgentMessagePayload agentMessagePayload = new AgentMessagePayload();
                                agentMessagePayload.setData(deviceInboundTcpPacket.getData());
                                agentMessagePayload.setPayloadType(AgentMessagePayloadType.TcpData);
                                NetAddress sourceAddress = new NetAddress();
                                sourceAddress.setHost(this.repositoryKey.getSourceAddress());
                                sourceAddress.setPort((short) this.repositoryKey.getSourcePort());
                                sourceAddress.setType(NetAddressType.IpV4);
                                agentMessagePayload.setSourceAddress(sourceAddress);
                                NetAddress targetAddress = new NetAddress();
                                targetAddress.setHost(this.repositoryKey.getDestinationAddress());
                                targetAddress.setPort((short) this.repositoryKey.getDestinationPort());
                                targetAddress.setType(NetAddressType.IpV4);
                                agentMessagePayload.setTargetAddress(targetAddress);
                                messageRelayToRemote.setPayload(
                                        PpaassMessageUtil.INSTANCE.generateAgentMessagePayloadBytes(
                                                agentMessagePayload));
                                this.proxyChannel.writeAndFlush(messageRelayToRemote);
                                this.currentAcknowledgementNumber.getAndAdd(deviceInboundTcpPacket.getData().length);
                                this.tcpIpPacketWriter.writeAckToDevice(null, this, this.currentSequenceNumber.get(),
                                        this.currentAcknowledgementNumber.get());
                            }
                        }
                        this.proxyChannel.close().sync();
                        this.remoteBootstrap.config().group().shutdownGracefully();
                        this.tcpIpPacketWriter.writeFinAckToDevice(this, this.currentSequenceNumber.get(),
                                this.currentAcknowledgementNumber.get() + 1);
                        this.currentAcknowledgementNumber.getAndIncrement();
                        continue;
                    }
                    if (deviceInboundTcpHeader.isAck()) {
                        this.finallyCloseTcpConnection();
                        return;
                    }
                    Log.e(TcpConnection.class.getName(),
                            ">>>>>>>> Incorrect tcp header(3), close connection, current connection: " + this +
                                    "; device inbound tcp packet: " + deviceInboundTcpPacket);
                    this.tcpIpPacketWriter.writeRstToDevice(this, deviceInboundTcpHeader.getAcknowledgementNumber(),
                            deviceInboundTcpHeader.getSequenceNumber());
                    this.finallyCloseTcpConnection();
                    return;
                }
                Log.e(TcpConnection.class.getName(),
                        ">>>>>>>> Incorrect tcp header(4), close connection, current connection: " + this +
                                ", device inbound tcp packet: " + deviceInboundTcpPacket);
                this.tcpIpPacketWriter.writeRstToDevice(this, deviceInboundTcpHeader.getAcknowledgementNumber(),
                        deviceInboundTcpHeader.getSequenceNumber());
                this.finallyCloseTcpConnection();
                return;
            } catch (Exception e) {
                Log.e(TcpConnection.class.getName(),
                        ">>>>>>>> Exception happen, close connection, current connection: " + this, e);
                this.tcpIpPacketWriter.writeRstToDevice(this, this.getCurrentSequenceNumber(),
                        this.currentAcknowledgementNumber.get());
                this.finallyCloseTcpConnection();
                return;
            } finally {
                Log.d(TcpConnection.class.getName(),
                        "######## Current connection: " + this + "; device inbound queue size: " +
                                this.deviceInboundQueue.size());
            }
        }
    }

    public long getLatestActiveTime() {
        return latestActiveTime.get();
    }

    public void setLatestActiveTime() {
        this.latestActiveTime.set(System.currentTimeMillis());
    }

    public TcpConnectionStatus getStatus() {
        return status.get();
    }

    private Bootstrap createBootstrap() {
        Bootstrap result = new Bootstrap();
        result.group(new NioEventLoopGroup(8));
        result.channelFactory(new PpaassVpnTcpChannelFactory(this.vpnService));
        result.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 120 * 1000);
        result.option(ChannelOption.SO_TIMEOUT, 120 * 1000);
        result.option(ChannelOption.SO_KEEPALIVE, false);
        result.option(ChannelOption.AUTO_READ, true);
        result.option(ChannelOption.AUTO_CLOSE, false);
        result.option(ChannelOption.TCP_NODELAY, true);
        result.option(ChannelOption.SO_REUSEADDR, true);
        result.handler(new ChannelInitializer<NioSocketChannel>() {
            @Override
            protected void initChannel(@NonNull NioSocketChannel ch) {
                ch.pipeline().addLast(new PpaassMessageDecoder());
                ch.pipeline().addLast(new TcpConnectionProxyMessageHandler(TcpConnection.this.tcpIpPacketWriter,
                        TcpConnection.this.remoteConnectStatusPromise));
                ch.pipeline().addLast(new PpaassMessageEncoder(false));
            }
        });
        return result;
    }

    private int generateVpnInitialSequenceNumber() {
        return INITIAL_SEQ.getAndIncrement();
    }

    public void finallyCloseTcpConnection() {
        this.deviceInboundQueue.clear();
        try {
            if (proxyChannel != null) {
                this.proxyChannel.close();
            }
        } catch (Exception e) {
            Log.e(TcpConnection.class.getName(),
                    ">>>>>>>> Fail to close remote channel, current connection: " + this + ", device inbound size: " +
                            deviceInboundQueue.size(), e);
        }
        this.remoteBootstrap.config().group().shutdownGracefully();
        this.connectionManager.closeConnection(this.repositoryKey);
        this.status.set(TcpConnectionStatus.CLOSED);
        Log.d(TcpConnection.class.getName(),
                ">>>>>>>> Tcp connection closed, current tcp connection: " + this);
    }

    private Channel connectProxy() throws Exception {
        AttributeKey<TcpConnection> tcpConnectionKey = AttributeKey.valueOf(IVpnConst.TCP_CONNECTION);
        InetSocketAddress proxyAddress =
                new InetSocketAddress(InetAddress.getByName(IVpnConst.PPAASS_PROXY_IP), IVpnConst.PPAASS_PROXY_PORT);
        ChannelFuture channelFuture = this.remoteBootstrap.connect(proxyAddress);
        channelFuture.channel().attr(tcpConnectionKey).setIfAbsent(this);
        channelFuture = channelFuture.sync();
        if (channelFuture.isSuccess()) {
            Log.d(TcpConnection.class.getName(),
                    ">>>>>>>> Success create proxy channel: " + proxyAddress + ", current tcp connection: " + this);
            return channelFuture.channel();
        } else {
            Log.e(TcpConnection.class.getName(),
                    ">>>>>>>> Fail to create proxy channel: " + proxyAddress + ", current tcp connection: " + this,
                    channelFuture.cause());
            return null;
        }
    }

    /**
     * Receive device inbound data from another thread.
     *
     * @param tcpPacket The device inbound data.
     * @throws Exception The exception when put device data into inbound queue
     */
    public void onDeviceInbound(TcpPacket tcpPacket) throws Exception {
        this.deviceInboundQueue.put(tcpPacket);
    }

    public TcpConnectionRepositoryKey getRepositoryKey() {
        return repositoryKey;
    }

    public long getCurrentAcknowledgementNumber() {
        return currentAcknowledgementNumber.get();
    }

    public long getCurrentSequenceNumber() {
        return currentSequenceNumber.get();
    }

    public boolean compareAndSetCurrentSequenceNumber(long previousSequenceNumber, long currentSequenceNumber) {
        return this.currentSequenceNumber.compareAndSet(previousSequenceNumber, currentSequenceNumber);
    }

    public boolean compareAndSetStatus(TcpConnectionStatus previousStatus, TcpConnectionStatus currentStatus) {
        return this.status.compareAndSet(previousStatus, currentStatus);
    }

    private long countRelativeVpnSequenceNumber() {
        return this.currentSequenceNumber.get() - this.vpnInitialSequenceNumber.get();
    }

    private long countRelativeDeviceSequenceNumber() {
        return this.currentAcknowledgementNumber.get() - this.deviceInitialSequenceNumber.get();
    }

    @Override
    public String toString() {
        return "TcpConnection{id='" + id + '\'' + ", repositoryKey=" + repositoryKey + ", status=" + status +
                ", currentSequenceNumber=" + currentSequenceNumber + ", (Current Relative VPN Sequence Number)=" +
                this.countRelativeVpnSequenceNumber() + ", currentAcknowledgementNumber=" +
                currentAcknowledgementNumber + ", (Current Relative VPN Acknowledgement Number)=" +
                this.countRelativeDeviceSequenceNumber() + ", deviceInitialSequenceNumber=" +
                deviceInitialSequenceNumber + ", vpnInitialSequenceNumber=" + vpnInitialSequenceNumber + '}';
    }
}
