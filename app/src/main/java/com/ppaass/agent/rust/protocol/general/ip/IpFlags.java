package com.ppaass.agent.rust.protocol.general.ip;

public class IpFlags {
    private final int resolved;
    private final boolean df;
    private final boolean mf;

    public IpFlags(boolean df, boolean mf) {
        this.resolved = 0;
        this.df = df;
        this.mf = mf;
    }

    public int getResolved() {
        return resolved;
    }

    public boolean isDf() {
        return df;
    }

    public boolean isMf() {
        return mf;
    }

    @Override
    public String toString() {
        return "IpFlags{" +
                "resolved=" + resolved +
                ", df=" + df +
                ", mf=" + mf +
                '}';
    }
}
