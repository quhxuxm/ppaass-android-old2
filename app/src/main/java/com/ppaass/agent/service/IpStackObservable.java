package com.ppaass.agent.service;

import com.ppaass.agent.common.AbstractFlowObservable;
import com.ppaass.agent.protocol.general.ip.IpPacket;
import com.ppaass.agent.protocol.general.ip.IpPacketReader;

import java.io.InputStream;

public class IpStackObservable extends AbstractFlowObservable<IpPacket> {
    private final InputStream ipStream;
    private final int bufferSize;

    public IpStackObservable(InputStream ipStream, int bufferSize) {
        this.ipStream = ipStream;
        this.bufferSize = bufferSize;
    }

    @Override
    protected IpPacket readFlowElement() {
        byte[] buffer = new byte[this.bufferSize];
        try {
            int size = this.ipStream.read(buffer);
            if (size <= 0) {
                return null;
            }
            return IpPacketReader.INSTANCE.parse(buffer);
        } catch (Exception e) {
            return null;
        }
    }
}
