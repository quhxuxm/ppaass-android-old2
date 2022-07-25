package com.ppaass.agent.protocol.general.ip;

import com.ppaass.agent.protocol.general.IProtocolConst;

import java.util.concurrent.atomic.AtomicInteger;

public class IpV4HeaderBuilder {
    private static final AtomicInteger IP_IDENTIFICATION = new AtomicInteger(0);
    private IpDifferentiatedServices ds;
    private IpExplicitCongestionNotification ecn;
    private int identification;
    private IpFlags flags;
    private int fragmentOffset;
    private int ttl;
    private IpDataProtocol protocol;
    private int checksum;
    private byte[] sourceAddress;
    private byte[] destinationAddress;
    private byte[] options;

    public IpV4HeaderBuilder() {
        this.ttl = 64;
        this.ds = new IpDifferentiatedServices(0, false, false, false);
        this.ecn = new IpExplicitCongestionNotification(false, 0);
        this.options = new byte[]{};
        this.flags = new IpFlags(true, false);
        this.identification = IP_IDENTIFICATION.getAndIncrement();
        this.fragmentOffset = 0;
    }

    public IpV4HeaderBuilder ds(IpDifferentiatedServices ds) {
        this.ds = ds;
        return this;
    }

    public IpV4HeaderBuilder ecn(IpExplicitCongestionNotification ecn) {
        this.ecn = ecn;
        return this;
    }

    public IpV4HeaderBuilder identification(int identification) {
        this.identification = identification;
        return this;
    }

    public IpV4HeaderBuilder flags(IpFlags flags) {
        this.flags = flags;
        return this;
    }

    public IpV4HeaderBuilder fragmentOffset(int fragmentOffset) {
        this.fragmentOffset = fragmentOffset;
        return this;
    }

    public IpV4HeaderBuilder ttl(int ttl) {
        this.ttl = ttl;
        return this;
    }

    public IpV4HeaderBuilder protocol(IpDataProtocol protocol) {
        this.protocol = protocol;
        return this;
    }

    IpV4HeaderBuilder checksum(int checksum) {
        this.checksum = checksum;
        return this;
    }

    public IpV4HeaderBuilder sourceAddress(byte[] sourceAddress) {
        this.sourceAddress = sourceAddress;
        return this;
    }

    public IpV4HeaderBuilder destinationAddress(byte[] destinationAddress) {
        this.destinationAddress = destinationAddress;
        return this;
    }

    public IpV4HeaderBuilder options(byte[] options) {
        if (options == null) {
            this.options = new byte[]{};
            return this;
        }
        this.options = options;
        return this;
    }

    public IpV4Header build() {
        IpV4Header
                result = new IpV4Header();
        result.setOptions(this.options);
        result.setDestinationAddress(this.destinationAddress);
        result.setIdentification(this.identification);
        result.setSourceAddress(this.sourceAddress);
        result.setTtl(this.ttl);
        result.setDs(this.ds);
        result.setEcn(this.ecn);
        result.setFlags(this.flags);
        result.setFragmentOffset(this.fragmentOffset);
        result.setProtocol(this.protocol);
        result.setChecksum(this.checksum);
        int internetHeaderLength = (IProtocolConst.MIN_IP_HEADER_LENGTH + this.options.length) / 4;
        result.setInternetHeaderLength(internetHeaderLength);
        return result;
    }
}
