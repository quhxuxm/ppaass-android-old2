package com.ppaass.agent.service;

import android.app.Service;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import com.ppaass.agent.R;
import com.ppaass.agent.service.handler.IpPacketHandler;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

public class PpaassVpnService extends VpnService {
    private static final String VPN_ADDRESS = "110.110.110.110";
    private static final String VPN_ROUTE = "0.0.0.0";
    //    private static final String DNS = "10.246.128.21";
    private static final String DNS = "192.168.31.1";
    //    private static final String DNS = "8.8.8.8";
    private String id;
    private ParcelFileDescriptor vpnInterface;
    private FileInputStream rawDeviceInputStream;
    private FileOutputStream rawDeviceOutputStream;
    private boolean running;

    public PpaassVpnService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();
        this.id = UUID.randomUUID().toString().replace("-", "");
        Log.i(PpaassVpnService.class.getName(), "onCreate: " + this.id);
        Builder vpnBuilder = new Builder();
        vpnBuilder.addAddress(VPN_ADDRESS, 32).addRoute(VPN_ROUTE, 0).addDnsServer(DNS).setMtu(IVpnConst.MTU)
                .setBlocking(false);
        vpnBuilder.setSession(getString(R.string.app_name));
        this.vpnInterface = vpnBuilder.establish();
        final FileDescriptor vpnFileDescriptor = vpnInterface.getFileDescriptor();
        this.rawDeviceInputStream = new FileInputStream(vpnFileDescriptor);
        this.rawDeviceOutputStream = new FileOutputStream(vpnFileDescriptor);
        this.running = false;
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        if (this.running) {
            Log.i(PpaassVpnService.class.getName(), "onStartCommand(start already): " + this.id);
            return Service.START_STICKY;
        }
        this.running = true;
        try {
            IpPacketHandler ipPacketHandler =
                    new IpPacketHandler(this.rawDeviceInputStream, this.rawDeviceOutputStream,
                            IVpnConst.READ_BUFFER_SIZE, this);
            ipPacketHandler.start();
        } catch (Exception e) {
            Log.e(PpaassVpnService.class.getName(), "Fail onStartCommand: " + this.id, e);
            return Service.START_STICKY;
        }
        Log.i(PpaassVpnService.class.getName(), "onStartCommand: " + this.id);
        return Service.START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        this.running = false;
        try {
            this.vpnInterface.close();
        } catch (IOException e) {
            Log.e(PpaassVpnService.class.getName(), "Fail to close vpn interface: " + this.id, e);
        }
    }
}
