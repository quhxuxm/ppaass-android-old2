package com.ppaass.agent.rust.service;

import android.app.Service;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;
import android.util.Log;
import com.ppaass.agent.rust.R;
import com.ppaass.agent.rust.jni.RustLibrary;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.Executors;

public class PpaassVpnService extends VpnService {
    private static final String VPN_ADDRESS = "110.110.110.110";
    private static final String VPN_ROUTE = "0.0.0.0";
    private String id;
    private ParcelFileDescriptor vpnFd;

    public PpaassVpnService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();
        this.id = UUID.randomUUID().toString().replace("-", "");
        Log.i(PpaassVpnService.class.getName(), "onCreate: " + this.id);
        var vpnBuilder = new Builder();
        vpnBuilder.addAddress(VPN_ADDRESS, 32).addRoute(VPN_ROUTE, 0)
                .addDnsServer(IVpnConst.DNS)
                .setMtu(IVpnConst.MTU)
                .setBlocking(true);
        vpnBuilder.setSession(getString(R.string.app_name));
        vpnBuilder.allowFamily(OsConstants.AF_INET);
        this.vpnFd = vpnBuilder.establish();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    RustLibrary.initLog();
                    RustLibrary.startVpn(this.vpnFd.detachFd(), this);
                } catch (Exception e) {
                    Log.e(PpaassVpnService.class.getName(), "Stop PPAASS VPN thread because of exception.", e);
                    return;
                }
                Log.d(PpaassVpnService.class.getName(), "Stop PPAASS VPN thread normally.");
            });
        } catch (Exception e) {

            Log.e(PpaassVpnService.class.getName(), "Fail to start service: " + this.id, e);
            return Service.START_STICKY;
        }
        Log.i(PpaassVpnService.class.getName(), "Success to start service: " + this.id);
        return Service.START_STICKY;
    }
}
