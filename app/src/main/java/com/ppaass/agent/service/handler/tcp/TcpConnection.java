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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
    private final BlockingQueue<TcpPacket> deviceInbound;
    private final BlockingQueue<TcpPacket> deviceOutbound;
    private final Map<TcpConnectionRepositoryKey, TcpConnection> connectionRepository;
    private final AtomicReference<TcpConnectionStatus> status;
    private final AtomicLong currentSequenceNumber;
    private final AtomicLong currentAcknowledgementNumber;
    private final AtomicLong deviceInitialSequenceNumber;
    private final AtomicLong vpnInitialSequenceNumber;
    private final CountDownLatch establishLatch;
    private final AtomicBoolean running;

    public TcpConnection(TcpConnectionRepositoryKey repositoryKey, TcpIpPacketWriter tcpIpPacketWriter,
                         Map<TcpConnectionRepositoryKey, TcpConnection> connectionRepository, VpnService vpnService)
            throws IOException {
        this.id = UUID.randomUUID().toString().replace("-", "");
        this.repositoryKey = repositoryKey;
        this.status = new AtomicReference<>(TcpConnectionStatus.LISTEN);
        this.currentAcknowledgementNumber = new AtomicLong(0);
        this.currentSequenceNumber = new AtomicLong(0);
        this.deviceInitialSequenceNumber = new AtomicLong(0);
        this.vpnInitialSequenceNumber = new AtomicLong(0);
        this.deviceInbound = new PriorityBlockingQueue<>(1024,
                Comparator.comparingLong(p -> p.getHeader().getSequenceNumber()));
        this.deviceOutbound = new PriorityBlockingQueue<>(1024,
                Comparator.comparingLong(p -> p.getHeader().getSequenceNumber()));
        this.tcpIpPacketWriter = tcpIpPacketWriter;
        this.connectionRepository = connectionRepository;
        this.establishLatch = new CountDownLatch(1);
        this.remoteSocketChannel = SocketChannel.open();
        this.remoteSocketChannel.configureBlocking(true);
        if (!vpnService.protect(this.remoteSocketChannel.socket())) {
            Log.e(TcpConnection.class.getName(), "Fail to protect vpn tcp socket, current connection: " + this
                    + ", remote socket: " + this.remoteSocketChannel);
            throw new UnsupportedOperationException("Fail to protect vpn tcp socket");
        }
        this.running = new AtomicBoolean(false);
    }

    private int generateVpnInitialSequenceNumber() {
        return INITIAL_SEQ.getAndIncrement();
    }

    public void start() {
        this.running.set(true);
    }

    private void closeConnection() {
        while (!this.deviceOutbound.isEmpty()) {
            Thread.yield();
        }
        this.running.set(false);
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
        this.deviceInbound.put(tcpPacket);
    }

    public TcpConnectionRepositoryKey getRepositoryKey() {
        return repositoryKey;
    }

    public void run() {
        Executors.newSingleThreadExecutor().execute(() -> {
            while (TcpConnection.this.running.get()) {
                try {
                    TcpPacket outboundPacket = TcpConnection.this.deviceOutbound.take();
                    TcpConnection.this.tcpIpPacketWriter.write(TcpConnection.this, outboundPacket);
                    Log.d(TcpConnection.class.getName(),
                            "<<<<<<<< Write tcp packet to device, current connection: " + TcpConnection.this +
                                    ", tcp packet: " +
                                    outboundPacket);
                } catch (InterruptedException | IOException e) {
                    Log.e(TcpConnection.class.getName(),
                            "<<<<<<<< Fail to get tcp packet from outbound queue because of exception, current connection: " +
                                    TcpConnection.this, e);
                }
            }
        });
        while (this.running.get()) {
            try {
                TcpPacket tcpPacket = this.deviceInbound.take();
                TcpHeader tcpHeader = tcpPacket.getHeader();
                if (tcpHeader.isSyn()) {
                    if (this.status.get() == TcpConnectionStatus.SYNC_RCVD) {
                        this.writeRstToDevice();
                        this.closeConnection();
                        Log.e(TcpConnection.class.getName(),
                                ">>>>>>>> Receive duplicate sync tcp packet, current connection: " + this +
                                        ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
                        continue;
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
                                    ", incoming tcp packet: " +
                                    this.printTcpPacket(tcpPacket));
                    continue;
                }
                if (tcpHeader.isAck()) {
                    if (this.status.get() == TcpConnectionStatus.SYNC_RCVD) {
                        if (this.currentSequenceNumber.get() + 1 != tcpHeader.getAcknowledgementNumber()) {
                            this.writeRstToDevice();
                            this.closeConnection();
                            Log.e(TcpConnection.class.getName(),
                                    ">>>>>>>> Connection current seq number do not match incoming ack number:  " +
                                            this +
                                            ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
                            return;
                        }
                        this.connectRemote();
                        this.currentSequenceNumber.getAndIncrement();
                        this.status.set(TcpConnectionStatus.ESTABLISHED);
                        //Start remote relay task
                        Executors.newSingleThreadExecutor().execute(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    TcpConnection.this.establishLatch.await();
                                } catch (InterruptedException e) {
                                    Log.e(TcpConnection.class.getName(),
                                            "<<<<<<<< Connection can not relay remote data because of exception, connection:  " +
                                                    this, e);
                                    try {
                                        TcpConnection.this.writeRstToDevice();
                                    } catch (Exception ex) {
                                        Log.e(TcpConnection.class.getName(),
                                                "<<<<<<<< Fail to send rst to device because of exception, connection:  " +
                                                        TcpConnection.this, e);
                                    }
                                    TcpConnection.this.closeConnection();
                                    return;
                                }
                                Log.d(TcpConnection.class.getName(),
                                        "<<<<<<<< Connection in establish status, begin to relay remote data to device, current connection: " +
                                                TcpConnection.this);
                                while (TcpConnection.this.running.get()) {
                                    try {
                                        ByteBuffer remoteDataBuf =
                                                TcpConnection.this.readFromRemote();
                                        if (!remoteDataBuf.hasRemaining()) {
                                            return;
                                        }
                                        //Relay remote data to device and use mss as the transfer unit
                                        while (remoteDataBuf.hasRemaining()) {
                                            int mssDataLength =
                                                    Math.min(IVpnConst.TCP_MSS, remoteDataBuf.remaining());
                                            byte[] mssData = new byte[mssDataLength];
                                            remoteDataBuf.get(mssData);
                                            TcpConnection.this.writeAckToDevice(mssData);
                                            // Data should write to device first then increase the sequence number
                                            TcpConnection.this.currentSequenceNumber.addAndGet(mssData.length);
                                            Log.d(TcpConnection.class.getName(),
                                                    "<<<<<<<< Receive remote data write ack to device, current connection: " +
                                                            TcpConnection.this +
                                                            ", remote data size: " + mssData.length +
                                                            ", remote data:\n\n" +
                                                            new String(mssData) + "\n\n");
                                        }
                                    } catch (Exception e) {
                                        Log.e(TcpConnection.class.getName(),
                                                "<<<<<<<< Fail to relay device data to remote because of exception, current connection: " +
                                                        TcpConnection.this, e);
                                    }
                                }
                            }
                        });
                        //Make remote relay start to work
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
                                            "), current connection:  " + this +
                                            ", incoming tcp packet: " + this.printTcpPacket(tcpPacket) +
                                            " , device data size: " +
                                            tcpPacket.getData().length);
                            continue;
                        }
                        this.writeToRemote(tcpPacket.getData());
                        int dataLength = tcpPacket.getData().length;
                        this.currentAcknowledgementNumber.addAndGet(dataLength);
                        this.writeAckToDevice(null);
                        Log.d(TcpConnection.class.getName(),
                                ">>>>>>>> Receive ACK - (PSH=" + tcpHeader.isPsh() +
                                        ") and write device data to remote, current connection:  " + this +
                                        ", incoming tcp packet: " + this.printTcpPacket(tcpPacket) +
                                        " , device data size: " +
                                        tcpPacket.getData().length + ", write data: \n\n" +
                                        new String(tcpPacket.getData()) + "\n\n");
                        continue;
                    }
                    if (this.status.get() == TcpConnectionStatus.LAST_ACK) {
                        this.writeRstToDevice();
                        this.closeConnection();
                        Log.d(TcpConnection.class.getName(),
                                ">>>>>>>> Receive last ack, close connection: " + this + ", incoming tcp packet: " +
                                        this.printTcpPacket(tcpPacket));
                        return;
                    }
                    if (this.status.get() == TcpConnectionStatus.FIN_WAIT1) {
                        this.writeToRemote(tcpPacket.getData());
                        this.status.set(TcpConnectionStatus.FIN_WAIT2);
                        Log.d(TcpConnection.class.getName(),
                                ">>>>>>>> Receive ack after FIN_WAIT1, forward data to remove directly and make connection to FIN_WAIT2, current connection: " +
                                        this + ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
                        continue;
                    }
                    this.writeRstToDevice();
                    this.closeConnection();
                    Log.e(TcpConnection.class.getName(),
                            ">>>>>>>> Incorrect connection status going to close connection, current connection: " +
                                    this +
                                    ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
                    return;
                }
                if (tcpHeader.isFin()) {
                    if (!tcpHeader.isAck() || this.status.get() == TcpConnectionStatus.ESTABLISHED) {
                        this.status.set(TcpConnectionStatus.CLOSE_WAIT);
                        this.connectionRepository.remove(this.repositoryKey);
                        this.currentAcknowledgementNumber.incrementAndGet();
                        this.writeAckToDevice(null);
                        this.status.set(TcpConnectionStatus.LAST_ACK);
                        this.writeFinAckToDevice();
                        Log.d(TcpConnection.class.getName(),
                                ">>>>>>>> Receive fin, begin to close connection: " + this + ", incoming tcp packet: " +
                                        this.printTcpPacket(tcpPacket));
                        continue;
                    }
                    //Fin + Ack
                    if (this.status.get() == TcpConnectionStatus.FIN_WAIT2) {
                        this.currentSequenceNumber.incrementAndGet();
                        this.currentAcknowledgementNumber.incrementAndGet();
                        this.status.set(TcpConnectionStatus.TIME_WAIT);
                        this.writeAckToDevice(null);
                        Log.d(TcpConnection.class.getName(),
                                ">>>>>>>> Receive fin ack after FIN_WAIT2, make connection to TIME_WAIT, current connection: " +
                                        this + ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
                        Log.d(TcpConnection.class.getName(),
                                ">>>>>>>> After 2MSL make connection to CLOSED, current connection: " +
                                        this);
                        this.closeConnection();
                        return;
                    }
                    this.writeRstToDevice();
                    this.closeConnection();
                    Log.e(TcpConnection.class.getName(),
                            ">>>>>>>> Incorrect connection status going to close connection(fin process), close directly, current connection: " +
                                    this +
                                    ", incoming tcp packet: " + this.printTcpPacket(tcpPacket));
                    return;
                }
            } catch (Exception e) {
                try {
                    this.writeRstToDevice();
                    this.closeConnection();
                } catch (Exception e1) {
                    Log.e(TcpConnection.class.getName(),
                            ">>>>>>>> Exception happen when handle connection(fail to write RST to device), current connection: " +
                                    this, e1);
                    return;
                }
                Log.e(TcpConnection.class.getName(),
                        ">>>>>>>> Exception happen when handle connection, current connection: " + this, e);
                return;
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

    private void writeSyncAckToDevice() throws Exception {
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        tcpPacketBuilder.ack(true);
        tcpPacketBuilder.syn(true);
        ByteBuffer mssByteBuffer = ByteBuffer.allocate(2);
        mssByteBuffer.putShort(IVpnConst.TCP_MSS);
        tcpPacketBuilder.addOption(new TcpHeaderOption(TcpHeaderOption.Kind.MSS, mssByteBuffer.array()));
        TcpPacket tcpPacket = this.buildCommonTcpPacket(tcpPacketBuilder);
//        this.tcpIpPacketWriter.write(this, syncAckTcpPacket);
        this.deviceOutbound.put(tcpPacket);
    }

    private void writeAckToDevice(byte[] ackData) throws Exception {
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        tcpPacketBuilder.ack(true);
        tcpPacketBuilder.data(ackData);
        TcpPacket tcpPacket = this.buildCommonTcpPacket(tcpPacketBuilder);
//        this.tcpIpPacketWriter.write(this, ackTcpPacket);
        this.deviceOutbound.put(tcpPacket);
    }

    private void writeRstToDevice() throws Exception {
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        tcpPacketBuilder.ack(true);
        tcpPacketBuilder.rst(true);
        TcpPacket tcpPacket = this.buildCommonTcpPacket(tcpPacketBuilder);
//        this.tcpIpPacketWriter.write(this, ackTcpPacket);
        this.deviceOutbound.put(tcpPacket);
    }

    private void writePshAckToDevice(byte[] ackData) throws Exception {
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        tcpPacketBuilder.ack(true);
        tcpPacketBuilder.psh(true);
        tcpPacketBuilder.data(ackData);
        TcpPacket tcpPacket = this.buildCommonTcpPacket(tcpPacketBuilder);
//        this.tcpIpPacketWriter.write(this, ackTcpPacket);
        this.deviceOutbound.put(tcpPacket);
    }

    private void writeFinAckToDevice() throws Exception {
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        tcpPacketBuilder.ack(true);
        tcpPacketBuilder.fin(true);
        TcpPacket tcpPacket = this.buildCommonTcpPacket(tcpPacketBuilder);
//        this.tcpIpPacketWriter.write(this, ackTcpPacket);
        this.deviceOutbound.put(tcpPacket);
    }

    private void writeFinToDevice() throws Exception {
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        tcpPacketBuilder.fin(true);
        TcpPacket tcpPacket = this.buildCommonTcpPacket(tcpPacketBuilder);
//        this.tcpIpPacketWriter.write(this, ackTcpPacket);
        this.deviceOutbound.put(tcpPacket);
    }

    public String printTcpPacket(TcpPacket tcpPacket) {
        return tcpPacket + ", (Relative Device Sequence Number)=" + (tcpPacket.getHeader().getSequenceNumber() -
                this.deviceInitialSequenceNumber.get()) + ", (Relative Device Acknowledgement Number)=" +
                (tcpPacket.getHeader().getAcknowledgementNumber() - this.vpnInitialSequenceNumber.get());
    }

    @Override
    public String toString() {
        return "TcpConnection{" +
                "TIMESTAMP=" + TIMESTAMP +
                ", id='" + id + '\'' +
                ", repositoryKey=" + repositoryKey +
                ", status=" + status +
                ", currentSequenceNumber=" + currentSequenceNumber +
                ", (Current Relative VPN Sequence Number)=" + this.countRelativeVpnSequenceNumber() +
                ", currentAcknowledgementNumber=" + currentAcknowledgementNumber +
                ", (Current Relative VPN Acknowledgement Number)=" + this.countRelativeDeviceSequenceNumber() +
                ", deviceInitialSequenceNumber=" + deviceInitialSequenceNumber +
                ", vpnInitialSequenceNumber=" + vpnInitialSequenceNumber +
                '}';
    }
}
