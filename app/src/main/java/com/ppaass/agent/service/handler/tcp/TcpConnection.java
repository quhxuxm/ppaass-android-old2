package com.ppaass.agent.service.handler.tcp;

import android.net.VpnService;
import android.util.Log;
import androidx.annotation.NonNull;
import com.ppaass.agent.protocol.general.tcp.TcpHeader;
import com.ppaass.agent.protocol.general.tcp.TcpPacket;
import com.ppaass.agent.service.IVpnConst;
import com.ppaass.agent.service.PpaassVpnTcpChannelFactory;
import com.ppaass.agent.service.handler.TcpIpPacketWriter;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.AttributeKey;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Comparator;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final TcpConnectionRepositoryKey repositoryKey;
    private Channel remoteChannel;
    private final TcpIpPacketWriter tcpIpPacketWriter;
    private final BlockingQueue<TcpPacket> deviceInbound;
    private final Map<TcpConnectionRepositoryKey, TcpConnection> connectionRepository;
    private TcpConnectionStatus status;
    private long currentSequenceNumber;
    private long currentAcknowledgementNumber;
    private long deviceInitialSequenceNumber;
    private long vpnInitialSequenceNumber;
    //    private final Thread writeToDeviceTask;
    private final Runnable waitTime2MslTask;
    private final long writeToDeviceTimeout;
    private final long readFromDeviceTimeout;
    private final ByteBuf deviceReceiveBuf;
    private final ScheduledExecutorService executorFor2MslTasks;
    private final VpnService vpnService;
    private final Bootstrap remoteBootstrap;

    public TcpConnection(TcpConnectionRepositoryKey repositoryKey, TcpIpPacketWriter tcpIpPacketWriter,
                         Map<TcpConnectionRepositoryKey, TcpConnection> connectionRepository, long writeToDeviceTimeout,
                         long readFromDeviceTimeout, VpnService vpnService) {
        this.writeToDeviceTimeout = writeToDeviceTimeout;
        this.readFromDeviceTimeout = readFromDeviceTimeout;
        this.id = UUID.randomUUID().toString().replace("-", "");
        this.repositoryKey = repositoryKey;
        this.status = TcpConnectionStatus.LISTEN;
        this.currentAcknowledgementNumber = 0;
        this.currentSequenceNumber = 0;
        this.deviceInitialSequenceNumber = 0;
        this.vpnInitialSequenceNumber = 0;
        this.deviceInbound =
                new PriorityBlockingQueue<>(1024, Comparator.comparingLong(p -> p.getHeader().getSequenceNumber()));
        this.tcpIpPacketWriter = tcpIpPacketWriter;
        this.connectionRepository = connectionRepository;
        this.vpnService = vpnService;
        this.deviceReceiveBuf = Unpooled.buffer(IVpnConst.TCP_MSS, IVpnConst.TCP_WINDOW);
        this.executorFor2MslTasks = Executors.newSingleThreadScheduledExecutor();
        this.waitTime2MslTask = TcpConnection.this::finallyCloseTcpConnection;
        this.remoteBootstrap = this.createBootstrap();
    }

    public void run() {
        while (this.status != TcpConnectionStatus.CLOSED) {
            try {
                TcpPacket deviceInboundTcpPacket = this.deviceInbound.take();
                Log.d(TcpConnection.class.getName(),
                        ">>>>>>>> Success take tcp packet from device inbound, current connection: " + this +
                                "; device inbound tcp packet: " + deviceInboundTcpPacket +
                                "; device inbound queue size: " +
                                deviceInbound.size());
                TcpHeader deviceInboundTcpHeader = deviceInboundTcpPacket.getHeader();
                if (deviceInboundTcpHeader.isRst()) {
                    // Receive rst and ack, close the connection directly.
                    Log.d(TcpConnection.class.getName(),
                            "Receive rst ack, close connection, current connection: " + this +
                                    "; device inbound tcp packet: " + deviceInboundTcpPacket);
                    this.finallyCloseTcpConnection();
                    return;
                }
                if (deviceInboundTcpHeader.isSyn() && !deviceInboundTcpHeader.isAck()) {
                    // Receive sync from device inbound, the connection status must be LISTEN,
                    // for other status it is duplicate packet.
                    if (this.status != TcpConnectionStatus.LISTEN) {
                        Log.w(TcpConnection.class.getName(),
                                "Receive duplicate syn from device, current connection: " + this +
                                        "; device inbound tcp packet: " +
                                        deviceInboundTcpPacket);
                        continue;
                    }
                    //Initialize ack number and seq number
                    this.deviceInitialSequenceNumber = deviceInboundTcpHeader.getSequenceNumber();
                    this.currentAcknowledgementNumber = deviceInboundTcpHeader.getSequenceNumber();
                    long vpnIsn = this.generateVpnInitialSequenceNumber();
                    this.currentSequenceNumber = vpnIsn;
                    this.vpnInitialSequenceNumber = vpnIsn;
                    this.status = TcpConnectionStatus.SYNC_RCVD;
                    this.tcpIpPacketWriter.writeSyncAckToDevice(this, this.currentSequenceNumber,
                            this.currentAcknowledgementNumber + 1);
                    Log.d(TcpConnection.class.getName(),
                            ">>>>>>>> Receive sync and do sync ack, current connection: " + this +
                                    "; device inbound tcp packet: " + deviceInboundTcpPacket);
                    continue;
                }
                if (deviceInboundTcpHeader.isAck() && !deviceInboundTcpHeader.isFin()) {
                    if (deviceInboundTcpHeader.getSequenceNumber() < this.currentAcknowledgementNumber) {
                        // This is a resend packet from device, which means previous device data is received by vpn
                        // but client sent again because of timer expired, in this case vpn should ignore this packet.
                        Log.d(TcpConnection.class.getName(),
                                ">>>>>>>> Receive ack of resend packet(client timer expired), ignore this packet and continue next, current connection: " +
                                        this +
                                        "; device inbound tcp packet: " +
                                        deviceInboundTcpPacket);
                        continue;
                    }
                    if (deviceInboundTcpHeader.getSequenceNumber() >= this.currentAcknowledgementNumber) {
                        // Device receive vpn data and do ack and vpn receive the corresponding ack or
                        // this is a resend packet from device, which means previous device data not received by vpn.
                        if (this.status == TcpConnectionStatus.SYNC_RCVD) {
                            // Receive ack of previous sync-ack.
                            if (this.currentSequenceNumber + 1 !=
                                    deviceInboundTcpHeader.getAcknowledgementNumber()) {
                                Log.e(TcpConnection.class.getName(),
                                        ">>>>>>>> Connection current seq number do not match incoming ack number:  " +
                                                this + "; device inbound tcp packet: " +
                                                deviceInboundTcpPacket);
                                tcpIpPacketWriter.writeRstToDevice(this, this.currentSequenceNumber,
                                        this.currentAcknowledgementNumber);
                                this.finallyCloseTcpConnection();
                                return;
                            }
                            if (this.currentAcknowledgementNumber + 1 !=
                                    deviceInboundTcpHeader.getSequenceNumber()) {
                                Log.e(TcpConnection.class.getName(),
                                        ">>>>>>>> Connection current ack number do not match incoming seq number:  " +
                                                this + "; device inbound tcp packet: " +
                                                deviceInboundTcpPacket);
                                tcpIpPacketWriter.writeRstToDevice(this, this.currentSequenceNumber,
                                        this.currentAcknowledgementNumber);
                                this.finallyCloseTcpConnection();
                                return;
                            }
                            this.remoteChannel = this.connectRemote();
                            if (this.remoteChannel == null) {
                                Log.e(TcpConnection.class.getName(),
                                        ">>>>>>>> Connect to remote fail(NULL), current connection:  " + this +
                                                "; incoming tcp packet: " +
                                                deviceInboundTcpPacket);
                                tcpIpPacketWriter.writeRstToDevice(this, this.currentSequenceNumber,
                                        this.currentAcknowledgementNumber);
                                this.finallyCloseTcpConnection();
                                return;
                            }
                            this.currentSequenceNumber++;
                            this.currentAcknowledgementNumber++;
                            this.status = TcpConnectionStatus.ESTABLISHED;
                            Log.d(TcpConnection.class.getName(),
                                    ">>>>>>>> Receive ack and remote connection established; current connection: " +
                                            this +
                                            "; incoming tcp packet: " +
                                            deviceInboundTcpPacket);
                            continue;
                        }
                        if (this.status == TcpConnectionStatus.ESTABLISHED) {
                            Log.d(TcpConnection.class.getName(),
                                    ">>>>>>>> Receive ACK on established connection - (PSH=" +
                                            deviceInboundTcpPacket.getHeader().isPsh() +
                                            ") and forward device inbound data to remote, current connection:  " +
                                            this +
                                            "; device inbound tcp packet: " +
                                            deviceInboundTcpPacket +
                                            "; device inbound queue size: " + this.deviceInbound.size());
                            this.remoteChannel.writeAndFlush(
                                    Unpooled.wrappedBuffer(deviceInboundTcpPacket.getData()));
                            this.currentAcknowledgementNumber += deviceInboundTcpPacket.getData().length;
                            this.tcpIpPacketWriter.writeAckToDevice(null, this, this.currentSequenceNumber,
                                    this.currentAcknowledgementNumber);
                            continue;
                        }
                        if (this.status == TcpConnectionStatus.LAST_ACK) {
                            // Finally close the tcp connection after receive Fin from client.
                            Log.d(TcpConnection.class.getName(),
                                    ">>>>>>>> Receive last ack, close connection: " + this +
                                            "; device inbound tcp packet: " +
                                            deviceInboundTcpPacket);
                            this.finallyCloseTcpConnection();
                            return;
                        }
                        Log.e(TcpConnection.class.getName(),
                                ">>>>>>>> Incorrect tcp header(1), close connection; current connection: " +
                                        this +
                                        "; device inbound tcp packet: " + deviceInboundTcpPacket);
                        this.tcpIpPacketWriter.writeRstToDevice(this, this.currentSequenceNumber,
                                this.currentAcknowledgementNumber);
                        this.finallyCloseTcpConnection();
                        return;
                    }
//                        if (deviceInboundTcpHeader.getSequenceNumber() > this.currentAcknowledgementNumber) {
//                            // This is a resend packet from device, which means previous device data not received by vpn.
//                        }
                    Log.e(TcpConnection.class.getName(),
                            ">>>>>>>> Incorrect tcp header(2), close connection; current connection: " +
                                    this +
                                    "; device inbound tcp packet: " + deviceInboundTcpPacket);
                    this.tcpIpPacketWriter.writeRstToDevice(this, this.currentSequenceNumber,
                            this.currentAcknowledgementNumber);
                    this.finallyCloseTcpConnection();
                    return;
                }
                if (deviceInboundTcpHeader.isFin()) {
                    //Receive Fin on established status.
                    if (this.status == TcpConnectionStatus.ESTABLISHED) {
                        //After fin, no more device data will be sent to vpn
                        Log.d(TcpConnection.class.getName(),
                                ">>>>>>>> Receive FIN when connection ESTABLISHED, current connection: " + this +
                                        "; device inbound tcp packet: " + deviceInboundTcpPacket);
                        if (deviceInboundTcpPacket.getData().length > 0) {
                            if (deviceInboundTcpHeader.getSequenceNumber() < this.currentAcknowledgementNumber) {
                                // This is a resend packet from device, which means previous device data is received by vpn
                                // but client sent again because of timer expired, in this case vpn should ignore this packet.
                                Log.d(TcpConnection.class.getName(),
                                        ">>>>>>>> Receive FIN with data of resend packet(client timer expired), ignore this packet and continue next, current connection: " +
                                                this +
                                                "; device inbound tcp packet: " +
                                                deviceInboundTcpPacket);
                                continue;
                            }
                            if (deviceInboundTcpHeader.getSequenceNumber() >= this.currentAcknowledgementNumber) {
                                Log.d(TcpConnection.class.getName(),
                                        ">>>>>>>> Receive FIN with data on established connection - (PSH=" +
                                                deviceInboundTcpPacket.getHeader().isPsh() +
                                                ") and forward device inbound data to remote, current connection:  " +
                                                this +
                                                "; device inbound tcp packet: " +
                                                deviceInboundTcpPacket +
                                                "; device inbound queue size: " + this.deviceInbound.size());
                                this.remoteChannel.writeAndFlush(
                                        Unpooled.wrappedBuffer(deviceInboundTcpPacket.getData()));
                                this.currentAcknowledgementNumber += deviceInboundTcpPacket.getData().length;
                                this.tcpIpPacketWriter.writeAckToDevice(null, this, this.currentSequenceNumber,
                                        this.currentAcknowledgementNumber);
                                continue;
                            }
                        }
                        this.status = TcpConnectionStatus.CLOSED_WAIT;
                        this.tcpIpPacketWriter.writeAckToDevice(null, this, this.currentSequenceNumber,
                                this.currentAcknowledgementNumber + 1);
                        this.remoteChannel.close().sync();
                        this.remoteBootstrap.config().group().shutdownGracefully();
                        this.tcpIpPacketWriter.writeFinAckToDevice(this, this.currentSequenceNumber,
                                this.currentAcknowledgementNumber + 1);
                        this.currentAcknowledgementNumber++;
                        continue;
                    }
                    Log.e(TcpConnection.class.getName(),
                            ">>>>>>>> Incorrect tcp header(3), close connection, current connection: " +
                                    this +
                                    "; device inbound tcp packet: " + deviceInboundTcpPacket);
                    this.tcpIpPacketWriter.writeRstToDevice(this, this.currentSequenceNumber,
                            this.currentAcknowledgementNumber);
                    this.finallyCloseTcpConnection();
                    return;
                }
                Log.e(TcpConnection.class.getName(),
                        ">>>>>>>> Incorrect tcp header(4), close connection, current connection: " +
                                this +
                                ", device inbound tcp packet: " + deviceInboundTcpPacket);
                this.tcpIpPacketWriter.writeRstToDevice(this, this.currentSequenceNumber,
                        this.currentAcknowledgementNumber);
                this.finallyCloseTcpConnection();
                return;
            } catch (Exception e) {
                Log.e(TcpConnection.class.getName(),
                        ">>>>>>>> Exception happen, close connection, current connection: " +
                                this, e);
                this.tcpIpPacketWriter.writeRstToDevice(this, this.currentSequenceNumber,
                        this.currentAcknowledgementNumber);
                this.finallyCloseTcpConnection();
                return;
            } finally {
                Log.d(TcpConnection.class.getName(),
                        "######## Current connection: " + this + "; connection repository size: " +
                                this.connectionRepository.size() + "; device inbound queue size: " +
                                this.deviceInbound.size());
            }
        }
    }

    private Bootstrap createBootstrap() {
        Bootstrap result = new Bootstrap();
        result.group(new NioEventLoopGroup(1));
        result.channelFactory(new PpaassVpnTcpChannelFactory(this.vpnService));
        result.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 20000);
        result.option(ChannelOption.SO_TIMEOUT, 20000);
        result.option(ChannelOption.SO_KEEPALIVE, false);
        result.option(ChannelOption.AUTO_READ, true);
        result.option(ChannelOption.AUTO_CLOSE, false);
        result.option(ChannelOption.TCP_NODELAY, true);
        result.option(ChannelOption.SO_REUSEADDR, true);
        result.handler(new ChannelInitializer<NioSocketChannel>() {
            @Override
            protected void initChannel(@NonNull NioSocketChannel ch) {
                ch.pipeline().addLast(new TcpConnectionRelayRemoteHandler(TcpConnection.this.tcpIpPacketWriter));
            }
        });
        return result;
    }

    private int generateVpnInitialSequenceNumber() {
        return INITIAL_SEQ.getAndIncrement();
    }

    public void finallyCloseTcpConnection() {
        this.status = TcpConnectionStatus.CLOSED;
        this.deviceInbound.clear();
        try {
            if (remoteChannel != null) {
                this.remoteChannel.close();
            }
        } catch (Exception e) {
            Log.e(TcpConnection.class.getName(),
                    ">>>>>>>> Fail to close remote channel, current connection: " + this +
                            ", device inbound size: " +
                            deviceInbound.size(), e);
        }
        this.remoteBootstrap.config().group().shutdownGracefully();
        this.connectionRepository.remove(this.repositoryKey);
    }

    private Channel connectRemote() throws Exception {
        AttributeKey<TcpConnection> tcpConnectionKey = AttributeKey.valueOf(IVpnConst.TCP_CONNECTION);
        InetSocketAddress remoteAddress =
                new InetSocketAddress(InetAddress.getByAddress(this.repositoryKey.getDestinationAddress()),
                        this.repositoryKey.getDestinationPort());
        ChannelFuture channelFuture = this.remoteBootstrap.connect(remoteAddress);
        channelFuture.channel().attr(tcpConnectionKey).setIfAbsent(this);
        channelFuture = channelFuture.sync();
        if (channelFuture.isSuccess()) {
            Log.d(TcpConnection.class.getName(), ">>>>>>>> Success connect to remote: " + remoteAddress);
            return channelFuture.channel();
        } else {
            Log.e(TcpConnection.class.getName(), ">>>>>>>> Fail connect to remote: " + remoteAddress,
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
        this.deviceInbound.put(tcpPacket);
        synchronized (this.deviceInbound) {
            this.deviceInbound.notifyAll();
        }
    }

    public TcpConnectionRepositoryKey getRepositoryKey() {
        return repositoryKey;
    }

    public TcpConnectionStatus getStatus() {
        return status;
    }

    public void setStatus(TcpConnectionStatus status) {
        this.status = status;
    }

    public long getCurrentAcknowledgementNumber() {
        return currentAcknowledgementNumber;
    }

    public long getCurrentSequenceNumber() {
        return currentSequenceNumber;
    }

    public void setCurrentSequenceNumber(long currentSequenceNumber) {
        this.currentSequenceNumber = currentSequenceNumber;
    }

    private long countRelativeVpnSequenceNumber() {
        return this.currentSequenceNumber - this.vpnInitialSequenceNumber;
    }

    private long countRelativeDeviceSequenceNumber() {
        return this.currentAcknowledgementNumber - this.deviceInitialSequenceNumber;
    }

    @Override
    public String toString() {
        return "TcpConnection{id='" + id + '\'' + ", repositoryKey=" + repositoryKey +
                ", status=" + status + ", currentSequenceNumber=" + currentSequenceNumber +
                ", (Current Relative VPN Sequence Number)=" + this.countRelativeVpnSequenceNumber() +
                ", currentAcknowledgementNumber=" + currentAcknowledgementNumber +
                ", (Current Relative VPN Acknowledgement Number)=" + this.countRelativeDeviceSequenceNumber() +
                ", deviceInitialSequenceNumber=" + deviceInitialSequenceNumber + ", vpnInitialSequenceNumber=" +
                vpnInitialSequenceNumber + '}';
    }
}
