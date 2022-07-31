package com.ppaass.agent.activity;

public class JniTest {
    static {
        System.loadLibrary("jnitest");
    }

    public static native String getStringFromNDK();
}
