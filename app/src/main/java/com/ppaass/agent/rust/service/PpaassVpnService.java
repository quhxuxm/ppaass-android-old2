package com.ppaass.agent.rust.service;

import android.app.Service;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import com.ppaass.agent.rust.R;
import com.ppaass.agent.rust.jni.RustLibrary;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Executors;

public class PpaassVpnService extends VpnService {
    private static final String VPN_ADDRESS = "110.110.110.110";
    private static final String VPN_ROUTE = "0.0.0.0";
    private String id;
    private ParcelFileDescriptor vpnFd;

    public PpaassVpnService() {
        this.id = UUID.randomUUID().toString().replace("-", "");
        Log.i(PpaassVpnService.class.getName(), "Ppaass Vpn create service object: " + this.id);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(PpaassVpnService.class.getName(), "Ppaass Vpn service object onCreate: " + this.id);
        var vpnBuilder = new Builder();
        vpnBuilder.addAddress(VPN_ADDRESS, 32).addRoute(VPN_ROUTE, 0)
                .addDnsServer(IVpnConst.DNS)
                .setMtu(IVpnConst.MTU);
        vpnBuilder.setSession(getString(R.string.app_name));
        this.vpnFd = vpnBuilder.establish();
    }


    @Override
    public boolean stopService(Intent name) {
        boolean result = super.stopService(name);
        Log.d(PpaassVpnService.class.getName(), "Ppaass Vpn service object stopped.");
        return result;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    int nativeVpnFileDescriptor = vpnFd.detachFd();
                    Log.d(PpaassVpnService.class.getName(), "Ppaass Vpn native file descriptor: "+nativeVpnFileDescriptor);
                    RustLibrary.startVpn(nativeVpnFileDescriptor, this);
                } catch (Exception e) {
                    Log.e(PpaassVpnService.class.getName(), "Stop Ppaass Vpn thread because of exception.", e);
                    return;
                }
                Log.d(PpaassVpnService.class.getName(), "Stop Ppaass Vpn thread normally.");
            });
            Log.i(PpaassVpnService.class.getName(), "Ppaass Vpn success to start service: " + this.id);
            return Service.START_STICKY;
        } catch (Exception e) {
            Log.e(PpaassVpnService.class.getName(), "Ppaass Vpn fail to start service: " + this.id, e);
            return Service.START_STICKY;
        }
    }
}
