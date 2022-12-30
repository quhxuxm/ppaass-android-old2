package com.ppaass.agent.jni;

public class RustLibrary {
    static {
        System.loadLibrary("rust");
    }
    public static native String doSomething(String code);
}