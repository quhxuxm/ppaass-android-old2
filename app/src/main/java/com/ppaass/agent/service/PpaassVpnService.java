package com.ppaass.agent.service;

import android.app.Service;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import com.ppaass.agent.R;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.UUID;

public class PpaassVpnService extends VpnService {
    private static final String VPN_ADDRESS = "110.110.110.110";
    private static final String VPN_ROUTE = "0.0.0.0";
    private static final String DNS = "8.8.8.8";
    private String id;
    private ParcelFileDescriptor vpnInterface;
    private IpStackObservable ipStackObservable;

    public PpaassVpnService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();
        this.id = UUID.randomUUID().toString().replace("-", "");
        Log.i(PpaassVpnService.class.getName(), "onCreate: " + this.id);
        Builder vpnBuilder = new Builder();
        vpnBuilder.addAddress(VPN_ADDRESS, 32);
        vpnBuilder.addRoute(VPN_ROUTE, 0);
        vpnBuilder.addDnsServer(DNS);
        vpnBuilder.setMtu(1500);
        vpnBuilder.setBlocking(false);
        vpnBuilder.setSession(getString(R.string.app_name));
        this.vpnInterface =
                vpnBuilder.establish();
        final FileDescriptor vpnFileDescriptor = vpnInterface.getFileDescriptor();
        FileInputStream rawIpInputStream = new FileInputStream(vpnFileDescriptor);
        FileOutputStream rawIpOutputStream = new FileOutputStream(vpnFileDescriptor);
        this.ipStackObservable = new IpStackObservable(rawIpInputStream, 65536);
        this.ipStackObservable.registerObserver(new IpStackObserver(rawIpOutputStream));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        this.ipStackObservable.start();
        Log.i(PpaassVpnService.class.getName(), "onStartCommand: " + this.id);
        return Service.START_STICKY;
    }

    @Override
    public void onRevoke() {
        super.onRevoke();
        Log.i(PpaassVpnService.class.getName(), "onRevoke: " + this.id);
        try {
            this.vpnInterface.close();
        } catch (Exception e) {
            Log.e(PpaassVpnService.class.getName(), "Error happen when destroy vpn service", e);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(PpaassVpnService.class.getName(), "onDestroy: " + this.id);
    }
}
