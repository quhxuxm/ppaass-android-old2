package com.ppaass.agent.service;

import android.util.Log;
import com.ppaass.agent.protocol.general.ip.IIpHeader;
import com.ppaass.agent.protocol.general.ip.IpPacket;
import com.ppaass.agent.protocol.general.ip.IpPacketReader;
import com.ppaass.agent.protocol.general.ip.IpV4Header;
import com.ppaass.agent.protocol.general.tcp.TcpPacket;
import com.ppaass.agent.protocol.general.udp.UdpPacket;

import java.io.InputStream;
import java.io.OutputStream;

public class IpPacketProcessor implements Runnable {
    private final InputStream rawVpnInputStream;
    private final int readBufferSize;
    private final int writeBufferSize;
    private final TcpPacketHandler tcpPacketHandler;
    private final UdpPacketHandler udpPacketHandler;
    private boolean start;

    public IpPacketProcessor(InputStream rawVpnInputStream, OutputStream rawVpnOutputStream, int readBufferSize,
                             int writeBufferSize) {
        this.rawVpnInputStream = rawVpnInputStream;
        this.readBufferSize = readBufferSize;
        this.writeBufferSize = writeBufferSize;
        this.tcpPacketHandler = new TcpPacketHandler(rawVpnOutputStream);
        this.udpPacketHandler = new UdpPacketHandler(rawVpnOutputStream);
        this.start = false;
    }

    public void run() {
        while (this.start) {
            try {
                IpPacket ipPacket = this.read();
                if (ipPacket == null) {
                    Thread.sleep(50);
                    continue;
                }
                this.handle(ipPacket);
            } catch (Exception e) {
                Log.e(IpPacketProcessor.class.getName(),
                        "Fail to read ip packet from raw input stream because of exception.", e);
            }
        }
    }

    public boolean isRunning() {
        return this.start;
    }

    public void stop() {
        this.start = false;
    }

    public void start() {
        this.start = true;
    }

    public void handle(IpPacket element) {
        IIpHeader ipHeader = element.getHeader();
        switch (ipHeader.getVersion()) {
            case V4: {
                IpV4Header ipV4Header = (IpV4Header) ipHeader;
                switch (ipV4Header.getProtocol()) {
                    case TCP: {
                        TcpPacket tcpPacket = (TcpPacket) element.getData();
                        this.tcpPacketHandler.handle(tcpPacket);
                        break;
                    }
                    case UDP: {
                        UdpPacket udpPacket = (UdpPacket) element.getData();
                        this.udpPacketHandler.handle(udpPacket);
                        break;
                    }
                    default: {
                        Log.e(IpPacketProcessor.class.getName(),
                                "Ignore unsupported protocol: " + ipV4Header.getProtocol());
                        break;
                    }
                }
                break;
            }
            case V6: {
                Log.e(IpPacketProcessor.class.getName(), "Ignore IpV6 packet because of not support");
                break;
            }
            default: {
                throw new UnsupportedOperationException("Unsupported ip version.");
            }
        }
    }

    private IpPacket read() {
        byte[] buffer = new byte[this.readBufferSize];
        try {
            int size = this.rawVpnInputStream.read(buffer);
            if (size <= 0) {
                Log.d(IpPacketProcessor.class.getName(),
                        "Nothing to read from raw input stream because of read size: " + size);
                return null;
            }
            return IpPacketReader.INSTANCE.parse(buffer);
        } catch (Exception e) {
            Log.e(IpPacketProcessor.class.getName(),
                    "Fail to read ip packet from raw input stream because of exception.", e);
            throw new RuntimeException(e);
        }
    }
}
