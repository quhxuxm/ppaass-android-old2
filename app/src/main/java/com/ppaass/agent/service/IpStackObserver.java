package com.ppaass.agent.service;

import android.util.Log;
import com.ppaass.agent.common.IFlowObserver;
import com.ppaass.agent.protocol.general.ip.IIpHeader;
import com.ppaass.agent.protocol.general.ip.IpPacket;
import com.ppaass.agent.protocol.general.ip.IpV4Header;
import com.ppaass.agent.protocol.general.tcp.TcpPacket;
import com.ppaass.agent.protocol.general.udp.UdpPacket;

import java.io.FileOutputStream;

public class IpStackObserver implements IFlowObserver<IpPacket> {
    private final TcpPacketHandler tcpPacketHandler;
    private final UdpPacketHandler udpPacketHandler;

    public IpStackObserver(FileOutputStream rawVpnOutputStream) {
        this.tcpPacketHandler = new TcpPacketHandler(rawVpnOutputStream);
        this.udpPacketHandler = new UdpPacketHandler(rawVpnOutputStream);
    }

    @Override
    public void onFlowElement(IpPacket element) {
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
                        Log.e(IpStackObserver.class.getName(), "Unsupported protocol");
//                        throw new UnsupportedOperationException("Unsupported protocol.");
                        break;
                    }
                }
                break;
            }
            case V6: {
//                throw new UnsupportedOperationException("IpV6 still not support.");
                Log.e(IpStackObserver.class.getName(), "IpV6 still not support");
                break;
            }
            default: {
                throw new UnsupportedOperationException("Unsupported ip version.");
            }
        }
    }
}
