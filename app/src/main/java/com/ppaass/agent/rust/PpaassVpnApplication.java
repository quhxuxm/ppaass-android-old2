package com.ppaass.agent.rust;

import android.app.Application;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.IOException;

public class PpaassVpnApplication extends Application {
    public static class VpnInitializeResult {

        private ParcelFileDescriptor vpnInterface;

        public VpnInitializeResult(ParcelFileDescriptor vpnInterface) {

            this.vpnInterface = vpnInterface;
        }

        public ParcelFileDescriptor getVpnInterface() {
            return vpnInterface;
        }
    }

    private VpnInitializeResult initializeResult;
    private boolean started;

    public PpaassVpnApplication() {
    }

    public void attachInitializeResult(VpnInitializeResult initializeResult) {
        this.initializeResult = initializeResult;
    }

    public void startVpn() {
        this.started = true;
    }

    public void stopVpn() {
        if (this.initializeResult == null) {
            this.started = false;
            return;
        }
        try {
            this.initializeResult.vpnInterface.close();
            this.initializeResult = null;
            this.started = false;
            Log.i(PpaassVpnApplication.class.getName(), "Success to stop service.");
        } catch (IOException e) {
            Log.e(PpaassVpnApplication.class.getName(), "Fail to close vpn interface.");
        }
    }

    public boolean isVpnStarted() {
        return this.started;
    }

    public boolean isVpnInitializeResultAttached() {
        return this.initializeResult != null;
    }

    public VpnInitializeResult getInitializeResult() {
        return initializeResult;
    }
}
