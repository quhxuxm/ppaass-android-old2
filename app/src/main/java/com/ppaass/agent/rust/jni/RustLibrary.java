package com.ppaass.agent.rust.jni;

import android.net.VpnService;

public class RustLibrary {
    static {
        System.loadLibrary("rust");
    }
    public static native void startVpn(int vpnFd, VpnService vpnService);

    public static native void stopVpn();

}