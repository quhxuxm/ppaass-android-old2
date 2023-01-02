package com.ppaass.agent.rust.protocol.general.ip;

public class IpExplicitCongestionNotification {
    private final boolean lowCost;
    private final int resolve;

    public IpExplicitCongestionNotification(boolean lowCost, int resolve) {
        this.lowCost = lowCost;
        this.resolve = resolve;
    }

    public boolean isLowCost() {
        return lowCost;
    }

    public int getResolve() {
        return resolve;
    }

    @Override
    public String toString() {
        return "IpExplicitCongestionNotification{" +
                "lowCost=" + lowCost +
                ", resolve=" + resolve +
                '}';
    }
}
