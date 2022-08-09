package com.ppaass.agent.service.handler.tcp;

import android.util.Log;
import com.ppaass.agent.protocol.general.tcp.TcpHeader;
import com.ppaass.agent.protocol.general.tcp.TcpHeaderOption;
import com.ppaass.agent.protocol.general.tcp.TcpPacket;
import com.ppaass.agent.protocol.general.tcp.TcpPacketBuilder;
import com.ppaass.agent.service.IVpnConst;
import com.ppaass.agent.service.handler.TcpIpPacketWriter;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.util.AttributeKey;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.Map;
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
        Random random = new Random();
        int nextRandom = Math.abs(random.nextInt());
        INITIAL_SEQ.set(nextRandom);
    }

    private static final AtomicInteger TIMESTAMP = new AtomicInteger();
    private final String id;
    private final TcpConnectionRepositoryKey repositoryKey;
    private Channel remoteChannel;
    private final Bootstrap remoteBootstrap;
    private final TcpIpPacketWriter tcpIpPacketWriter;
    private final BlockingQueue<TcpPacket> deviceInbound;
    private final Map<TcpConnectionRepositoryKey, TcpConnection> connectionRepository;
    private final AtomicReference<TcpConnectionStatus> status;
    private final AtomicLong currentSequenceNumber;
    private final AtomicLong currentAcknowledgementNumber;
    private final AtomicLong deviceInitialSequenceNumber;
    private final AtomicLong vpnInitialSequenceNumber;
    //    private final Thread writeToDeviceTask;
