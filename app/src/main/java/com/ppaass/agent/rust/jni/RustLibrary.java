package com.ppaass.agent.rust.jni;

import android.net.VpnService;

public class RustLibrary {
    static {
        System.loadLibrary("rust");
    }

    public static native String handleInputString(String inputMessage);


    public static native ExampleNativeObject handleInputObject(ExampleNativeObject input);

    public static native void startVpn(int vpnFd, VpnService vpnService);
    public static native void initLog();
}