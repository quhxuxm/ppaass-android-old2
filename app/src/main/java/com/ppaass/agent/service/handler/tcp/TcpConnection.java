package com.ppaass.agent.service.handler.tcp;

import android.net.VpnService;
import android.util.Log;
import com.ppaass.agent.protocol.general.tcp.TcpHeader;
import com.ppaass.agent.protocol.general.tcp.TcpHeaderOption;
import com.ppaass.agent.protocol.general.tcp.TcpPacket;
import com.ppaass.agent.protocol.general.tcp.TcpPacketBuilder;
import com.ppaass.agent.service.IVpnConst;
import com.ppaass.agent.service.handler.TcpIpPacketWriter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Comparator;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final SocketChannel remoteSocketChannel;
    private final TcpIpPacketWriter tcpIpPacketWriter;
    private final BlockingQueue<TcpPacket> deviceInbound;
    private final Map<TcpConnectionRepositoryKey, TcpConnection> connectionRepository;
    private final AtomicReference<TcpConnectionStatus> status;
    private final AtomicLong currentSequenceNumber;
    private final AtomicLong currentAcknowledgementNumber;
    private final AtomicLong deviceInitialSequenceNumber;
    private final AtomicLong vpnInitialSequenceNumber;
    private final CountDownLatch establishLatch;
    private final AtomicBoolean running;
    //    private final Thread writeToDeviceTask;
    private final Runnable relayRemoteDataToDeviceTask;
    private final Runnable relayDeviceDataToRemoteTask;
    private final Runnable waitTime2MslTask;
    private final long writeToDeviceTimeout;
    private final long readFromDeviceTimeout;
    private final ByteBuffer deviceReceiveBuf;
    private final ExecutorService connectionExecutors;

    public TcpConnection(TcpConnectionRepositoryKey repositoryKey, TcpIpPacketWriter tcpIpPacketWriter,
                         Map<TcpConnectionRepositoryKey, TcpConnection> connectionRepository, VpnService vpnService,
                         ExecutorService connectionExecutors,
                         long writeToDeviceTimeout, long readFromDeviceTimeout) throws IOException {
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
        this.establishLatch = new CountDownLatch(1);
        this.remoteSocketChannel = SocketChannel.open();
        this.remoteSocketChannel.configureBlocking(true);
        this.connectionExecutors = connectionExecutors;
        if (!vpnService.protect(this.remoteSocketChannel.socket())) {
            Log.e(TcpConnection.class.getName(),
                    "Fail to protect vpn tcp socket, current connection: " + this + ", remote socket: " +
                            this.remoteSocketChannel);
            throw new UnsupportedOperationException("Fail to protect vpn tcp socket");
        }
        this.running = new AtomicBoolean(false);
        this.deviceReceiveBuf = ByteBuffer.allocateDirect(IVpnConst.READ_BUFFER_SIZE);
        this.relayDeviceDataToRemoteTask = new Runnable() {
            @Override
            public void run() {
                try {
                    TcpConnection.this.establishLatch.await();
                } catch (InterruptedException e) {
                    Log.e(TcpConnection.class.getName(),
                            "---->>>> Connection can not relay device data to remote because of exception, connection:  " +
                                    this, e);
                    TcpConnection.this.writeRstToDevice();
                    TcpConnection.this.finallyCloseTcpConnection();
                    return;
                }
                Log.d(TcpConnection.class.getName(),
                        "---->>>> Connection in establish status, begin to relay device data to remote, current connection: " +
                                TcpConnection.this);
                while (TcpConnection.this.running.get()) {
                    synchronized (TcpConnection.this.deviceReceiveBuf) {
                        try {
                            TcpConnection.this.deviceReceiveBuf.flip();
                            TcpConnection.this.remoteSocketChannel.write(TcpConnection.this.deviceReceiveBuf);
                            TcpConnection.this.deviceReceiveBuf.clear();
                            TcpConnection.this.deviceReceiveBuf.wait();
                        } catch (IOException e) {
                            Log.e(TcpConnection.class.getName(),
                                    "---->>>> Connection can not write device data to remote because of exception, connection:  " +
                                            this, e);
                            TcpConnection.this.writeRstToDevice();
                            TcpConnection.this.finallyCloseTcpConnection();
                            return;
                        } catch (InterruptedException e) {
                            Log.e(TcpConnection.class.getName(),
                                    "---->>>> Connection can not write device data to remote because of relay thread interrupted, connection:  " +
                                            this, e);
                            TcpConnection.this.writeRstToDevice();
                            TcpConnection.this.finallyCloseTcpConnection();
                            return;
                        }
                    }
                }
            }
        };
        this.relayRemoteDataToDeviceTask = new Runnable() {
            @Override
            public void run() {
                try {
                    TcpConnection.this.establishLatch.await();
                } catch (InterruptedException e) {
                    Log.e(TcpConnection.class.getName(),
                            "<<<<---- Connection can not relay remote data because of exception, connection:  " + this,
                            e);
                    TcpConnection.this.writeRstToDevice();
                    TcpConnection.this.finallyCloseTcpConnection();
                    return;
                }
                Log.d(TcpConnection.class.getName(),
                        "<<<<---- Connection in establish status, begin to relay remote data to device, current connection: " +
                                TcpConnection.this);
                while (TcpConnection.this.running.get()) {
                    ByteBuffer remoteDataBuf = null;
                    try {
                        remoteDataBuf = ByteBuffer.allocateDirect(IVpnConst.READ_REMOTE_BUFFER_SIZE);
                        int readSize = TcpConnection.this.remoteSocketChannel.read(remoteDataBuf);
                        if (readSize <= 0) {
                            synchronized (this) {
                                try {
                                    this.wait(500);
                                } catch (InterruptedException e) {
                                    Log.e(TcpConnection.class.getName(),
                                            "<<<<---- Relay remote data to device thread interrupted, current connection:  " +
                                                    TcpConnection.this, e);
                                    TcpConnection.this.writeRstToDevice();
                                    TcpConnection.this.finallyCloseTcpConnection();
                                    return;
                                }
                            }
                            if (TcpConnection.this.status.get() == TcpConnectionStatus.CLOSED_WAIT) {
                                TcpConnection.this.writeFinAckToDevice();
                                TcpConnection.this.status.set(TcpConnectionStatus.LAST_ACK);
                                TcpConnection.this.currentSequenceNumber.incrementAndGet();
                                Log.d(TcpConnection.class.getName(),
                                        "<<<<---- Move tcp connection from CLOSED_WAIT to  LAST_ACK, current connection:  " +
                                                TcpConnection.this);
                                return;
                            }
                            continue;
                        }
                    } catch (Exception e) {
                        Log.e(TcpConnection.class.getName(),
                                "<<<<---- Read remote data fail, current connection:  " + TcpConnection.this, e);
                        TcpConnection.this.writeRstToDevice();
                        TcpConnection.this.finallyCloseTcpConnection();
                        return;
                    }
                    remoteDataBuf.flip();
                    //Relay remote data to device and use mss as the transfer unit
                    while (remoteDataBuf.hasRemaining()) {
                        int mssDataLength = Math.min(IVpnConst.TCP_MSS, remoteDataBuf.remaining());
                        byte[] mssData = new byte[mssDataLength];
                        remoteDataBuf.get(mssData);
                        TcpConnection.this.writeAckToDevice(mssData,
                                TcpConnection.this.countDeviceReceiveBufRemainingSpace());
                        // Data should write to device first then increase the sequence number
                        TcpConnection.this.currentSequenceNumber.addAndGet(mssData.length);
                        Log.d(TcpConnection.class.getName(),
                                "<<<<---- Receive remote data write ack to device, current connection: " +
                                        TcpConnection.this + ", remote data size: " + mssData.length +
                                        ", remote data:\n\n" + new String(mssData) + "\n\n");
                    }
                }
            }
        };
        this.waitTime2MslTask = new Runnable() {
            @Override
            public void run() {
                synchronized (this) {
                    try {
                        this.wait(2 * 60 * 1000);
                    } catch (InterruptedException e) {
                        Log.e(TcpConnection.class.getName(), "Fail to execute waite time 2 msl task.", e);
                    } finally {
                        TcpConnection.this.finallyCloseTcpConnection();
                    }
                }
            }
        };
    }

    private int generateVpnInitialSequenceNumber() {
        return INITIAL_SEQ.getAndIncrement();
    }

    public void start() {
        this.running.set(true);
    }

    private void finallyCloseTcpConnection() {
//        this.deviceInbound.clear();
        this.status.set(TcpConnectionStatus.CLOSED);
        this.connectionRepository.remove(this.repositoryKey);
        this.running.set(false);
        synchronized (this.deviceReceiveBuf) {
            this.deviceReceiveBuf.notify();
        }
        this.establishLatch.countDown();
        synchronized (this.deviceInbound) {
            this.deviceInbound.notify();
        }
        this.closeRemoteSocket();
    }

    private void connectRemote() throws Exception {
        this.remoteSocketChannel.connect(
                new InetSocketAddress(InetAddress.getByAddress(this.repositoryKey.getDestinationAddress()),
                        this.repositoryKey.getDestinationPort()));
    }

    public void onDeviceInbound(TcpPacket tcpPacket) throws Exception {
        if (!this.deviceInbound.offer(tcpPacket, this.readFromDeviceTimeout, TimeUnit.MILLISECONDS)) {
            Log.e(TcpConnection.class.getName(),
                    ">>>>>>>> Fail to offer tcp packet from device inbound, current connection: " + this +
                            ", tcp packet: " + tcpPacket + ", device inbound size: " + deviceInbound.size());
            this.finallyCloseTcpConnection();
        }
    }

    public TcpConnectionRepositoryKey getRepositoryKey() {
        return repositoryKey;
    }

    public void run() {
        while (this.running.get()) {
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
                        //Receive Fin on established status.
                        //After fin, no more device data will be sent to vpn
                        synchronized (this.deviceReceiveBuf) {
                            int remainingSpace = this.countDeviceReceiveBufRemainingSpace();
                            if (tcpPacket.getData().length > remainingSpace) {
                                Log.d(TcpConnection.class.getName(),
                                        ">>>>>>>> Receive ACK - (PSH=" + tcpHeader.isPsh() +
                                                ") but no space in the device receive buffer, ack to discount window size, current connection:  " +
                                                this + ", incoming tcp packet: " + this.printTcpPacket(tcpPacket) +
                                                " , device data size: " + tcpPacket.getData().length +
                                                ", write data: \n\n" +
                                                new String(tcpPacket.getData()) + "\n\n");
                                this.writeAckToDevice(null, remainingSpace);
                                return;
                            }
                            receiveDeviceData(tcpPacket, tcpHeader, remainingSpace);
                        }
                        Log.d(TcpConnection.class.getName(),
                                ">>>>>>>> Receive fin ack, current connection:  " + this +
                                        ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
                        this.status.set(TcpConnectionStatus.WAIT_TIME);
                        this.currentAcknowledgementNumber.incrementAndGet();
                        this.writeAckToDevice(null, this.countDeviceReceiveBufRemainingSpace());
                        this.connectionExecutors.execute(this.waitTime2MslTask);
                        Log.d(TcpConnection.class.getName(),
                                ">>>>>>>> Receive fin ack on FIN_WAIT2 and start 2msl task, current connection:  " +
                                        this +
                                        ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
                        continue;
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
                                            this +
                                            ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
                            this.writeRstToDevice();
                            this.finallyCloseTcpConnection();
                            return;
                        }
                        try {
                            this.connectRemote();
                        } catch (Exception e) {
                            this.writeRstToDevice();
                            this.finallyCloseTcpConnection();
                            Log.e(TcpConnection.class.getName(),
                                    ">>>>>>>> Connect to remote fail, current connection:  " + this +
                                            ", incoming tcp packet: " + this.printTcpPacket(tcpPacket), e);
                            return;
                        }
                        this.currentSequenceNumber.getAndIncrement();
                        this.status.set(TcpConnectionStatus.ESTABLISHED);
                        //Start remote relay to device task
                        this.connectionExecutors.execute(this.relayRemoteDataToDeviceTask);
                        //Start device relay to remote task
                        this.connectionExecutors.execute(this.relayDeviceDataToRemoteTask);
                        this.establishLatch.countDown();
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
                        synchronized (this.deviceReceiveBuf) {
                            int remainingSpace = this.countDeviceReceiveBufRemainingSpace();
                            if (tcpPacket.getData().length > remainingSpace) {
                                Log.d(TcpConnection.class.getName(),
                                        ">>>>>>>> Receive ACK - (PSH=" + tcpHeader.isPsh() +
                                                ") but no space in the device receive buffer, ack to discount window size, current connection:  " +
                                                this + ", incoming tcp packet: " + this.printTcpPacket(tcpPacket) +
                                                " , device data size: " + tcpPacket.getData().length +
                                                ", write data: \n\n" +
                                                new String(tcpPacket.getData()) + "\n\n");
                                this.writeAckToDevice(null, remainingSpace);
                                return;
                            }
                            receiveDeviceData(tcpPacket, tcpHeader, remainingSpace);
                            continue;
                        }
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
                                    this +
                                    ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
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
                        this.writeAckToDevice(null, this.countDeviceReceiveBufRemainingSpace());
                        continue;
                    }
                    Log.e(TcpConnection.class.getName(),
                            ">>>>>>>> Incorrect connection status going to close connection, current connection: " +
                                    this +
                                    ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
                    this.writeRstToDevice();
                    this.finallyCloseTcpConnection();
                    return;
                }
                Log.e(TcpConnection.class.getName(),
                        ">>>>>>>> Incorrect tcp header going to close connection, current connection: " +
                                this +
                                ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
                this.writeRstToDevice();
                this.finallyCloseTcpConnection();
                return;
            } finally {
                Log.d(TcpConnection.class.getName(),
                        "Current connection: " + this + ", connection repository: " + this.connectionRepository);
            }
        }
    }

    private void receiveDeviceData(TcpPacket tcpPacket, TcpHeader tcpHeader, int remainingSpace) {
        this.deviceReceiveBuf.put(tcpPacket.getData());
        int dataLength = tcpPacket.getData().length;
        this.currentAcknowledgementNumber.addAndGet(dataLength);
        this.writeAckToDevice(null, remainingSpace);
        this.deviceReceiveBuf.notify();
        Log.d(TcpConnection.class.getName(), ">>>>>>>> Receive ACK - (PSH=" + tcpHeader.isPsh() +
                ") and put device data into device receive buffer, current connection:  " + this +
                ", incoming tcp packet: " + this.printTcpPacket(tcpPacket) + " , device data size: " +
                tcpPacket.getData().length + ", write data: \n\n" + new String(tcpPacket.getData()) +
                "\n\n");
    }

    private int countDeviceReceiveBufRemainingSpace() {
        synchronized (this.deviceReceiveBuf) {
            return this.deviceReceiveBuf.remaining();
        }
    }

    private long countRelativeVpnSequenceNumber() {
        return this.currentSequenceNumber.get() - this.vpnInitialSequenceNumber.get();
    }

    private long countRelativeDeviceSequenceNumber() {
        return this.currentAcknowledgementNumber.get() - this.deviceInitialSequenceNumber.get();
    }

    private void closeRemoteSocket() {
        try {
            if (this.remoteSocketChannel == null) {
                return;
            }
            this.remoteSocketChannel.close();
        } catch (IOException e) {
            Log.e(TcpConnection.class.getName(), "Exception happen when close remote socket.", e);
        }
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

    private void writeAckToDevice(byte[] ackData, int windowSize) {
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

    private void writeRstToDevice() {
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

    private void writePshAckToDevice(byte[] ackData) {
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

    private void writeFinAckToDevice() {
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        tcpPacketBuilder.ack(true);
        tcpPacketBuilder.fin(true);
        tcpPacketBuilder.window(this.countDeviceReceiveBufRemainingSpace());
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
