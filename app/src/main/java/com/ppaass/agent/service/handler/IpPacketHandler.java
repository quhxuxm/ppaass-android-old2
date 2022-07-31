package com.ppaass.agent.service.handler;

import android.util.Log;
import com.ppaass.agent.protocol.general.ip.IIpHeader;
import com.ppaass.agent.protocol.general.ip.IpPacket;
import com.ppaass.agent.protocol.general.ip.IpPacketReader;
import com.ppaass.agent.protocol.general.ip.IpV4Header;
import com.ppaass.agent.protocol.general.tcp.TcpPacket;
import com.ppaass.agent.protocol.general.udp.UdpPacket;
import com.ppaass.agent.service.PpaassVpnService;
import com.ppaass.agent.service.handler.tcp.IpV4TcpPacketHandler;
import com.ppaass.agent.service.handler.udp.IpV4UdpPacketHandler;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Executors;

public class IpPacketHandler {
    private final InputStream rawDeviceInputStream;
    private final int readBufferSize;
    private final IpV4TcpPacketHandler ipV4TcpPacketHandler;
    private final IpV4UdpPacketHandler ipV4UdpPacketHandler;
    private final PpaassVpnService vpnService;

    public IpPacketHandler(InputStream rawDeviceInputStream, OutputStream rawDeviceOutputStream, int readBufferSize,
                           PpaassVpnService vpnService) throws Exception {
        this.rawDeviceInputStream = rawDeviceInputStream;
        this.readBufferSize = readBufferSize;
        this.vpnService = vpnService;
        this.ipV4TcpPacketHandler = new IpV4TcpPacketHandler(rawDeviceOutputStream, vpnService);
        this.ipV4UdpPacketHandler = new IpV4UdpPacketHandler(rawDeviceOutputStream, vpnService);
    }

    public void start() {
        Executors.newSingleThreadExecutor().execute(new Runnable() {
            @Override
            public void run() {
                while (IpPacketHandler.this.vpnService.isRunning()) {
                    try {
                        IpPacket ipPacket = IpPacketHandler.this.read();
                        if (ipPacket == null) {
                            Thread.yield();
                            continue;
                        }
                        IpPacketHandler.this.handle(ipPacket);
                    } catch (Exception e) {
                        Log.e(IpPacketHandler.class.getName(),
                                "Fail to read ip packet from raw input stream because of exception.", e);
                    }
                }
            }
        });
    }

    public void handle(IpPacket element) throws Exception {
        IIpHeader ipHeader = element.getHeader();
        switch (ipHeader.getVersion()) {
            case V4: {
                IpV4Header ipV4Header = (IpV4Header) ipHeader;
                switch (ipV4Header.getProtocol()) {
                    case TCP: {
                        TcpPacket tcpPacket = (TcpPacket) element.getData();
                        this.ipV4TcpPacketHandler.handle(tcpPacket, ipV4Header);
                        break;
                    }
                    case UDP: {
                        UdpPacket udpPacket = (UdpPacket) element.getData();
                        this.ipV4UdpPacketHandler.handle(udpPacket, ipV4Header);
                        break;
                    }
                    default: {
//                        Log.e(IpPacketHandler.class.getName(),
//                                "Ignore unsupported protocol: " + ipV4Header.getProtocol());
                        break;
                    }
                }
                break;
            }
            case V6: {
//                Log.e(IpPacketHandler.class.getName(), "Ignore IpV6 packet because of not support");
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
            int size = this.rawDeviceInputStream.read(buffer);
            if (size < 0) {
//                Log.d(IpPacketHandler.class.getName(),
//                        "Nothing to read from raw input stream because of read size: " + size);
                return null;
            }
            return IpPacketReader.INSTANCE.parse(buffer);
        } catch (Exception e) {
            Log.e(IpPacketHandler.class.getName(),
                    "Fail to read ip packet from raw input stream because of exception.", e);
            throw new RuntimeException(e);
        }
    }
}
