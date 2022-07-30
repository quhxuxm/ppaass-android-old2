package com.ppaass.agent.protocol.general.tcp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TcpHeader {
    private int sourcePort;
    private int destinationPort;
    private long sequenceNumber;
    private long acknowledgementNumber;
    private int offset;
    private int resolve;
    private boolean urg;
    private boolean ack;
    private boolean psh;
    private boolean rst;
    private boolean syn;
    private boolean fin;
    private int window;
    private int checksum;
    private int urgPointer;
    private final List<TcpHeaderOption> options;

    TcpHeader() {
        this.urg = false;
        this.ack = false;
        this.psh = false;
        this.rst = false;
        this.syn = false;
        this.fin = false;
        this.options = new ArrayList<>();
    }

    public int getSourcePort() {
        return sourcePort;
    }

    void setSourcePort(int sourcePort) {
        this.sourcePort = sourcePort;
    }

    public int getDestinationPort() {
        return destinationPort;
    }

    void setDestinationPort(int destinationPort) {
        this.destinationPort = destinationPort;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    void setSequenceNumber(long sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    public long getAcknowledgementNumber() {
        return acknowledgementNumber;
    }

    void setAcknowledgementNumber(long acknowledgementNumber) {
        this.acknowledgementNumber = acknowledgementNumber;
    }

    public int getOffset() {
        return offset;
    }

    void setOffset(int offset) {
        this.offset = offset;
    }

    public int getResolve() {
        return resolve;
    }

    void setResolve(int resolve) {
        this.resolve = resolve;
    }

    public boolean isUrg() {
        return urg;
    }

    void setUrg(boolean urg) {
        this.urg = urg;
    }

    public boolean isAck() {
        return ack;
    }

    void setAck(boolean ack) {
        this.ack = ack;
    }

    public boolean isPsh() {
        return psh;
    }

    void setPsh(boolean psh) {
        this.psh = psh;
    }

    public boolean isRst() {
        return rst;
    }

    void setRst(boolean rst) {
        this.rst = rst;
    }

    public boolean isSyn() {
        return syn;
    }

    void setSyn(boolean syn) {
        this.syn = syn;
    }

    public boolean isFin() {
        return fin;
    }

    void setFin(boolean fin) {
        this.fin = fin;
    }

    public int getWindow() {
        return window;
    }

    void setWindow(int window) {
        this.window = window;
    }

    public int getChecksum() {
        return checksum;
    }

    void setChecksum(int checksum) {
        this.checksum = checksum;
    }

    void setUrgPointer(int urgPointer) {
        this.urgPointer = urgPointer;
    }

    public int getUrgPointer() {
        return urgPointer;
    }

    public List<TcpHeaderOption> getOptions() {
        return options;
    }

    @Override
    public String toString() {
        StringBuilder optionsBuilder = new StringBuilder();
        this.options.forEach((option) -> {
            optionsBuilder.append(option.getKind().name()).append(":").append(Arrays.toString(option.getInfo()))
                    .append(",");
        });
        return "TcpHeader{" + "sourcePort=" + sourcePort + ", destinationPort=" + destinationPort +
                ", sequenceNumber=" + sequenceNumber + ", acknowledgementNumber=" + acknowledgementNumber +
                ", offset=" + offset + ", resolve=" + resolve + ", window=" + window + ", checksum=" + checksum +
                ", urgPointer=" + urgPointer + ", options={" + optionsBuilder + "}" + ", URG=" + urg +
                ", ACK=" + ack + ", PSH=" + psh + ", RST=" + rst + ", SYN=" + syn + ", FIN=" + fin + '}';
    }
}
