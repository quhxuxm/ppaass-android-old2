package com.ppaass.agent.service.handler.tcp;

import android.util.Log;
import com.ppaass.agent.protocol.general.tcp.TcpHeader;
import com.ppaass.agent.protocol.general.tcp.TcpPacket;
import com.ppaass.agent.protocol.general.tcp.TcpPacketBuilder;
import com.ppaass.agent.service.handler.TcpIpPacketWriter;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class TcpConnection implements Runnable {
    private static final Random RANDOM = new Random();
    private final String id;
    private final TcpConnectionRepositoryKey repositoryKey;
    private final Socket remoteSocket;
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
                         Map<TcpConnectionRepositoryKey, TcpConnection> connectionRepository) {
        this.id = UUID.randomUUID().toString().replace("-", "");
        this.repositoryKey = repositoryKey;
        this.status = new AtomicReference<>(TcpConnectionStatus.LISTEN);
        this.currentAcknowledgementNumber = new AtomicLong(0);
        this.currentSequenceNumber = new AtomicLong(0);
        this.clientSyncSequenceNumber = new AtomicLong(0);
        this.remoteSocket = new Socket();
        this.deviceInbound = new LinkedBlockingQueue<>();
        this.tcpIpPacketWriter = tcpIpPacketWriter;
        this.connectionRepository = connectionRepository;
        this.establishLatch = new CountDownLatch(1);
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
                    return;
                }
                while (
                        TcpConnection.this.status.get() == TcpConnectionStatus.ESTABLISHED) {
                    try {
                        byte[] remoteDataBuf = new byte[65536];
                        byte[] remoteData = TcpConnection.this.readFromRemote(remoteDataBuf);
                        if (remoteData == null) {
                            return;
                        }
                        TcpConnection.this.currentSequenceNumber.addAndGet(remoteData.length);
                        Log.d(TcpConnection.class.getName(),
                                "Receive remote data write ack to device, current connection: " +
                                        TcpConnection.this + ", remote data size: " + remoteData.length);
                        TcpConnection.this.writeAck(remoteData);
                    } catch (Exception e) {
                        Log.e(TcpConnection.class.getName(),
                                "Fail to relay device data to remote because of exception.", e);
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
        TcpPacket syncAckTcpPacket = tcpPacketBuilder.build();
        this.tcpIpPacketWriter.write(this.repositoryKey, syncAckTcpPacket);
    }

    private void writeAck(byte[] ackData) throws Exception {
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        tcpPacketBuilder.ack(true);
        tcpPacketBuilder.destinationPort(this.repositoryKey.getSourcePort());
        tcpPacketBuilder.sourcePort(this.repositoryKey.getDestinationPort());
        tcpPacketBuilder.sequenceNumber(this.currentSequenceNumber.get());
        tcpPacketBuilder.acknowledgementNumber(this.currentAcknowledgementNumber.get());
        tcpPacketBuilder.data(ackData);
        TcpPacket ackTcpPacket = tcpPacketBuilder.build();
        this.tcpIpPacketWriter.write(this.repositoryKey, ackTcpPacket);
    }

    private void writeFinAck() throws Exception {
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        tcpPacketBuilder.ack(true);
        tcpPacketBuilder.fin(true);
        tcpPacketBuilder.destinationPort(this.repositoryKey.getSourcePort());
        tcpPacketBuilder.sourcePort(this.repositoryKey.getDestinationPort());
        tcpPacketBuilder.sequenceNumber(this.currentSequenceNumber.get());
        tcpPacketBuilder.acknowledgementNumber(this.currentAcknowledgementNumber.get());
        TcpPacket ackTcpPacket = tcpPacketBuilder.build();
        this.tcpIpPacketWriter.write(this.repositoryKey, ackTcpPacket);
    }

    public Socket getRemoteSocket() {
        return remoteSocket;
    }

    private void connectRemote() throws Exception {
        this.remoteSocket.connect(
                new InetSocketAddress(InetAddress.getByAddress(this.repositoryKey.getDestinationAddress()),
                        this.repositoryKey.getDestinationPort()));
    }

    public void onDeviceInbound(TcpPacket tcpPacket) throws Exception {
        this.deviceInbound.put(tcpPacket);
    }

    public void run() {
        while (this.status.get() != TcpConnectionStatus.CLOSED) {
            try {
                TcpPacket tcpPacket = this.deviceInbound.poll(20, TimeUnit.SECONDS);
                if (tcpPacket == null) {
                    this.status.set(TcpConnectionStatus.CLOSED);
                    this.connectionRepository.remove(this.repositoryKey);
                    Log.d(TcpConnection.class.getName(), "Read tcp packet timeout, current connection: " + this);
                    return;
                }
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
                    this.writeSyncAck();
                    this.currentSequenceNumber.incrementAndGet();
                    Log.d(TcpConnection.class.getName(),
                            "Receive sync and do sync ack, current connection: " + this + ", incoming tcp packet: " + tcpPacket);
                    continue;
                }
                if (tcpHeader.isAck() && !tcpHeader.isFin()) {
                    if (this.status.get() == TcpConnectionStatus.SYNC_RCVD) {
                        if (this.currentSequenceNumber.get() != tcpHeader.getAcknowledgementNumber()) {
                            this.status.set(TcpConnectionStatus.CLOSED);
                            this.connectionRepository.remove(this.repositoryKey);
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
                        this.writeAck(null);
                        Log.d(TcpConnection.class.getName(),
                                "Receive ack and write device data to remote, current connection:  " + this +
                                        ", incoming tcp packet: " + tcpPacket + " , device data size: " +
                                        tcpPacket.getData().length);
                        continue;
                    }
                    if (this.status.get() == TcpConnectionStatus.LAST_ACK) {
                        this.status.set(TcpConnectionStatus.CLOSED);
                        this.connectionRepository.remove(this.repositoryKey);
                        Log.d(TcpConnection.class.getName(),
                                "Receive last ack, close connection: " + this + ", incoming tcp packet: " + tcpPacket);
                        return;
                    }
                    Log.e(TcpConnection.class.getName(),
                            "Incorrect connection status going to close connection, current connection: " + this +
                                    ", incoming tcp packet: " + tcpPacket);
                    this.status.set(TcpConnectionStatus.CLOSED);
                    this.connectionRepository.remove(this.repositoryKey);
                    return;
                }
                if (tcpHeader.isFin()) {
                    this.status.set(TcpConnectionStatus.CLOSE_WAIT);
                    this.connectionRepository.remove(this.repositoryKey);
                    this.currentAcknowledgementNumber.incrementAndGet();
                    this.writeAck(null);
                    this.status.set(TcpConnectionStatus.LAST_ACK);
                    this.writeFinAck();
                    Log.d(TcpConnection.class.getName(),
                            "Receive fin, begin to close connection: " + this + ", incoming tcp packet: " + tcpPacket);
                    continue;
                }
            } catch (Exception e) {
                this.status.set(TcpConnectionStatus.CLOSED);
                this.connectionRepository.remove(this.repositoryKey);
                Log.e(TcpConnection.class.getName(), "Exception happen when handle connection.", e);
                return;
            }
        }
    }

    private void writeToRemote(byte[] data) throws Exception {
        OutputStream remoteStream = this.remoteSocket.getOutputStream();
        remoteStream.write(data);
        remoteStream.flush();
    }

    private byte[] readFromRemote(byte[] data) throws Exception {
        InputStream remoteStream = this.remoteSocket.getInputStream();
        int remoteDataLength = remoteStream.read(data);
        if (remoteDataLength < 0) {
            return null;
        }
        return Arrays.copyOf(data, remoteDataLength);
    }

    public void close() throws Exception {
        this.remoteSocket.close();
    }

    @Override
    public String toString() {
        return "TcpConnection{" + "id='" + id + '\'' + ", repositoryKey=" + repositoryKey + ", status=" + status +
                ", currentSequenceNumber=" + currentSequenceNumber + ", currentAcknowledgementNumber=" +
                currentAcknowledgementNumber + ", clientSyncSequenceNumber=" + clientSyncSequenceNumber + '}';
    }
}