//    private final Runnable waitTime2MslTask;
    private final long writeToDeviceTimeout;
    private final long readFromDeviceTimeout;

    public TcpConnection(TcpConnectionRepositoryKey repositoryKey, TcpIpPacketWriter tcpIpPacketWriter,
                         Map<TcpConnectionRepositoryKey, TcpConnection> connectionRepository, long writeToDeviceTimeout,
                         long readFromDeviceTimeout,
                         Bootstrap remoteBootstrap) {
        this.writeToDeviceTimeout = writeToDeviceTimeout;
        this.readFromDeviceTimeout = readFromDeviceTimeout;
        this.id = UUID.randomUUID().toString().replace("-", "");
        this.repositoryKey = repositoryKey;
        this.status = new AtomicReference<>(TcpConnectionStatus.LISTEN);
        this.currentAcknowledgementNumber = new AtomicLong(0);
        this.currentSequenceNumber = new AtomicLong(0);
        this.deviceInitialSequenceNumber = new AtomicLong(0);
        this.vpnInitialSequenceNumber = new AtomicLong(0);
        this.deviceInbound =
                new PriorityBlockingQueue<>(1024, Comparator.comparingLong(p -> p.getHeader().getSequenceNumber()));
        this.tcpIpPacketWriter = tcpIpPacketWriter;
        this.connectionRepository = connectionRepository;
        this.remoteBootstrap = remoteBootstrap;
//        this.waitTime2MslTask = new Runnable() {
//            @Override
//            public void run() {
//                synchronized (this) {
//                    try {
//                        this.wait(2 * 60 * 1000);
//                    } catch (InterruptedException e) {
//                        Log.e(TcpConnection.class.getName(), "Fail to execute waite time 2 msl task.", e);
//                    } finally {
//                        TcpConnection.this.finallyCloseTcpConnection();
//                    }
//                }
//            }
//        };
    }

    private int generateVpnInitialSequenceNumber() {
        return INITIAL_SEQ.getAndIncrement();
    }

    public void finallyCloseTcpConnection() {
        this.deviceInbound.clear();
        this.status.set(TcpConnectionStatus.CLOSED);
        this.connectionRepository.remove(this.repositoryKey);
        try {
            if (remoteChannel != null) {
                this.remoteChannel.close();
            }
        } catch (Exception e) {
            Log.e(TcpConnection.class.getName(),
                    ">>>>>>>> Fail to close remote channel, current connection: " + this +
                            ", device inbound size: " + deviceInbound.size(), e);
        }
    }

    private Channel connectRemote() throws Exception {
        AttributeKey<TcpConnection> tcpConnectionKey = AttributeKey.valueOf(IVpnConst.TCP_CONNECTION);
        InetSocketAddress remoteAddress =
                new InetSocketAddress(InetAddress.getByAddress(this.repositoryKey.getDestinationAddress()),
                        this.repositoryKey.getDestinationPort());
        ChannelFuture channelFuture = remoteBootstrap.connect(remoteAddress);
        channelFuture.channel().attr(tcpConnectionKey).setIfAbsent(this);
        channelFuture = channelFuture.sync();
        if (channelFuture.isSuccess()) {
            Log.d(TcpConnection.class.getName(), ">>>>>>>> Success connect to remote: " + remoteAddress);
            return channelFuture.channel();
        } else {
            Log.d(TcpConnection.class.getName(), ">>>>>>>> Fail connect to remote: " + remoteAddress,
                    channelFuture.cause());
            return null;
        }
    }

    public void onDeviceInbound(TcpPacket tcpPacket) throws Exception {
        this.deviceInbound.put(tcpPacket);
    }

    public TcpConnectionRepositoryKey getRepositoryKey() {
        return repositoryKey;
    }

    public TcpConnectionStatus getStatus() {
        return status.get();
    }

    public void setStatus(TcpConnectionStatus status) {
        this.status.set(status);
    }

    public AtomicLong getCurrentAcknowledgementNumber() {
        return currentAcknowledgementNumber;
    }

    public AtomicLong getCurrentSequenceNumber() {
        return currentSequenceNumber;
    }

    public void run() {
        while (this.status.get() != TcpConnectionStatus.CLOSED) {
            try {
                TcpPacket tcpPacket = null;
                try {
                    tcpPacket = this.deviceInbound.poll(this.readFromDeviceTimeout, TimeUnit.MILLISECONDS);
//                    tcpPacket = this.deviceInbound.take();
                } catch (Exception e) {
                    Log.e(TcpConnection.class.getName(),
                            ">>>>>>>> Fail to take tcp packet from inbound queue, current connection: " + this +
                                    ", device inbound size: " + deviceInbound.size(), e);
                    this.writeRstToDevice();
                    this.finallyCloseTcpConnection();
                    return;
                }
                if (tcpPacket == null) {
                    Log.e(TcpConnection.class.getName(),
                            ">>>>>>>> Fail to take tcp packet from inbound queue because of timeout, current connection: " +
                                    this + ", device inbound size: " + deviceInbound.size());
                    this.writeRstToDevice();
                    this.finallyCloseTcpConnection();
                    return;
                }
                Log.d(TcpConnection.class.getName(),
                        ">>>>>>>> Success take tcp packet from device inbound, current connection: " + this +
                                ", tcp packet: " + tcpPacket + ", device inbound size: " + deviceInbound.size());
                TcpHeader tcpHeader = tcpPacket.getHeader();
                if (tcpHeader.isSyn()) {
                    if (this.status.get() == TcpConnectionStatus.SYNC_RCVD) {
                        Log.e(TcpConnection.class.getName(),
                                ">>>>>>>> Receive duplicate sync tcp packet, current connection: " + this +
                                        ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
                        this.writeRstToDevice();
                        this.finallyCloseTcpConnection();
                        return;
                    }
                    //Initialize ack number and seq number
                    this.deviceInitialSequenceNumber.set(tcpHeader.getSequenceNumber());
                    this.currentAcknowledgementNumber.set(tcpHeader.getSequenceNumber() + 1);
                    long vpnIsn = this.generateVpnInitialSequenceNumber();
                    this.currentSequenceNumber.set(vpnIsn);
                    this.vpnInitialSequenceNumber.set(vpnIsn);
                    this.status.set(TcpConnectionStatus.SYNC_RCVD);
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
                        if (this.status.get() == TcpConnectionStatus.SYNC_RCVD) {
                            Log.e(TcpConnection.class.getName(),
                                    ">>>>>>>> Receive fin ack on SYNC_RCVD close connection directly, current connection:  " +
                                            this + ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
                            this.writeRstToDevice();
                            this.finallyCloseTcpConnection();
                            return;
                        }
                        if (this.status.get() == TcpConnectionStatus.CLOSED) {
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
                        this.status.set(TcpConnectionStatus.WAIT_TIME);
                        this.currentAcknowledgementNumber.incrementAndGet();
                        this.writeAckToDevice(null);
                        // TODO should make 2msl close, here just close directly
                        this.finallyCloseTcpConnection();
                        Log.d(TcpConnection.class.getName(),
                                ">>>>>>>> Receive fin ack on FIN_WAIT2 and start 2msl task, current connection:  " +
                                        this + ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
                        return;
                    }
                    if (this.status.get() == TcpConnectionStatus.FIN_WAIT1) {
                        this.status.set(TcpConnectionStatus.FIN_WAIT2);
                        Log.d(TcpConnection.class.getName(),
                                ">>>>>>>> Receive ack on FIN_WAIT1, current connection:  " + this +
                                        ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
                        continue;
                    }
                    if (this.status.get() == TcpConnectionStatus.SYNC_RCVD) {
                        if (this.currentSequenceNumber.get() + 1 != tcpHeader.getAcknowledgementNumber()) {
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
                        this.currentSequenceNumber.getAndIncrement();
                        this.status.set(TcpConnectionStatus.ESTABLISHED);
                        Log.d(TcpConnection.class.getName(),
                                ">>>>>>>> Receive ack and remote connection established, current connection: " + this +
                                        ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
                        continue;
                    }
                    if (this.status.get() == TcpConnectionStatus.ESTABLISHED) {
                        if (this.currentAcknowledgementNumber.get() > tcpHeader.getSequenceNumber()) {
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
                                        ">>>>>>>> Write to remote fail(ack on ESTABLISHED), current connection:  " + this +
                                                ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
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
                    if (this.status.get() == TcpConnectionStatus.CLOSED_WAIT) {
                        // No data will from device anymore, just to consume the ack from device
                        Log.d(TcpConnection.class.getName(), ">>>>>>>> Receive ACK - (PSH=" + tcpHeader.isPsh() +
                                ") on CLOSE_WAIT, current connection:  " + this + ", incoming tcp packet: " +
                                this.printTcpPacket(tcpPacket));
                        continue;
                    }
                    if (this.status.get() == TcpConnectionStatus.LAST_ACK) {
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
                    if (this.status.get() == TcpConnectionStatus.ESTABLISHED) {
                        //After fin, no more device data will be sent to vpn
                        Log.d(TcpConnection.class.getName(),
                                ">>>>>>>> Receive fin when connection ESTABLISHED, current connection: " + this +
                                        ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
                        this.status.set(TcpConnectionStatus.CLOSED_WAIT);
                        this.currentAcknowledgementNumber.incrementAndGet();
                        this.writeAckToDevice(null);
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
                        "Current connection: " + this + ", connection repository size: " +
                                this.connectionRepository.size() + ", device inbound size: " +
                                this.deviceInbound.size());
            }
        }
        this.finallyCloseTcpConnection();
    }

    private void writeDataToRemote(TcpPacket tcpPacket) throws IOException {
        int dataLength = tcpPacket.getData().length;
        this.currentAcknowledgementNumber.addAndGet(dataLength);
        this.writeAckToDevice(null);
        if (tcpPacket.getData() != null && tcpPacket.getData().length > 0) {
            ByteBuf dataBuffer = Unpooled.wrappedBuffer(tcpPacket.getData());
            this.remoteChannel.writeAndFlush(dataBuffer);
            Log.d(TcpConnection.class.getName(),
                    ">>>>>>>> Receive ACK - (PSH=" + tcpPacket.getHeader().isPsh() +
                            ") and put device data into device receive buffer, current connection:  " + this +
                            ", incoming tcp packet: " + this.printTcpPacket(tcpPacket) +
                            " , device data size: " +
                            tcpPacket.getData().length + ", write data: \n\n" +
                            ByteBufUtil.prettyHexDump(Unpooled.wrappedBuffer(tcpPacket.getData())) + "\n\n");
        }
    }

    private long countRelativeVpnSequenceNumber() {
        return this.currentSequenceNumber.get() - this.vpnInitialSequenceNumber.get();
    }

    private long countRelativeDeviceSequenceNumber() {
        return this.currentAcknowledgementNumber.get() - this.deviceInitialSequenceNumber.get();
    }

    private TcpPacket buildCommonTcpPacket(TcpPacketBuilder tcpPacketBuilder) {
        tcpPacketBuilder.destinationPort(this.repositoryKey.getSourcePort());
        tcpPacketBuilder.sourcePort(this.repositoryKey.getDestinationPort());
        tcpPacketBuilder.sequenceNumber(this.currentSequenceNumber.get());
        tcpPacketBuilder.acknowledgementNumber(this.currentAcknowledgementNumber.get());
        tcpPacketBuilder.window(IVpnConst.TCP_WINDOW);
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

    public void writeAckToDevice(byte[] ackData) {
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        tcpPacketBuilder.ack(true);
        tcpPacketBuilder.data(ackData);
        tcpPacketBuilder.window(IVpnConst.TCP_WINDOW);
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
        TcpPacket tcpPacket = this.buildCommonTcpPacket(tcpPacketBuilder);
        try {
            this.tcpIpPacketWriter.write(this, tcpPacket);
        } catch (IOException e) {
            Log.e(TcpConnection.class.getName(),
                    "Fail to write rst tcp packet to device outbound queue because of error.", e);
        }
    }

    public void writePshAckToDevice(byte[] ackData) {
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        tcpPacketBuilder.ack(true);
        tcpPacketBuilder.psh(true);
        tcpPacketBuilder.data(ackData);
        TcpPacket tcpPacket = this.buildCommonTcpPacket(tcpPacketBuilder);
        try {
            this.tcpIpPacketWriter.write(this, tcpPacket);
        } catch (IOException e) {
            Log.e(TcpConnection.class.getName(),
                    "Fail to write psh ack tcp packet to device outbound queue because of error.", e);
        }
    }

    public void writeFinAckToDevice() {
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        tcpPacketBuilder.ack(true);
        tcpPacketBuilder.fin(true);
        tcpPacketBuilder.window(IVpnConst.TCP_WINDOW);
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
                (tcpPacket.getHeader().getSequenceNumber() - this.deviceInitialSequenceNumber.get()) +
                ", (Relative Device Acknowledgement Number)=" +
                (tcpPacket.getHeader().getAcknowledgementNumber() - this.vpnInitialSequenceNumber.get());
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
