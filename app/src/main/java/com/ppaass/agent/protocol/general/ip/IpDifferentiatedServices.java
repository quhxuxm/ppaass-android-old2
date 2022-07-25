package com.ppaass.agent.protocol.general.ip;

public class IpDifferentiatedServices {
    private final int importance;
    private final boolean delay;
    private final boolean highStream;
    private final boolean highAvailability;

    public IpDifferentiatedServices(int importance, boolean delay, boolean highStream, boolean highAvailability) {
        this.importance = importance;
        this.delay = delay;
        this.highAvailability = highAvailability;
        this.highStream = highStream;
    }

    public int getImportance() {
        return importance;
    }

    public boolean isDelay() {
        return delay;
    }

    public boolean isHighStream() {
        return highStream;
    }

    public boolean isHighAvailability() {
        return highAvailability;
    }

    @Override
    public String toString() {
        return "IpDifferentiatedServices{" +
                "importance=" + importance +
                ", delay=" + delay +
                ", highStream=" + highStream +
                ", highAvailability=" + highAvailability +
                '}';
    }
}
