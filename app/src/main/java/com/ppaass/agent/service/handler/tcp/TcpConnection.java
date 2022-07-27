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
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class TcpConnection implements Runnable {
    private static final Random RANDOM = new Random();
    private final String id;
    private final TcpConnectionRepositoryKey repositoryKey;
    private final Socket remoteSocket;
    private final TcpIpPacketWriter tcpIpPacketWriter;
    private final BlockingQueue<TcpPacket> deviceInbound;
    private final BlockingQueue<byte[]> remoteInbound;
    private final Map<TcpConnectionRepositoryKey, TcpConnection> connectionRepository;
    private TcpConnectionStatus status;
    private long currentSequenceNumber;
    private long currentAcknowledgementNumber;
    private long clientSyncSequenceNumber;
    private final Runnable remoteRelayToDeviceJob;
    private final AtomicBoolean remoteRelayToDeviceJobRunning;

    public TcpConnection(TcpConnectionRepositoryKey repositoryKey, TcpIpPacketWriter tcpIpPacketWriter,
                         Map<TcpConnectionRepositoryKey, TcpConnection> connectionRepository) {
        this.id = UUID.randomUUID().toString().replace("-", "");
        this.repositoryKey = repositoryKey;
        this.status = TcpConnectionStatus.LISTEN;
        this.currentAcknowledgementNumber = 0;
        this.currentSequenceNumber = 0;
        this.clientSyncSequenceNumber = 0;
        this.remoteSocket = new Socket();
        this.deviceInbound = new LinkedBlockingQueue<>();
        this.remoteInbound = new LinkedBlockingQueue<>();
        this.tcpIpPacketWriter = tcpIpPacketWriter;
        this.connectionRepository = connectionRepository;
        this.remoteRelayToDeviceJobRunning = new AtomicBoolean(false);
        this.remoteRelayToDeviceJob = new Runnable() {
            @Override
            public void run() {
                while (TcpConnection.this.status != TcpConnectionStatus.CLOSED &&
                        TcpConnection.this.remoteRelayToDeviceJobRunning.get()) {
                    try {
                        Log.d(TcpConnection.class.getName(),
                                "Receive remote data write ack to device [begin], current connection: " + this);
                        byte[] remoteInboundData = TcpConnection.this.remoteInbound.take();
                        TcpConnection.this.currentSequenceNumber += remoteInboundData.length;
                        TcpConnection.this.writeAck(remoteInboundData);
                        Log.d(TcpConnection.class.getName(),
                                "Receive remote data write ack to device [end], current connection: " + this);
                    } catch (Exception e) {
                        Log.e(TcpConnection.class.getName(),
                                "Fail to relay device data to remote because of exception.",
                                e);
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
        tcpPacketBuilder.sequenceNumber(this.currentSequenceNumber);
        tcpPacketBuilder.acknowledgementNumber(this.currentAcknowledgementNumber);
        TcpPacket syncAckTcpPacket = tcpPacketBuilder.build();
        this.tcpIpPacketWriter.write(this.repositoryKey, syncAckTcpPacket);
    }

    private void writeAck(byte[] ackData) throws Exception {
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        tcpPacketBuilder.ack(true);
        tcpPacketBuilder.destinationPort(this.repositoryKey.getSourcePort());
        tcpPacketBuilder.sourcePort(this.repositoryKey.getDestinationPort());
        tcpPacketBuilder.sequenceNumber(this.currentSequenceNumber);
        tcpPacketBuilder.acknowledgementNumber(this.currentAcknowledgementNumber);
        tcpPacketBuilder.data(ackData);
        TcpPacket ackTcpPacket = tcpPacketBuilder.build();
        this.tcpIpPacketWriter.write(this.repositoryKey, ackTcpPacket);
    }

    public TcpConnectionStatus getStatus() {
        return status;
    }

    public TcpConnectionRepositoryKey getRepositoryKey() {
        return repositoryKey;
    }

    public Socket getRemoteSocket() {
        return remoteSocket;
    }

    private void connectRemote() throws Exception {
        this.remoteSocket.connect(new InetSocketAddress(
                InetAddress.getByAddress(this.repositoryKey.getDestinationAddress()),
                this.repositoryKey.getDestinationPort()));
    }

    public void onDeviceInbound(TcpPacket tcpPacket) throws Exception {
        this.deviceInbound.put(tcpPacket);
    }

    public void run() {
        while (this.status != TcpConnectionStatus.CLOSED) {
            try {
                TcpPacket tcpPacket = this.deviceInbound.take();
                TcpHeader tcpHeader = tcpPacket.getHeader();
                if (tcpHeader.isSyn()) {
                    Log.d(TcpConnection.class.getName(),
                            "Receive sync [begin], current connection: " + this + ", client tcp packet: " + tcpPacket);
                    //Initialize ack number and seq number
                    this.currentAcknowledgementNumber = tcpHeader.getSequenceNumber() + 1;
                    this.currentSequenceNumber = this.generateRandomNumber();
                    this.clientSyncSequenceNumber = tcpHeader.getSequenceNumber();
                    this.status = TcpConnectionStatus.SYNC_RCVD;
                    this.writeSyncAck();
                    Log.d(TcpConnection.class.getName(),
                            "Receive sync [end], current connection: " + this);
                    continue;
                }
                if (tcpHeader.isAck()) {
                    Log.d(TcpConnection.class.getName(),
                            "Receive ack [begin], current connection: " + this + ", client tcp packet: " + tcpPacket);
                    if (this.status == TcpConnectionStatus.SYNC_RCVD) {
                        if (this.currentSequenceNumber + 1 != tcpHeader.getAcknowledgementNumber()) {
                            Log.e(TcpConnection.class.getName(),
                                    "Connection current seq number do not match incoming ack number:  " + this);
                            this.status = TcpConnectionStatus.CLOSED;
                            this.connectionRepository.remove(this.repositoryKey);
                            Log.d(TcpConnection.class.getName(),
                                    "Receive ack [end.1], current connection: " + this);
                            return;
                        }
                        if (this.clientSyncSequenceNumber + 1 != tcpHeader.getSequenceNumber()) {
                            Log.e(TcpConnection.class.getName(),
                                    "Connection current seq number do not match syn seq +1:  " + this);
                            this.status = TcpConnectionStatus.CLOSED;
                            this.connectionRepository.remove(this.repositoryKey);
                            Log.d(TcpConnection.class.getName(),
                                    "Receive ack [end.2], current connection: " + this);
                            return;
                        }
                        //TODO connect to remote target.
                        Log.d(TcpConnection.class.getName(), "Begin connect to remote: " + this);
                        this.connectRemote();
                        this.status = TcpConnectionStatus.ESTABLISHED;
                        this.currentSequenceNumber = tcpHeader.getAcknowledgementNumber();
                        Log.d(TcpConnection.class.getName(),
                                "Receive ack [end.3], remote connection established, current connection: " + this);
                        continue;
                    }
                    if (this.status == TcpConnectionStatus.ESTABLISHED) {
                        Log.d(TcpConnection.class.getName(), "Device write to remote:  " + this);
                        if (!this.remoteRelayToDeviceJobRunning.get()) {
                            this.remoteRelayToDeviceJobRunning.set(true);
                            Executors.newSingleThreadExecutor().execute(this.remoteRelayToDeviceJob);
                        }
                        int dataLength = tcpPacket.getData().length;
                        this.writeToRemote(tcpPacket.getData());
                        this.currentAcknowledgementNumber += dataLength;
                        this.currentSequenceNumber = tcpHeader.getAcknowledgementNumber();
                        this.writeAck(null);
                        Log.d(TcpConnection.class.getName(),
                                "Receive ack [end.4], data write to remote, current connection: " + this);
                        continue;
                    }
                }
            } catch (Exception e) {
                this.status = TcpConnectionStatus.CLOSED;
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

    private int readFromRemote(byte[] data) throws Exception {
        InputStream remoteStream = this.remoteSocket.getInputStream();
        return remoteStream.read(data);
    }

    public void close() throws Exception {
        this.remoteSocket.close();
    }

    @Override
    public String toString() {
        return "TcpConnection{" +
                "id='" + id + '\'' +
                ", repositoryKey=" + repositoryKey +
                ", status=" + status +
                ", currentSequenceNumber=" + currentSequenceNumber +
                ", currentAcknowledgementNumber=" + currentAcknowledgementNumber +
                ", clientSyncSequenceNumber=" + clientSyncSequenceNumber +
                ", remoteRelayToDeviceJobRunning=" + remoteRelayToDeviceJobRunning +
                '}';
    }
}
