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
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.PriorityBlockingQueue;
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
    private final BlockingQueue<InboundAction> deviceInbound;
    private final BlockingQueue<OutboundAction> deviceOutbound;
    private final Map<TcpConnectionRepositoryKey, TcpConnection> connectionRepository;
    private final AtomicReference<TcpConnectionStatus> status;
    private final AtomicLong currentSequenceNumber;
    private final AtomicLong currentAcknowledgementNumber;
    private final AtomicLong deviceInitialSequenceNumber;
    private final AtomicLong vpnInitialSequenceNumber;
    private final CountDownLatch establishLatch;
    private final AtomicBoolean running;
    private final AtomicBoolean remoteRelayRunning;
    private final Thread writeToDeviceTask;
    private final Thread writeToRemoteTask;
    private final long writeToDeviceTimeout;
    private final long readFromDeviceTimeout;

    private enum OutboundActionType {
        STOP, OUTPUT_TO_DEVICE
    }

    private static class OutboundAction {
        private final TcpPacket tcpPacket;
        private final OutboundActionType type;

        private OutboundAction(TcpPacket tcpPacket, OutboundActionType type) {
            this.tcpPacket = tcpPacket;
            this.type = type;
        }
    }

    private enum InboundActionType {
        STOP, INPUT_TO_REMOTE
    }

    private static class InboundAction {
        private final TcpPacket tcpPacket;
        private final InboundActionType type;

        private InboundAction(TcpPacket tcpPacket, InboundActionType type) {
            this.tcpPacket = tcpPacket;
            this.type = type;
        }
    }

    public TcpConnection(TcpConnectionRepositoryKey repositoryKey, TcpIpPacketWriter tcpIpPacketWriter,
                         Map<TcpConnectionRepositoryKey, TcpConnection> connectionRepository, VpnService vpnService,
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
        this.deviceInbound = new PriorityBlockingQueue<>(1024, (a1, a2) -> {
            if (a1.type == InboundActionType.INPUT_TO_REMOTE && a2.type == InboundActionType.INPUT_TO_REMOTE) {
                return Long.compare(a1.tcpPacket.getHeader().getSequenceNumber(),
                        a2.tcpPacket.getHeader().getSequenceNumber());
            }
            return 0;
        });
        this.deviceOutbound = new PriorityBlockingQueue<>(1024, (a1, a2) -> {
            if (a1.type == OutboundActionType.OUTPUT_TO_DEVICE && a2.type == OutboundActionType.OUTPUT_TO_DEVICE) {
                return Long.compare(a1.tcpPacket.getHeader().getSequenceNumber(),
                        a2.tcpPacket.getHeader().getSequenceNumber());
            }
            return 0;
        });
        this.tcpIpPacketWriter = tcpIpPacketWriter;
        this.connectionRepository = connectionRepository;
        this.establishLatch = new CountDownLatch(1);
        this.remoteSocketChannel = SocketChannel.open();
        this.remoteSocketChannel.configureBlocking(true);
        if (!vpnService.protect(this.remoteSocketChannel.socket())) {
            Log.e(TcpConnection.class.getName(),
                    "Fail to protect vpn tcp socket, current connection: " + this + ", remote socket: " +
                            this.remoteSocketChannel);
            throw new UnsupportedOperationException("Fail to protect vpn tcp socket");
        }
        this.running = new AtomicBoolean(false);
        this.remoteRelayRunning = new AtomicBoolean(false);
        this.writeToDeviceTask = new Thread(() -> {
            while (TcpConnection.this.running.get()) {
                try {
                    OutboundAction outboundAction = TcpConnection.this.deviceOutbound.take();
                    if (outboundAction.type == OutboundActionType.STOP) {
                        TcpConnection.this.deviceOutbound.clear();
                        if (TcpConnection.this.status.get() == TcpConnectionStatus.CLOSE_WAIT) {
                            TcpConnection.this.status.set(TcpConnectionStatus.LAST_ACK);
                            this.currentSequenceNumber.incrementAndGet();
                            this.writeFinAckToDevice();
                            Log.d(TcpConnection.class.getName(),
                                    "<<<<<<<< Write fin ack to device, begin to close remote relay connection: " +
                                            this);
                        }
                        return;
                    }
                    TcpConnection.this.tcpIpPacketWriter.write(TcpConnection.this, outboundAction.tcpPacket);
                    Log.d(TcpConnection.class.getName(),
                            "<<<<<<<< Write tcp packet to device, current connection: " + TcpConnection.this +
                                    ", tcp packet: " + outboundAction.tcpPacket);
                } catch (InterruptedException | IOException e) {
                    Log.e(TcpConnection.class.getName(),
                            "<<<<<<<< Fail to get tcp packet from outbound queue because of exception, current connection: " +
                                    TcpConnection.this, e);
                }
            }
        });
        this.writeToRemoteTask = new Thread(() -> {
            try {
                TcpConnection.this.establishLatch.await();
            } catch (InterruptedException e) {
                TcpConnection.this.writeRstToDevice();
                TcpConnection.this.finallyCloseTcpConnection();
                Log.e(TcpConnection.class.getName(),
                        "<<<<<<<< Connection can not relay remote data because of exception, connection:  " + this, e);
                return;
            }
            Log.d(TcpConnection.class.getName(),
                    "<<<<<<<< Connection in establish status, begin to relay remote data to device, current connection: " +
                            TcpConnection.this);
            while (TcpConnection.this.remoteRelayRunning.get()) {
                ByteBuffer remoteDataBuf = null;
                try {
                    remoteDataBuf = TcpConnection.this.readFromRemote();
                } catch (Exception e) {
                    TcpConnection.this.writeRstToDevice();
                    TcpConnection.this.finallyCloseTcpConnection();
                    Log.e(TcpConnection.class.getName(),
                            "<<<<<<<< Read remote data fail, current connection:  " + this);
                    return;
                }
                if (!remoteDataBuf.hasRemaining()) {
                    return;
                }
                //Relay remote data to device and use mss as the transfer unit
                while (remoteDataBuf.hasRemaining()) {
                    int mssDataLength = Math.min(IVpnConst.TCP_MSS, remoteDataBuf.remaining());
                    byte[] mssData = new byte[mssDataLength];
                    remoteDataBuf.get(mssData);
                    TcpConnection.this.writeAckToDevice(mssData);
                    // Data should write to device first then increase the sequence number
                    TcpConnection.this.currentSequenceNumber.addAndGet(mssData.length);
                    Log.d(TcpConnection.class.getName(),
                            "<<<<<<<< Receive remote data write ack to device, current connection: " +
                                    TcpConnection.this + ", remote data size: " + mssData.length +
                                    ", remote data:\n\n" + new String(mssData) + "\n\n");
                }
            }
        });
    }

    private int generateVpnInitialSequenceNumber() {
        return INITIAL_SEQ.getAndIncrement();
    }

    public void start() {
        this.running.set(true);
    }

    private void finallyCloseTcpConnection() {
        try {
            this.deviceInbound.put(new InboundAction(null, InboundActionType.STOP));
        } catch (InterruptedException e) {
            Log.e(TcpConnection.class.getName(), "Fail to put STOP action to device inbound queue", e);
        }
        try {
            this.deviceOutbound.put(new OutboundAction(null, OutboundActionType.STOP));
        } catch (InterruptedException e) {
            Log.e(TcpConnection.class.getName(), "Fail to put STOP action to device outbound queue", e);
        }
        this.running.set(false);
        this.remoteRelayRunning.set(false);
        this.status.set(TcpConnectionStatus.CLOSED);
        this.connectionRepository.remove(this.repositoryKey);
        this.closeRemoteSocket();
    }

    private void connectRemote() throws Exception {
        this.remoteSocketChannel.connect(
                new InetSocketAddress(InetAddress.getByAddress(this.repositoryKey.getDestinationAddress()),
                        this.repositoryKey.getDestinationPort()));
    }

    public void onDeviceInbound(TcpPacket tcpPacket) throws Exception {
        this.deviceInbound.put(new InboundAction(tcpPacket, InboundActionType.INPUT_TO_REMOTE));
    }

    public TcpConnectionRepositoryKey getRepositoryKey() {
        return repositoryKey;
    }

    public void run() {
        //Start write to device task
        this.writeToDeviceTask.start();
        //Start remote relay task
        this.writeToRemoteTask.start();
        while (this.running.get()) {
            InboundAction inboundAction = null;
            try {
                inboundAction = this.deviceInbound.take();
            } catch (Exception e) {
                this.writeRstToDevice();
                this.finallyCloseTcpConnection();
                Log.e(TcpConnection.class.getName(),
                        ">>>>>>>> Fail to take tcp packet from inbound queue, current connection: " + this, e);
                return;
            }
            if (inboundAction.type == InboundActionType.STOP) {
                this.deviceInbound.clear();
                return;
            }
            TcpPacket tcpPacket = inboundAction.tcpPacket;
            TcpHeader tcpHeader = tcpPacket.getHeader();
            if (tcpHeader.isSyn()) {
                if (this.status.get() == TcpConnectionStatus.SYNC_RCVD) {
                    this.writeRstToDevice();
                    this.finallyCloseTcpConnection();
                    Log.e(TcpConnection.class.getName(),
                            ">>>>>>>> Receive duplicate sync tcp packet, current connection: " + this +
                                    ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
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
            if (tcpHeader.isAck()) {
                if (this.status.get() == TcpConnectionStatus.SYNC_RCVD) {
                    if (this.currentSequenceNumber.get() + 1 != tcpHeader.getAcknowledgementNumber()) {
                        this.writeRstToDevice();
                        this.finallyCloseTcpConnection();
                        Log.e(TcpConnection.class.getName(),
                                ">>>>>>>> Connection current seq number do not match incoming ack number:  " + this +
                                        ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
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
                    //Make remote relay start to work
                    this.remoteRelayRunning.set(true);
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
                    //Transfer data
                    try {
                        this.writeToRemote(tcpPacket.getData());
                    } catch (Exception e) {
                        this.writeRstToDevice();
                        this.finallyCloseTcpConnection();
                        Log.e(TcpConnection.class.getName(),
                                ">>>>>>>> Fail to write device data to remote (ESTABLISHED) because of exception, current connection:  " +
                                        this + ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
                        return;
                    }
                    int dataLength = tcpPacket.getData().length;
                    this.currentAcknowledgementNumber.addAndGet(dataLength);
                    this.writeAckToDevice(null);
                    Log.d(TcpConnection.class.getName(), ">>>>>>>> Receive ACK - (PSH=" + tcpHeader.isPsh() +
                            ") and write device data to remote, current connection:  " + this +
                            ", incoming tcp packet: " + this.printTcpPacket(tcpPacket) + " , device data size: " +
                            tcpPacket.getData().length + ", write data: \n\n" + new String(tcpPacket.getData()) +
                            "\n\n");
                    continue;
                }
                if (this.status.get() == TcpConnectionStatus.CLOSE_WAIT) {
                    // No data will from device anymore, just to consume the ack from device
                    Log.d(TcpConnection.class.getName(), ">>>>>>>> Receive ACK - (PSH=" + tcpHeader.isPsh() +
                            ") on CLOSE_WAIT, current connection:  " + this +
                            ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
                    continue;
                }
                if (this.status.get() == TcpConnectionStatus.LAST_ACK) {
                    // Finally close the tcp connection after receive Fin from client.
                    this.finallyCloseTcpConnection();
                    Log.d(TcpConnection.class.getName(),
                            ">>>>>>>> Receive last ack, close connection: " + this + ", incoming tcp packet: " +
                                    this.printTcpPacket(tcpPacket));
                    return;
                }
                this.writeRstToDevice();
                this.finallyCloseTcpConnection();
                Log.e(TcpConnection.class.getName(),
                        ">>>>>>>> Incorrect connection status going to close connection, current connection: " + this +
                                ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
                return;
            }
            if (tcpHeader.isFin()) {
                //Receive Fin on established status.
                this.status.set(TcpConnectionStatus.CLOSE_WAIT);
                this.currentAcknowledgementNumber.incrementAndGet();
                this.writeAckToDevice(null);
                // Continue to make sure remote data all relay to device
                try {
                    this.deviceOutbound.put(new OutboundAction(null, OutboundActionType.STOP));
                } catch (InterruptedException e) {
                    Log.e(TcpConnection.class.getName(), "Fail to put STOP action to device outbound queue", e);
                }
            }
        }
    }

    private long countRelativeVpnSequenceNumber() {
        return this.currentSequenceNumber.get() - this.vpnInitialSequenceNumber.get();
    }

    private long countRelativeDeviceSequenceNumber() {
        return this.currentAcknowledgementNumber.get() - this.deviceInitialSequenceNumber.get();
    }

    private void writeToRemote(byte[] data) throws Exception {
        this.remoteSocketChannel.write(ByteBuffer.wrap(data));
    }

    private ByteBuffer readFromRemote() throws Exception {
        ByteBuffer readBuffer = ByteBuffer.allocateDirect(IVpnConst.READ_REMOTE_BUFFER_SIZE);
        this.remoteSocketChannel.read(readBuffer);
        readBuffer.flip();
        return readBuffer;
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
            this.deviceOutbound.put(new OutboundAction(tcpPacket, OutboundActionType.OUTPUT_TO_DEVICE));
        } catch (InterruptedException e) {
            Log.e(TcpConnection.class.getName(),
                    "Fail to write sync ack tcp packet to device outbound queue because of error.", e);
        }
    }

    private void writeAckToDevice(byte[] ackData) {
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        tcpPacketBuilder.ack(true);
        tcpPacketBuilder.data(ackData);
        TcpPacket tcpPacket = this.buildCommonTcpPacket(tcpPacketBuilder);
        try {
            this.deviceOutbound.put(new OutboundAction(tcpPacket, OutboundActionType.OUTPUT_TO_DEVICE));
        } catch (InterruptedException e) {
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
            this.deviceOutbound.put(new OutboundAction(tcpPacket, OutboundActionType.OUTPUT_TO_DEVICE));
        } catch (InterruptedException e) {
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
            this.deviceOutbound.put(new OutboundAction(tcpPacket, OutboundActionType.OUTPUT_TO_DEVICE));
        } catch (InterruptedException e) {
            Log.e(TcpConnection.class.getName(),
                    "Fail to write psh ack tcp packet to device outbound queue because of error.", e);
        }
    }

    private void writeFinAckToDevice() {
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        tcpPacketBuilder.ack(true);
        tcpPacketBuilder.fin(true);
        TcpPacket tcpPacket = this.buildCommonTcpPacket(tcpPacketBuilder);
        try {
            this.deviceOutbound.put(new OutboundAction(tcpPacket, OutboundActionType.OUTPUT_TO_DEVICE));
        } catch (InterruptedException e) {
            Log.e(TcpConnection.class.getName(),
                    "Fail to write fin ack tcp packet to device outbound queue because of error.", e);
        }
    }

    private void writeFinToDevice() {
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        tcpPacketBuilder.fin(true);
        TcpPacket tcpPacket = this.buildCommonTcpPacket(tcpPacketBuilder);
        try {
            this.deviceOutbound.put(new OutboundAction(tcpPacket, OutboundActionType.OUTPUT_TO_DEVICE));
        } catch (InterruptedException e) {
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
