package com.ppaass.agent.service.handler.tcp;

import android.net.VpnService;
import android.util.Log;
import com.ppaass.agent.protocol.general.tcp.TcpHeader;
import com.ppaass.agent.protocol.general.tcp.TcpHeaderOption;
import com.ppaass.agent.protocol.general.tcp.TcpPacket;
import com.ppaass.agent.protocol.general.tcp.TcpPacketBuilder;
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
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class TcpConnection implements Runnable {
    private final AtomicInteger TIMESTAMP = new AtomicInteger();
    private static final Random RANDOM = new Random();
    private final String id;
    private final TcpConnectionRepositoryKey repositoryKey;
    private SocketChannel remoteSocketChannel;
    private final TcpIpPacketWriter tcpIpPacketWriter;
    private final BlockingQueue<TcpPacket> deviceInbound;
    private final Map<TcpConnectionRepositoryKey, TcpConnection> connectionRepository;
    private final AtomicReference<TcpConnectionStatus> status;
    private final AtomicLong currentSequenceNumber;
    private final AtomicLong currentAcknowledgementNumber;
    private final AtomicLong clientSyncSequenceNumber;
    private final Runnable remoteRelayToDeviceJob;
    private final CountDownLatch establishLatch;

    public TcpConnection(TcpConnectionRepositoryKey repositoryKey, TcpIpPacketWriter tcpIpPacketWriter,
                         Map<TcpConnectionRepositoryKey, TcpConnection> connectionRepository, VpnService vpnService)
            throws IOException {
        this.id = UUID.randomUUID().toString().replace("-", "");
        this.repositoryKey = repositoryKey;
        this.status = new AtomicReference<>(TcpConnectionStatus.LISTEN);
        this.currentAcknowledgementNumber = new AtomicLong(0);
        this.currentSequenceNumber = new AtomicLong(0);
        this.clientSyncSequenceNumber = new AtomicLong(0);
        this.deviceInbound = new LinkedBlockingQueue<>();
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
        this.remoteRelayToDeviceJob = new Runnable() {
            @Override
            public void run() {
                try {
                    TcpConnection.this.establishLatch.await();
                } catch (InterruptedException e) {
                    Log.e(TcpConnection.class.getName(),
                            "Connection can not relay remote data because of exception, connection:  " + this, e);
                    TcpConnection.this.status.set(TcpConnectionStatus.CLOSED);
                    TcpConnection.this.connectionRepository.remove(TcpConnection.this.repositoryKey);
                    TcpConnection.this.closeRemoteSocket();
                    return;
                }
                Log.d(TcpConnection.class.getName(),
                        "Connection in establish status, begin to relay remote data to device, current connection: " +
                                TcpConnection.this);
                while (TcpConnection.this.status.get() == TcpConnectionStatus.ESTABLISHED ||
                        TcpConnection.this.status.get() == TcpConnectionStatus.CLOSE_WAIT) {
                    try {
                        byte[] remoteData =
                                TcpConnection.this.readFromRemote();
                        if (remoteData == null) {
                            synchronized (this) {
                                this.wait(50);
                            }
                            Log.d(TcpConnection.class.getName(),
                                    "Nothing to read from remote, stop remote relay thread, current connection: " +
                                            TcpConnection.this);
                            continue;
                        }
                        TcpConnection.this.currentSequenceNumber.addAndGet(remoteData.length);
                        Log.d(TcpConnection.class.getName(),
                                "Receive remote data write ack to device, current connection: " + TcpConnection.this +
                                        ", remote data size: " + remoteData.length + ", remote data:\n\n" +
                                        new String(remoteData) + "\n\n");
                        TcpConnection.this.writeAck(remoteData);
                    } catch (Exception e) {
                        Log.e(TcpConnection.class.getName(),
                                "Fail to relay device data to remote because of exception, current connection: " +
                                        TcpConnection.this, e);
                    }
                }
            }
        };
    }

    private int generateRandomNumber() {
        int result = RANDOM.nextInt();
        if (result < 0) {
            return result * (-1);
        }
        return result;
    }

    private void writeSyncAck() throws Exception {
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        tcpPacketBuilder.ack(true);
        tcpPacketBuilder.syn(true);
        tcpPacketBuilder.destinationPort(this.repositoryKey.getSourcePort());
        tcpPacketBuilder.sourcePort(this.repositoryKey.getDestinationPort());
        tcpPacketBuilder.sequenceNumber(this.currentSequenceNumber.get());
        tcpPacketBuilder.acknowledgementNumber(this.currentAcknowledgementNumber.get());
        tcpPacketBuilder.window(65535);
        ByteBuffer mssByteBuffer = ByteBuffer.allocate(2);
        mssByteBuffer.putShort(Short.MAX_VALUE);
        tcpPacketBuilder.addOption(new TcpHeaderOption(TcpHeaderOption.Kind.MSS, mssByteBuffer.array()));
        TcpPacket syncAckTcpPacket = tcpPacketBuilder.build();
        this.tcpIpPacketWriter.write(this, syncAckTcpPacket);
    }

    private void writeAck(byte[] ackData) throws Exception {
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        tcpPacketBuilder.ack(true);
        tcpPacketBuilder.destinationPort(this.repositoryKey.getSourcePort());
        tcpPacketBuilder.sourcePort(this.repositoryKey.getDestinationPort());
        tcpPacketBuilder.sequenceNumber(this.currentSequenceNumber.get());
        tcpPacketBuilder.acknowledgementNumber(this.currentAcknowledgementNumber.get());
        tcpPacketBuilder.data(ackData);
        tcpPacketBuilder.window(65535);
        int timestamp = TIMESTAMP.getAndIncrement();
        ByteBuffer timestampByteBuffer = ByteBuffer.allocate(4);
        timestampByteBuffer.putInt(timestamp);
        tcpPacketBuilder.addOption(new TcpHeaderOption(TcpHeaderOption.Kind.TSPOT, timestampByteBuffer.array()));
        TcpPacket ackTcpPacket = tcpPacketBuilder.build();
        this.tcpIpPacketWriter.write(this, ackTcpPacket);
    }

    private void writeFinAck() throws Exception {
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        tcpPacketBuilder.ack(true);
        tcpPacketBuilder.fin(true);
        tcpPacketBuilder.destinationPort(this.repositoryKey.getSourcePort());
        tcpPacketBuilder.sourcePort(this.repositoryKey.getDestinationPort());
        tcpPacketBuilder.sequenceNumber(this.currentSequenceNumber.get());
        tcpPacketBuilder.acknowledgementNumber(this.currentAcknowledgementNumber.get());
        tcpPacketBuilder.window(65535);
        int timestamp = TIMESTAMP.getAndIncrement();
        ByteBuffer timestampByteBuffer = ByteBuffer.allocate(4);
        timestampByteBuffer.putInt(timestamp);
        tcpPacketBuilder.addOption(new TcpHeaderOption(TcpHeaderOption.Kind.TSPOT, timestampByteBuffer.array()));
        TcpPacket ackTcpPacket = tcpPacketBuilder.build();
        this.tcpIpPacketWriter.write(this, ackTcpPacket);
    }

    private void writeFin() throws Exception {
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        tcpPacketBuilder.fin(true);
        tcpPacketBuilder.destinationPort(this.repositoryKey.getSourcePort());
        tcpPacketBuilder.sourcePort(this.repositoryKey.getDestinationPort());
        tcpPacketBuilder.sequenceNumber(this.currentSequenceNumber.get());
        tcpPacketBuilder.acknowledgementNumber(this.currentAcknowledgementNumber.get());
        tcpPacketBuilder.window(65535);
        int timestamp = TIMESTAMP.getAndIncrement();
        ByteBuffer timestampByteBuffer = ByteBuffer.allocate(4);
        timestampByteBuffer.putInt(timestamp);
        tcpPacketBuilder.addOption(new TcpHeaderOption(TcpHeaderOption.Kind.TSPOT, timestampByteBuffer.array()));
        TcpPacket ackTcpPacket = tcpPacketBuilder.build();
        this.tcpIpPacketWriter.write(this, ackTcpPacket);
    }

    private boolean connectRemote() throws Exception {
        return this.remoteSocketChannel.connect(
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
        while (this.status.get() != TcpConnectionStatus.CLOSED) {
            try {
                TcpPacket tcpPacket = this.deviceInbound.take();
                TcpHeader tcpHeader = tcpPacket.getHeader();
                if (tcpHeader.isSyn()) {
                    if (this.status.get() == TcpConnectionStatus.SYNC_RCVD) {
                        continue;
                    }
                    //Initialize ack number and seq number
                    this.currentAcknowledgementNumber.set(tcpHeader.getSequenceNumber() + 1);
                    this.currentSequenceNumber.set(this.generateRandomNumber());
                    this.clientSyncSequenceNumber.set(tcpHeader.getSequenceNumber());
                    this.status.set(TcpConnectionStatus.SYNC_RCVD);
                    Log.d(TcpConnection.class.getName(),
                            "Receive sync and do sync ack, current connection: " + this + ", incoming tcp packet: " +
                                    tcpPacket);
                    this.writeSyncAck();
                    this.currentSequenceNumber.incrementAndGet();
                    continue;
                }
                if (tcpHeader.isAck() && !tcpHeader.isFin()) {
                    if (this.status.get() == TcpConnectionStatus.SYNC_RCVD) {
                        if (this.currentSequenceNumber.get() != tcpHeader.getAcknowledgementNumber()) {
                            this.status.set(TcpConnectionStatus.CLOSED);
                            this.connectionRepository.remove(this.repositoryKey);
                            this.closeRemoteSocket();
                            Log.e(TcpConnection.class.getName(),
                                    "Connection current seq number do not match incoming ack number:  " + this +
                                            ", incoming tcp packet: " + tcpPacket);
                            return;
                        }
                        this.connectRemote();
                        this.status.set(TcpConnectionStatus.ESTABLISHED);
                        Executors.newSingleThreadExecutor().execute(this.remoteRelayToDeviceJob);
                        this.establishLatch.countDown();
                        Log.d(TcpConnection.class.getName(),
                                "Receive ack and remote connection established, current connection: " + this +
                                        ", incoming tcp packet: " + tcpPacket);
                        continue;
                    }
                    if (this.status.get() == TcpConnectionStatus.ESTABLISHED) {
                        int dataLength = tcpPacket.getData().length;
                        this.writeToRemote(tcpPacket.getData());
                        this.currentAcknowledgementNumber.addAndGet(dataLength);
                        Log.d(TcpConnection.class.getName(),
                                "Receive ack and write device data to remote, current connection:  " + this +
                                        ", incoming tcp packet: " + tcpPacket + " , device data size: " +
                                        tcpPacket.getData().length + ", write data: \n\n" +
                                        new String(tcpPacket.getData()) + "\n\n");
                        this.writeAck(null);
                        continue;
                    }
                    if (this.status.get() == TcpConnectionStatus.LAST_ACK) {
                        this.status.set(TcpConnectionStatus.CLOSED);
                        this.connectionRepository.remove(this.repositoryKey);
                        this.closeRemoteSocket();
                        Log.d(TcpConnection.class.getName(),
                                "Receive last ack, close connection: " + this + ", incoming tcp packet: " + tcpPacket);
                        return;
                    }
                    if (this.status.get() == TcpConnectionStatus.FIN_WAIT1) {
                        this.writeToRemote(tcpPacket.getData());
                        this.status.set(TcpConnectionStatus.FIN_WAIT2);
                        Log.d(TcpConnection.class.getName(),
                                "Receive ack after FIN_WAIT1, forward data to remove directly and make connection to FIN_WAIT2, current connection: " +
                                        this + ", incoming tcp packet: " + tcpPacket);
                        continue;
                    }
                    Log.e(TcpConnection.class.getName(),
                            "Incorrect connection status going to close connection, current connection: " + this +
                                    ", incoming tcp packet: " + tcpPacket);
                    this.status.set(TcpConnectionStatus.CLOSED);
                    this.connectionRepository.remove(this.repositoryKey);
                    this.closeRemoteSocket();
                    return;
                }
                if (tcpHeader.isFin()) {
                    if (!tcpHeader.isAck()) {
                        this.status.set(TcpConnectionStatus.CLOSE_WAIT);
                        synchronized (this.remoteRelayToDeviceJob) {
                            this.remoteRelayToDeviceJob.notify();
                        }
                        this.connectionRepository.remove(this.repositoryKey);
                        this.currentAcknowledgementNumber.incrementAndGet();
                        this.writeAck(null);
                        this.status.set(TcpConnectionStatus.LAST_ACK);
                        this.writeFinAck();
                        Log.d(TcpConnection.class.getName(),
                                "Receive fin, begin to close connection: " + this + ", incoming tcp packet: " +
                                        tcpPacket);
                        continue;
                    }
                    //Fin + Ack
                    if (this.status.get() == TcpConnectionStatus.FIN_WAIT2) {
                        this.currentSequenceNumber.incrementAndGet();
                        this.currentAcknowledgementNumber.incrementAndGet();
                        this.status.set(TcpConnectionStatus.TIME_WAIT);
                        this.writeAck(null);
                        Log.d(TcpConnection.class.getName(),
                                "Receive fin ack after FIN_WAIT2, make connection to TIME_WAIT, current connection: " +
                                        this + ", incoming tcp packet: " + tcpPacket);
                        this.status.set(TcpConnectionStatus.CLOSED);
                        this.connectionRepository.remove(this.repositoryKey);
                        this.closeRemoteSocket();
                        Log.d(TcpConnection.class.getName(),
                                "After 2MSL make connection to CLOSED, current connection: " +
                                        this);
                        return;
                    }
                    Log.e(TcpConnection.class.getName(),
                            "Incorrect connection status going to close connection(fin process), close directly, current connection: " +
                                    this +
                                    ", incoming tcp packet: " + tcpPacket);
                    this.status.set(TcpConnectionStatus.CLOSED);
                    this.connectionRepository.remove(this.repositoryKey);
                    this.closeRemoteSocket();
                    return;
                }
            } catch (Exception e) {
                this.status.set(TcpConnectionStatus.CLOSED);
                this.connectionRepository.remove(this.repositoryKey);
                this.closeRemoteSocket();
                Log.e(TcpConnection.class.getName(), "Exception happen when handle connection.", e);
                return;
            }
        }
    }

    private void writeToRemote(byte[] data) throws Exception {
        this.remoteSocketChannel.write(ByteBuffer.wrap(data));
    }

    private byte[] readFromRemote() throws Exception {
        ByteBuffer readBuffer = ByteBuffer.allocateDirect(65536);
        int readLength = this.remoteSocketChannel.read(readBuffer);
        if (readLength <= 0) {
            return null;
        }
        readBuffer.flip();
        byte[] result = new byte[readBuffer.remaining()];
        readBuffer.get(result);
        return result;
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

    @Override
    public String toString() {
        return "TcpConnection{" + "id='" + id + '\'' + ", repositoryKey=" + repositoryKey + ", status=" + status +
                ", currentSequenceNumber=" + currentSequenceNumber + ", currentAcknowledgementNumber=" +
                currentAcknowledgementNumber + ", clientSyncSequenceNumber=" + clientSyncSequenceNumber + '}';
    }
}
