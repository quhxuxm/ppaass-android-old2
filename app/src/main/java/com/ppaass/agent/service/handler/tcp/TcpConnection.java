package com.ppaass.agent.service.handler.tcp;

import android.net.VpnService;
import android.util.Log;
import androidx.annotation.NonNull;
import com.ppaass.agent.protocol.general.tcp.TcpPacket;
import com.ppaass.agent.protocol.message.*;
import com.ppaass.agent.service.IVpnConst;
import com.ppaass.agent.service.PpaassVpnNettyTcpChannelFactory;
import com.ppaass.agent.service.handler.ITcpIpPacketWriter;
import com.ppaass.agent.service.handler.PpaassMessageDecoder;
import com.ppaass.agent.service.handler.PpaassMessageEncoder;
import com.ppaass.agent.service.handler.PpaassMessageUtil;
import com.ppaass.agent.util.UUIDUtil;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class TcpConnection implements Runnable {
    private static final AtomicInteger INITIAL_SEQ = new AtomicInteger();

    static {
        // Set the initial sequence number, which will be used across all tcp connection,
        // it is increase for each tcp connection
        var random = new Random();
        var nextRandom = Math.abs(random.nextInt());
        INITIAL_SEQ.set(nextRandom);
    }

    private final String id;
    private final AtomicLong latestActiveTime;
    private final TcpConnectionRepositoryKey repositoryKey;
    private Channel proxyChannel;
    private final ITcpIpPacketWriter tcpIpPacketWriter;
    private final BlockingQueue<TcpPacket> deviceInboundQueue;
    private final AtomicReference<TcpConnectionStatus> status;
    private final AtomicLong currentSequenceNumber;
    private final AtomicLong currentAcknowledgementNumber;
    private final AtomicLong deviceInitialSequenceNumber;
    private final AtomicLong vpnInitialSequenceNumber;
    private final VpnService vpnService;
    private final Bootstrap proxyChannelBootstrap;
    private final Promise<Channel> proxyChannelConnectedPromise;

    public TcpConnection(TcpConnectionRepositoryKey repositoryKey, ITcpIpPacketWriter tcpIpPacketWriter,
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
        this.vpnService = vpnService;
        this.proxyChannelBootstrap = this.createBootstrap();
        this.proxyChannelConnectedPromise = new DefaultPromise<>(this.proxyChannelBootstrap.config().group().next());
    }

    public void run() {
        while (this.status.get() != TcpConnectionStatus.CLOSED) {
            try {
                var deviceInboundTcpPacket = this.deviceInboundQueue.take();
                this.setLatestActiveTime();
                var deviceInboundTcpHeader = deviceInboundTcpPacket.getHeader();
                int deviceInputDataLength = deviceInboundTcpPacket.getData().length;
                Log.v(TcpConnection.class.getName(),
                        ">>>>>>>> Take tcp packet from device inbound queue, current connection: " + this +
                                "; device inbound tcp packet: " + deviceInboundTcpPacket +
                                "; device inbound queue size: " + deviceInboundQueue.size() +
                                "; device inbound data size: " + deviceInputDataLength);
                if (deviceInboundTcpHeader.isRst()) {
                    this.resetConnectionByDevice(deviceInboundTcpPacket);
                    continue;
                }
                if (deviceInboundTcpHeader.isSyn() && !deviceInboundTcpHeader.isAck()) {
                    // Receive sync from device inbound, the connection status must be LISTEN,
                    // for other status it is duplicate packet.
                    this.initializeConnection(deviceInboundTcpPacket);
                    continue;
                }
                if (deviceInboundTcpHeader.isAck()) {
                    if (deviceInboundTcpHeader.getSequenceNumber() < this.currentAcknowledgementNumber.get()) {
                        // This is a resend packet from device, which means previous device data is received by vpn
                        // but client sent again because of timer expired, in this case vpn should ignore this packet.
                        Log.d(TcpConnection.class.getName(),
                                ">>>>>>>> Receive ack of resend packet(client timer expired), ignore this packet and continue next, current connection: " +
                                        this + "; device inbound tcp packet: " + deviceInboundTcpPacket);
                        continue;
                    }
                    if (TcpConnectionStatus.FIN_WAIT1 == this.status.get()) {
                        this.status.set(TcpConnectionStatus.FIN_WAIT2);
                        Log.d(TcpConnection.class.getName(),
                                ">>>>>>>> Receive ACK and begin to switch connection to FIN_WAIT2, current connection: " +
                                        this +
                                        "; device inbound tcp packet: " + deviceInboundTcpPacket);
                        continue;
                    }
                    if (TcpConnectionStatus.FIN_WAIT2 == this.status.get()) {
                        this.currentAcknowledgementNumber.getAndIncrement();
                        this.currentSequenceNumber.getAndIncrement();
                        this.tcpIpPacketWriter.writeAckToDevice(null, this,
                                this.currentSequenceNumber.get(),
                                this.currentAcknowledgementNumber.get());
                        this.status.set(TcpConnectionStatus.TIME_WAIT);
                        Log.d(TcpConnection.class.getName(),
                                ">>>>>>>> Receive ACK and begin to switch connection to TIME_WAIT, current connection: " +
                                        this +
                                        "; device inbound tcp packet: " + deviceInboundTcpPacket);
                        continue;
                    }
                    if (TcpConnectionStatus.LAST_ACK == this.status.get()) {
                        this.status.set(TcpConnectionStatus.CLOSED);
                        Log.d(TcpConnection.class.getName(),
                                ">>>>>>>> Receive ACK on LAST_ACK switch connection to CLOSED, current connection: " +
                                        this +
                                        "; device inbound tcp packet: " + deviceInboundTcpPacket);
                        continue;
                    }
                    if (deviceInboundTcpHeader.isFin()) {
                        if (TcpConnectionStatus.ESTABLISHED == this.status.get()) {
                            this.closeWaitConnection(deviceInboundTcpPacket);
                            continue;
                        }
                        //Receive fin ack on establish status
                        this.tcpIpPacketWriter.writeRstToDevice(this,
                                deviceInboundTcpHeader.getAcknowledgementNumber(),
                                deviceInboundTcpHeader.getSequenceNumber());
                        this.status.set(TcpConnectionStatus.CLOSED);
                        Log.e(TcpConnection.class.getName(),
                                ">>>>>>>> Incorrect status [receive FIN ACK but connection not ESTABLISHED], " +
                                        "reset and switch connection to CLOSED, current connection: " +
                                        this +
                                        ", device inbound tcp packet: " + deviceInboundTcpPacket);
                        continue;
                    }
                    if (TcpConnectionStatus.SYNC_RCVD == this.status.get()) {
                        this.switchConnectionToEstablished(deviceInboundTcpPacket);
                        continue;
                    }
                    if (TcpConnectionStatus.ESTABLISHED == this.status.get()) {
                        this.relayDeviceData(deviceInboundTcpPacket);
                        continue;
                    }
                    this.tcpIpPacketWriter.writeRstToDevice(this,
                            deviceInboundTcpHeader.getAcknowledgementNumber(),
                            deviceInboundTcpHeader.getSequenceNumber());
                    this.status.set(TcpConnectionStatus.CLOSED);
                    Log.e(TcpConnection.class.getName(),
                            ">>>>>>>> Incorrect status [receive ACK but no matched status], " +
                                    "reset and switch connection to CLOSED, current connection: " +
                                    this +
                                    ", device inbound tcp packet: " + deviceInboundTcpPacket);
                    continue;
                }
                if (deviceInboundTcpHeader.isFin()) {
                    closeWaitConnection(deviceInboundTcpPacket);
                    continue;
                }
                this.tcpIpPacketWriter.writeRstToDevice(this, deviceInboundTcpHeader.getAcknowledgementNumber(),
                        deviceInboundTcpHeader.getSequenceNumber());
                this.status.set(TcpConnectionStatus.CLOSED);
                Log.e(TcpConnection.class.getName(),
                        ">>>>>>>> Incorrect status, reset and close connection, current connection: " + this +
                                ", device inbound tcp packet: " + deviceInboundTcpPacket);
                return;
            } catch (Exception e) {
                this.tcpIpPacketWriter.writeRstToDevice(this, this.currentSequenceNumber.get(),
                        this.currentAcknowledgementNumber.get());
                this.status.set(TcpConnectionStatus.CLOSED);
                Log.e(TcpConnection.class.getName(),
                        ">>>>>>>> Exception happen, reset and close connection, current connection: " + this, e);
                return;
            } finally {
                Log.v(TcpConnection.class.getName(),
                        "######## Current connection: " + this + "; device inbound queue size: " +
                                this.deviceInboundQueue.size());
            }
        }
    }

    private void closeWaitConnection(TcpPacket deviceInboundTcpPacket) {
        Log.d(TcpConnection.class.getName(),
                ">>>>>>>> Begin to switch connection to CLOSE_WAIT, current connection: " +
                        this + "; device inbound tcp packet: " + deviceInboundTcpPacket);
        this.status.set(TcpConnectionStatus.CLOSE_WAIT);
        //Device maybe carry data together with Fin(ACK)
        this.relayDeviceData(deviceInboundTcpPacket);
        this.proxyChannel.close().addListener(future -> {
            var ackNumberForCloseWait = TcpConnection.this.getCurrentAcknowledgementNumber().get();
            TcpConnection.this.proxyChannelBootstrap.config().group().shutdownGracefully();
            TcpConnection.this.getStatus().set(TcpConnectionStatus.LAST_ACK);
            this.tcpIpPacketWriter.writeFinAckToDevice(null, TcpConnection.this,
                    TcpConnection.this.getCurrentSequenceNumber().get(),
                    TcpConnection.this.getCurrentAcknowledgementNumber().get());
            Log.d(TcpConnectionProxyMessageHandler.class.getName(),
                    "<<<<----  Connection switched to LAST_ACK, ack to device with [" +
                            ackNumberForCloseWait + "] current connection: " +
                            TcpConnection.this + "; device inbound tcp packet: " + deviceInboundTcpPacket);
        });
    }

    private void relayDeviceData(TcpPacket deviceInboundTcpPacket) {
        var dataLength = deviceInboundTcpPacket.getData().length;
        if (dataLength == 0) {
//            TcpConnection.this.currentAcknowledgementNumber.getAndIncrement();
//            TcpConnection.this.tcpIpPacketWriter.writeAckToDevice(null, TcpConnection.this,
//                    TcpConnection.this.currentSequenceNumber.get(),
//                    TcpConnection.this.currentAcknowledgementNumber.get());
            Log.d(TcpConnection.class.getName(),
                    ">>>>>>>> Nothing to relay device inbound data to proxy, current connection: " +
                            this + "; device inbound tcp packet: " + deviceInboundTcpPacket);
            return;
        }
        Log.d(TcpConnection.class.getName(),
                ">>>>>>>> Begin to relay device inbound data to proxy, current connection: " +
                        this + "; device inbound tcp packet: " + deviceInboundTcpPacket);
        var messageRelayToProxy = new Message();
        messageRelayToProxy.setId(UUIDUtil.INSTANCE.generateUuid());
        messageRelayToProxy.setUserToken(IVpnConst.PPAASS_PROXY_USER_TOKEN);
        messageRelayToProxy.setPayloadEncryptionType(PayloadEncryptionType.Aes);
        messageRelayToProxy.setPayloadEncryptionToken(UUIDUtil.INSTANCE.generateUuidInBytes());
        var agentMessagePayload = new AgentMessagePayload();
        agentMessagePayload.setData(deviceInboundTcpPacket.getData());
        agentMessagePayload.setPayloadType(AgentMessagePayloadType.TcpData);
        var sourceAddress = new NetAddress();
        sourceAddress.setHost(this.repositoryKey.getSourceAddress());
        sourceAddress.setPort((short) this.repositoryKey.getSourcePort());
        sourceAddress.setType(NetAddressType.IpV4);
        agentMessagePayload.setSourceAddress(sourceAddress);
        var targetAddress = new NetAddress();
        targetAddress.setHost(this.repositoryKey.getDestinationAddress());
        targetAddress.setPort((short) this.repositoryKey.getDestinationPort());
        targetAddress.setType(NetAddressType.IpV4);
        agentMessagePayload.setTargetAddress(targetAddress);
        messageRelayToProxy.setPayload(
                PpaassMessageUtil.INSTANCE.generateAgentMessagePayloadBytes(
                        agentMessagePayload));
        TcpConnection.this.currentAcknowledgementNumber.addAndGet(
                deviceInboundTcpPacket.getData().length);
        this.proxyChannel.writeAndFlush(messageRelayToProxy).addListener((ChannelFutureListener) future -> {
            //Ack every device inbound packet.
            long ackNumber = TcpConnection.this.currentAcknowledgementNumber.get();
            if (future.isSuccess()) {
                Log.d(TcpConnection.class.getName(),
                        "<<<<---- Success to relay device inbound data to proxy, ack to device with [" +
                                ackNumber +
                                "], current connection: " +
                                TcpConnection.this + "; relay time device inbound tcp packet: " +
                                deviceInboundTcpPacket);
                TcpConnection.this.tcpIpPacketWriter.writeAckToDevice(null, TcpConnection.this,
                        TcpConnection.this.currentSequenceNumber.get(),
                        ackNumber);
            } else {
                Log.e(TcpConnection.class.getName(),
                        "<<<<---- Fail to relay device inbound data to proxy, ack to device with [" +
                                ackNumber +
                                "], current connection: " +
                                TcpConnection.this + "; relay time device inbound tcp packet: " +
                                deviceInboundTcpPacket,
                        future.cause());
            }
        });
    }

    private void switchConnectionToEstablished(TcpPacket deviceInboundTcpPacket) {
        Log.d(TcpConnection.class.getName(),
                ">>>>>>>> Receive ACK and begin to switch connection to ESTABLISHED, current connection: " + this +
                        "; device inbound tcp packet: " + deviceInboundTcpPacket);
        var deviceInboundTcpHeader = deviceInboundTcpPacket.getHeader();
        // Receive ack of previous sync-ack.
        if (this.currentSequenceNumber.get() + 1 !=
                deviceInboundTcpHeader.getAcknowledgementNumber()) {
            tcpIpPacketWriter.writeRstToDevice(this,
                    this.currentSequenceNumber.get(),
                    this.currentAcknowledgementNumber.get());
            this.status.set(TcpConnectionStatus.CLOSED);
            Log.e(TcpConnection.class.getName(),
                    ">>>>>>>> Connection current seq number do not match incoming ack number, reset connection:  " +
                            this + "; device inbound tcp packet: " + deviceInboundTcpPacket);
            return;
        }
        if (this.currentAcknowledgementNumber.get() + 1 !=
                deviceInboundTcpHeader.getSequenceNumber()) {
            tcpIpPacketWriter.writeRstToDevice(this,
                    this.currentSequenceNumber.get(),
                    this.currentAcknowledgementNumber.get());
            this.status.set(TcpConnectionStatus.CLOSED);
            Log.e(TcpConnection.class.getName(),
                    ">>>>>>>> Connection current ack number do not match incoming seq number,  reset connection:  " +
                            this + "; device inbound tcp packet: " + deviceInboundTcpPacket);
            return;
        }
        Log.d(TcpConnection.class.getName(),
                ">>>>>>>> Begin connect to proxy, current connection:  " +
                        this + "; device inbound tcp packet: " + deviceInboundTcpPacket);
        try {
            this.doConnectToProxy();
        } catch (Exception e) {
            tcpIpPacketWriter.writeRstToDevice(this,
                    deviceInboundTcpHeader.getAcknowledgementNumber(),
                    deviceInboundTcpHeader.getSequenceNumber());
            this.status.set(TcpConnectionStatus.CLOSED);
            Log.e(TcpConnection.class.getName(),
                    ">>>>>>>> Connect to proxy fail(Exception), current connection:  " + this +
                            "; device inbound tcp packet: " + deviceInboundTcpPacket, e);
            return;
        }
        //Connect to Ppaass Proxy start
        try {
            this.proxyChannel = this.proxyChannelConnectedPromise.get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            tcpIpPacketWriter.writeRstToDevice(this,
                    deviceInboundTcpHeader.getAcknowledgementNumber(),
                    deviceInboundTcpHeader.getSequenceNumber());
            this.status.set(TcpConnectionStatus.CLOSED);
            Log.e(TcpConnection.class.getName(),
                    ">>>>>>>> Connect to proxy fail(timeout), current connection:  " + this +
                            "; device inbound tcp packet: " + deviceInboundTcpPacket, e);
            return;
        }
        this.currentSequenceNumber.getAndIncrement();
        this.currentAcknowledgementNumber.getAndIncrement();
        this.status.set(TcpConnectionStatus.ESTABLISHED);
        Log.d(TcpConnection.class.getName(),
                ">>>>>>>> Connect to remote through proxy success, connection status switch to ESTABLISHED, " +
                        "the Sequence and Acknowledgement should be synchronized between device and VPN, current connection: " +
                        this + "; device inbound tcp packet: " + deviceInboundTcpPacket);
    }

    private void resetConnectionByDevice(TcpPacket deviceInboundPacket) {
        // Receive rst , close the connection directly.
        this.tcpIpPacketWriter.writeRstAckToDevice(this, this.currentSequenceNumber.get(),
                this.currentAcknowledgementNumber.get());
        this.status.set(TcpConnectionStatus.CLOSED);
        Log.d(TcpConnection.class.getName(),
                ">>>>>>>> Receive RST, close connection, current connection: " + this +
                        "; device inbound tcp packet: " + deviceInboundPacket);
    }

    private void initializeConnection(TcpPacket deviceInboundPacket) {
        if (this.status.get() == TcpConnectionStatus.SYNC_RCVD ||
                this.status.get() == TcpConnectionStatus.ESTABLISHED) {
            Log.w(TcpConnection.class.getName(),
                    ">>>>>>>> Receive duplicate SYNC from device, current connection: " + this +
                            "; device inbound tcp packet: " + deviceInboundPacket);
            return;
        }
        if (this.status.get() != TcpConnectionStatus.LISTEN) {
            this.tcpIpPacketWriter.writeRstToDevice(this, this.currentSequenceNumber.get(),
                    this.currentAcknowledgementNumber.get());
            this.status.set(TcpConnectionStatus.CLOSED);
            Log.e(TcpConnection.class.getName(),
                    ">>>>>>>> Receive duplicate SYNC from device on connection is LISTEN, reset connection, current connection: " +
                            this +
                            "; device inbound tcp packet: " + deviceInboundPacket);
            return;
        }
        var deviceInboundTcpHeader = deviceInboundPacket.getHeader();
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
                ">>>>>>>> Receive SYNC and do SYNC+ACK, current connection: " + this +
                        "; device inbound tcp packet: " + deviceInboundPacket);
    }

    public long getLatestActiveTime() {
        return latestActiveTime.get();
    }

    public void setLatestActiveTime() {
        this.latestActiveTime.set(System.currentTimeMillis());
    }

    public AtomicReference<TcpConnectionStatus> getStatus() {
        return status;
    }

    private Bootstrap createBootstrap() {
        var result = new Bootstrap();
        result.group(new NioEventLoopGroup(1));
        result.channelFactory(new PpaassVpnNettyTcpChannelFactory(this.vpnService));
        result.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 120 * 1000);
        result.option(ChannelOption.SO_KEEPALIVE, true);
        result.option(ChannelOption.AUTO_READ, true);
        result.option(ChannelOption.AUTO_CLOSE, false);
        result.option(ChannelOption.TCP_NODELAY, true);
        result.option(ChannelOption.SO_REUSEADDR, true);
        AttributeKey<TcpConnection> tcpConnectionKey = AttributeKey.valueOf(IVpnConst.TCP_CONNECTION);
        result.attr(tcpConnectionKey, this);
        result.handler(new ChannelInitializer<NioSocketChannel>() {
            @Override
            protected void initChannel(@NonNull NioSocketChannel ch) {
                ch.pipeline().addLast(new PpaassMessageDecoder());
                ch.pipeline().addLast(new TcpConnectionProxyMessageHandler(TcpConnection.this.tcpIpPacketWriter,
                        TcpConnection.this.proxyChannelConnectedPromise));
                ch.pipeline().addLast(new PpaassMessageEncoder(false));
            }
        });
        return result;
    }

    private int generateVpnInitialSequenceNumber() {
        return INITIAL_SEQ.getAndIncrement();
    }

    public void clearResource() {
        this.deviceInboundQueue.clear();
        try {
            this.proxyChannel.close();
        } catch (Exception e) {
            Log.v(TcpConnection.class.getName(), "Fail to close proxy channel because of exception.", e);
        }
        try {
            this.proxyChannelBootstrap.config().group().shutdownGracefully();
        } catch (Exception e) {
            Log.v(TcpConnection.class.getName(), "Fail to shutdown remote bootstrap because of exception.", e);
        }
    }

    private void doConnectToProxy() throws Exception {
        var proxyAddress =
                new InetSocketAddress(InetAddress.getByName(IVpnConst.PPAASS_PROXY_IP), IVpnConst.PPAASS_PROXY_PORT);
        this.proxyChannelBootstrap.connect(proxyAddress);
    }

    /**
     * Non-blocking receive inbound data for the current tcp connection
     *
     * @param tcpPacket The device inbound data.
     * @throws Exception The exception when put device data into inbound queue
     */
    public void onDeviceInbound(TcpPacket tcpPacket) throws Exception {
        if (!this.deviceInboundQueue.offer(tcpPacket)) {
            Log.e(TcpConnection.class.getName(),
                    "Fail to insert tcp packet into connection inbound queue, current connection: " + this +
                            ", tcp packet: " + tcpPacket);
        }
    }

    public TcpConnectionRepositoryKey getRepositoryKey() {
        return repositoryKey;
    }

    public AtomicLong getCurrentSequenceNumber() {
        return currentSequenceNumber;
    }

    public AtomicLong getCurrentAcknowledgementNumber() {
        return currentAcknowledgementNumber;
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
