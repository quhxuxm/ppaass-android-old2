package com.ppaass.agent.service.handler.tcp;

import android.net.VpnService;
import android.util.Log;
import androidx.annotation.NonNull;
import com.ppaass.agent.protocol.general.tcp.TcpHeader;
import com.ppaass.agent.protocol.general.tcp.TcpHeaderOption;
import com.ppaass.agent.protocol.general.tcp.TcpPacket;
import com.ppaass.agent.protocol.general.tcp.TcpPacketBuilder;
import com.ppaass.agent.service.IVpnConst;
import com.ppaass.agent.service.PpaassVpnTcpChannelFactory;
import com.ppaass.agent.service.handler.TcpIpPacketWriter;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.AttributeKey;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.*;
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

    private static final AtomicInteger TIMESTAMP = new AtomicInteger();
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
    private int currentWindowSize;
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
                new LinkedBlockingQueue<>(1024);
        this.tcpIpPacketWriter = tcpIpPacketWriter;
        this.connectionRepository = connectionRepository;
        this.vpnService = vpnService;
        this.currentWindowSize = IVpnConst.TCP_WINDOW;
        this.executorFor2MslTasks = Executors.newSingleThreadScheduledExecutor();
        this.waitTime2MslTask = TcpConnection.this::finallyCloseTcpConnection;
        this.remoteBootstrap = this.createBootstrap();
    }

    private Bootstrap createBootstrap() {
        Bootstrap result = new Bootstrap();
        result.group(new NioEventLoopGroup(2));
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
            protected void initChannel(@NonNull NioSocketChannel ch) throws Exception {
                ch.pipeline().addLast(new TcpConnectionRelayRemoteHandler());
            }
        });
        return result;
    }

    private int generateVpnInitialSequenceNumber() {
        return INITIAL_SEQ.getAndIncrement();
    }

    public void finallyCloseTcpConnection() {
        synchronized (this) {
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

    public void onDeviceInbound(TcpPacket tcpPacket) throws Exception {
        synchronized (this) {
            int dataLength = tcpPacket.getData().length;
            int nextWindowSize = this.currentWindowSize - dataLength;
            if (nextWindowSize < 0) {
                Log.d(TcpConnection.class.getName(), ">>>>>>>> Window size is full, current connection:  " + this +
                        ", incoming tcp packet: " + this.printTcpPacket(tcpPacket) + " , device data size: " +
                        tcpPacket.getData().length + ", window size: " + this.currentWindowSize);
                Log.v(TcpConnection.class.getName(), ">>>>>>>> Inbound data:\n\n" +
                        ByteBufUtil.prettyHexDump(Unpooled.wrappedBuffer(tcpPacket.getData())) + "\n\n");
                this.writeAckToDevice(null, this.currentWindowSize);
                return;
            }
            Log.d(TcpConnection.class.getName(), ">>>>>>>> Window size is enough, current connection:  " + this +
                    ", incoming tcp packet: " + this.printTcpPacket(tcpPacket) + " , device data size: " +
                    tcpPacket.getData().length + ", window size: " + this.currentWindowSize);
            Log.v(TcpConnection.class.getName(), ">>>>>>>> Inbound data:\n\n" +
                    ByteBufUtil.prettyHexDump(Unpooled.wrappedBuffer(tcpPacket.getData())) + "\n\n");
            this.currentWindowSize -= dataLength;
            this.deviceInbound.put(tcpPacket);
            this.notifyAll();
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

    public int getCurrentWindowSize() {
        return currentWindowSize;
    }

    public void run() {
        while (this.status != TcpConnectionStatus.CLOSED) {
            try {
                TcpPacket tcpPacket = this.deviceInbound.poll();
                if (tcpPacket == null) {
                    synchronized (this) {
                        try {
                            this.wait(500);
                        } catch (Exception e) {
                            Log.e(TcpConnection.class.getName(),
                                    ">>>>>>>> Fail to take tcp packet from inbound queue, current connection: " + this +
                                            ", device inbound size: " + deviceInbound.size(), e);
                            this.writeRstToDevice();
                            this.finallyCloseTcpConnection();
                            return;
                        }
                        if (this.status == TcpConnectionStatus.CLOSED) {
                            return;
                        }
                    }
                    continue;
                }
                Log.d(TcpConnection.class.getName(),
                        ">>>>>>>> Success take tcp packet from device inbound, current connection: " + this +
                                ", tcp packet: " + tcpPacket + ", device inbound size: " + deviceInbound.size());
                TcpHeader tcpHeader = tcpPacket.getHeader();
                if (tcpHeader.isSyn()) {
                    if (this.status == TcpConnectionStatus.SYNC_RCVD) {
                        Log.e(TcpConnection.class.getName(),
                                ">>>>>>>> Receive duplicate sync tcp packet, current connection: " + this +
                                        ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
                        this.writeRstToDevice();
                        this.finallyCloseTcpConnection();
                        return;
                    }
                    //Initialize ack number and seq number
                    this.deviceInitialSequenceNumber = tcpHeader.getSequenceNumber();
                    this.currentAcknowledgementNumber = tcpHeader.getSequenceNumber() + 1;
                    long vpnIsn = this.generateVpnInitialSequenceNumber();
                    this.currentSequenceNumber = vpnIsn;
                    this.vpnInitialSequenceNumber = vpnIsn;
                    this.status = TcpConnectionStatus.SYNC_RCVD;
                    this.writeSyncAckToDevice();
                    Log.d(TcpConnection.class.getName(),
                            ">>>>>>>> Receive sync and do sync ack, current connection: " + this +
                                    ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
                    continue;
                }
                if (tcpHeader.isRst()) {
                    Log.d(TcpConnection.class.getName(),
                            ">>>>>>>> Receive rst close connection, current connection: " + this +
                                    ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
                    this.finallyCloseTcpConnection();
                    return;
                }
                if (tcpHeader.isAck()) {
                    if (tcpHeader.isFin()) {
                        if (this.status == TcpConnectionStatus.SYNC_RCVD) {
                            Log.e(TcpConnection.class.getName(),
                                    ">>>>>>>> Receive fin ack on SYNC_RCVD close connection directly, current connection:  " +
                                            this + ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
                            this.writeRstToDevice();
                            this.finallyCloseTcpConnection();
                            return;
                        }
                        if (this.status == TcpConnectionStatus.CLOSED) {
                            this.writeRstToDevice();
                            this.finallyCloseTcpConnection();
                            return;
                        }
                        //Receive Fin on established status.
                        //After fin, no more device data will be sent to vpn
                        try {
                            if (this.remoteChannel == null) {
                                this.writeRstToDevice();
                                this.finallyCloseTcpConnection();
                                Log.e(TcpConnection.class.getName(),
                                        ">>>>>>>> Write to remote fail(fin ack), current connection:  " + this +
                                                ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
                                return;
                            }
                            writeDataToRemote(tcpPacket);
                        } catch (Exception e) {
                            this.writeRstToDevice();
                            this.finallyCloseTcpConnection();
                            Log.e(TcpConnection.class.getName(),
                                    ">>>>>>>> Write to remote fail(fin ack), current connection:  " + this +
                                            ", incoming tcp packet: " + this.printTcpPacket(tcpPacket), e);
                            return;
                        }
                        Log.d(TcpConnection.class.getName(),
                                ">>>>>>>> Receive fin ack, current connection:  " + this + ", incoming tcp packet: " +
                                        this.printTcpPacket(tcpPacket));
                        this.status = TcpConnectionStatus.WAIT_TIME;
                        this.currentAcknowledgementNumber++;
                        this.writeAckToDevice(null, 0);
                        // TODO should make 2msl close, here just close directly
                        this.executorFor2MslTasks.schedule(this.waitTime2MslTask, 20,
                                TimeUnit.SECONDS);
                        Log.d(TcpConnection.class.getName(),
                                ">>>>>>>> Receive [fin ack] on ESTABLISHED and start 2msl task, current connection:  " +
                                        this + ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
                        continue;
                    }
                    if (this.status == TcpConnectionStatus.FIN_WAIT1) {
                        this.status = TcpConnectionStatus.FIN_WAIT2;
                        Log.d(TcpConnection.class.getName(),
                                ">>>>>>>> Receive ack on FIN_WAIT1, current connection:  " + this +
                                        ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
                        continue;
                    }
                    if (this.status == TcpConnectionStatus.SYNC_RCVD) {
                        if (this.currentSequenceNumber + 1 != tcpHeader.getAcknowledgementNumber()) {
                            Log.e(TcpConnection.class.getName(),
                                    ">>>>>>>> Connection current seq number do not match incoming ack number:  " +
                                            this + ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
                            this.writeRstToDevice();
                            this.finallyCloseTcpConnection();
                            return;
                        }
                        try {
                            this.remoteChannel = this.connectRemote();
                            if (this.remoteChannel == null) {
                                this.writeRstToDevice();
                                this.finallyCloseTcpConnection();
                                Log.e(TcpConnection.class.getName(),
                                        ">>>>>>>> Connect to remote fail(NULL), current connection:  " + this +
                                                ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
                                return;
                            }
                        } catch (Exception e) {
                            this.writeRstToDevice();
                            this.finallyCloseTcpConnection();
                            Log.e(TcpConnection.class.getName(),
                                    ">>>>>>>> Connect to remote fail(EXCEPTION), current connection:  " + this +
                                            ", incoming tcp packet: " + this.printTcpPacket(tcpPacket), e);
                            return;
                        }
                        this.currentSequenceNumber++;
                        this.status = TcpConnectionStatus.ESTABLISHED;
                        Log.d(TcpConnection.class.getName(),
                                ">>>>>>>> Receive ack and remote connection established, current connection: " + this +
                                        ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
                        continue;
                    }
                    if (this.status == TcpConnectionStatus.ESTABLISHED) {
                        if (this.currentAcknowledgementNumber > tcpHeader.getSequenceNumber()) {
                            Log.d(TcpConnection.class.getName(),
                                    ">>>>>>>> Ignore duplicate ACK - (PSH=" + tcpHeader.isPsh() +
                                            "), current connection:  " + this + ", incoming tcp packet: " +
                                            this.printTcpPacket(tcpPacket) + " , device data size: " +
                                            tcpPacket.getData().length);
                            continue;
                        }
                        try {
                            if (this.remoteChannel == null) {
                                this.writeRstToDevice();
                                this.finallyCloseTcpConnection();
                                Log.e(TcpConnection.class.getName(),
                                        ">>>>>>>> Write to remote fail(ack on ESTABLISHED), current connection:  " +
                                                this + ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
                                return;
                            }
                            writeDataToRemote(tcpPacket);
                        } catch (IOException e) {
                            this.writeRstToDevice();
                            this.finallyCloseTcpConnection();
                            Log.e(TcpConnection.class.getName(),
                                    ">>>>>>>> Write to remote fail, current connection:  " + this +
                                            ", incoming tcp packet: " + this.printTcpPacket(tcpPacket), e);
                            return;
                        }
                        continue;
                    }
                    if (this.status == TcpConnectionStatus.CLOSED_WAIT) {
                        // No data will from device anymore, just to consume the ack from device
                        Log.d(TcpConnection.class.getName(), ">>>>>>>> Receive ACK - (PSH=" + tcpHeader.isPsh() +
                                ") on CLOSE_WAIT, current connection:  " + this + ", incoming tcp packet: " +
                                this.printTcpPacket(tcpPacket));
                        continue;
                    }
                    if (this.status == TcpConnectionStatus.LAST_ACK) {
                        // Finally close the tcp connection after receive Fin from client.
                        Log.d(TcpConnection.class.getName(),
                                ">>>>>>>> Receive last ack, close connection: " + this + ", incoming tcp packet: " +
                                        this.printTcpPacket(tcpPacket));
                        this.finallyCloseTcpConnection();
                        return;
                    }
                    Log.e(TcpConnection.class.getName(),
                            ">>>>>>>> Incorrect connection status going to close connection, current connection: " +
                                    this + ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
                    this.writeRstToDevice();
                    this.finallyCloseTcpConnection();
                    return;
                }
                if (tcpHeader.isFin()) {
                    //Receive Fin on established status.
                    if (this.status == TcpConnectionStatus.ESTABLISHED) {
                        //After fin, no more device data will be sent to vpn
                        Log.d(TcpConnection.class.getName(),
                                ">>>>>>>> Receive fin when connection ESTABLISHED, current connection: " + this +
                                        ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
                        this.status = TcpConnectionStatus.CLOSED_WAIT;
                        this.currentAcknowledgementNumber++;
                        this.writeAckToDevice(null, 0);
                        continue;
                    }
                    Log.e(TcpConnection.class.getName(),
                            ">>>>>>>> Incorrect connection status going to close connection, current connection: " +
                                    this + ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
                    this.writeRstToDevice();
                    this.finallyCloseTcpConnection();
                    return;
                }
                Log.e(TcpConnection.class.getName(),
                        ">>>>>>>> Incorrect tcp header going to close connection, current connection: " + this +
                                ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
                this.writeRstToDevice();
                this.finallyCloseTcpConnection();
                return;
            } finally {
                Log.d(TcpConnection.class.getName(),
                        "######## Current connection: " + this + ", connection repository size: " +
                                this.connectionRepository.size() + ", device inbound size: " +
                                this.deviceInbound.size());
            }
        }
        this.finallyCloseTcpConnection();
    }

    private void writeDataToRemote(TcpPacket tcpPacket) throws IOException {
        synchronized (this) {
            this.remoteChannel.writeAndFlush(Unpooled.wrappedBuffer(tcpPacket.getData()));
            this.currentAcknowledgementNumber += tcpPacket.getData().length;
            this.currentWindowSize += tcpPacket.getData().length;
            this.writeAckToDevice(null, this.currentWindowSize);
            Log.d(TcpConnection.class.getName(), ">>>>>>>> Receive ACK - (PSH=" + tcpPacket.getHeader().isPsh() +
                    ") and put device data into device receive buffer, current connection:  " + this +
                    ", incoming tcp packet: " + this.printTcpPacket(tcpPacket) + " , device data size: " +
                    tcpPacket.getData().length + ", window size: " + this.currentWindowSize);
        }
    }

    private long countRelativeVpnSequenceNumber() {
        return this.currentSequenceNumber - this.vpnInitialSequenceNumber;
    }

    private long countRelativeDeviceSequenceNumber() {
        return this.currentAcknowledgementNumber - this.deviceInitialSequenceNumber;
    }

    private TcpPacket buildCommonTcpPacket(TcpPacketBuilder tcpPacketBuilder) {
        tcpPacketBuilder.destinationPort(this.repositoryKey.getSourcePort());
        tcpPacketBuilder.sourcePort(this.repositoryKey.getDestinationPort());
        tcpPacketBuilder.sequenceNumber(this.currentSequenceNumber);
        tcpPacketBuilder.acknowledgementNumber(this.currentAcknowledgementNumber);
        ByteBuffer mssBuffer = ByteBuffer.allocateDirect(2);
        mssBuffer.putShort(IVpnConst.TCP_MSS);
        mssBuffer.flip();
        byte[] mssBytes = new byte[2];
        mssBuffer.get(mssBytes);
        tcpPacketBuilder.addOption(new TcpHeaderOption(TcpHeaderOption.Kind.MSS, mssBytes));
        int timestamp = TIMESTAMP.getAndIncrement();
        ByteBuffer timestampBuffer = ByteBuffer.allocateDirect(4);
        timestampBuffer.putInt(timestamp);
        timestampBuffer.flip();
        byte[] timestampBytes = new byte[4];
        timestampBuffer.get(timestampBytes);
        tcpPacketBuilder.addOption(new TcpHeaderOption(TcpHeaderOption.Kind.TSPOT, timestampBytes));
        return tcpPacketBuilder.build();
    }

    private void writeSyncAckToDevice() {
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        tcpPacketBuilder.ack(true);
        tcpPacketBuilder.syn(true);
        tcpPacketBuilder.window(this.currentWindowSize);
        ByteBuffer mssByteBuffer = ByteBuffer.allocate(2);
        mssByteBuffer.putShort(IVpnConst.TCP_MSS);
        tcpPacketBuilder.addOption(new TcpHeaderOption(TcpHeaderOption.Kind.MSS, mssByteBuffer.array()));
        TcpPacket tcpPacket = this.buildCommonTcpPacket(tcpPacketBuilder);
        try {
            this.tcpIpPacketWriter.write(this, tcpPacket);
        } catch (IOException e) {
            Log.e(TcpConnection.class.getName(),
                    "Fail to write sync ack tcp packet to device outbound queue because of error.", e);
        }
    }

    public void writeAckToDevice(byte[] ackData, int windowSize) {
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        tcpPacketBuilder.ack(true);
        tcpPacketBuilder.data(ackData);
        tcpPacketBuilder.window(windowSize);
        TcpPacket tcpPacket = this.buildCommonTcpPacket(tcpPacketBuilder);
        try {
            this.tcpIpPacketWriter.write(this, tcpPacket);
        } catch (IOException e) {
            Log.e(TcpConnection.class.getName(),
                    "Fail to write ack tcp packet to device outbound queue because of error.", e);
        }
    }

    public void writeRstToDevice() {
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        tcpPacketBuilder.ack(true);
        tcpPacketBuilder.rst(true);
        tcpPacketBuilder.window(0);
        TcpPacket tcpPacket = this.buildCommonTcpPacket(tcpPacketBuilder);
        try {
            this.tcpIpPacketWriter.write(this, tcpPacket);
        } catch (IOException e) {
            Log.e(TcpConnection.class.getName(),
                    "Fail to write rst tcp packet to device outbound queue because of error.", e);
        }
    }

    public void writeFinAckToDevice() {
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        tcpPacketBuilder.ack(true);
        tcpPacketBuilder.fin(true);
        tcpPacketBuilder.window(0);
        TcpPacket tcpPacket = this.buildCommonTcpPacket(tcpPacketBuilder);
        try {
            this.tcpIpPacketWriter.write(this, tcpPacket);
        } catch (IOException e) {
            Log.e(TcpConnection.class.getName(),
                    "Fail to write fin ack tcp packet to device outbound queue because of error.", e);
        }
    }

    private void writeFinToDevice() {
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        tcpPacketBuilder.fin(true);
        tcpPacketBuilder.window(0);
        TcpPacket tcpPacket = this.buildCommonTcpPacket(tcpPacketBuilder);
        try {
            this.tcpIpPacketWriter.write(this, tcpPacket);
        } catch (IOException e) {
            Log.e(TcpConnection.class.getName(),
                    "Fail to write fin tcp packet to device outbound queue because of error.", e);
        }
    }

    public String printTcpPacket(TcpPacket tcpPacket) {
        return tcpPacket + ", (Relative Device Sequence Number)=" +
                (tcpPacket.getHeader().getSequenceNumber() - this.deviceInitialSequenceNumber) +
                ", (Relative Device Acknowledgement Number)=" +
                (tcpPacket.getHeader().getAcknowledgementNumber() - this.vpnInitialSequenceNumber);
    }

    @Override
    public String toString() {
        return "TcpConnection{" + "TIMESTAMP=" + TIMESTAMP + ", id='" + id + '\'' + ", repositoryKey=" + repositoryKey +
                ", status=" + status + ", currentSequenceNumber=" + currentSequenceNumber +
                ", (Current Relative VPN Sequence Number)=" + this.countRelativeVpnSequenceNumber() +
                ", currentAcknowledgementNumber=" + currentAcknowledgementNumber +
                ", (Current Relative VPN Acknowledgement Number)=" + this.countRelativeDeviceSequenceNumber() +
                ", deviceInitialSequenceNumber=" + deviceInitialSequenceNumber + ", vpnInitialSequenceNumber=" +
                vpnInitialSequenceNumber + '}';
    }
}
