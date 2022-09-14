package com.ppaass.agent.service;

import android.app.Service;
import android.content.Intent;
import android.net.VpnService;
import android.system.OsConstants;
import android.util.Log;
import com.ppaass.agent.PpaassVpnApplication;
import com.ppaass.agent.R;
import com.ppaass.agent.cryptography.CryptographyUtil;
import com.ppaass.agent.service.handler.IpPacketHandler;
import org.apache.commons.io.IOUtils;

import java.io.*;
import java.util.UUID;

public class PpaassVpnService extends VpnService {
    private static final String VPN_ADDRESS = "110.110.110.110";
    private static final String VPN_ROUTE = "0.0.0.0";
    private String id;
    private Builder vpnBuilder;

    public PpaassVpnService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();
        byte[] agentPrivateKeyBytes;
        try (InputStream agentPrivateKeyStream =
                     this.getResources().openRawResource(R.raw.agentprivatekey)) {
            agentPrivateKeyBytes = IOUtils.toByteArray(agentPrivateKeyStream);
        } catch (IOException e) {
            Log.e(PpaassVpnService.class.getName(), "Fail to read agent private key because of exception.", e);
            throw new RuntimeException(e);
        }
        byte[] proxyPublicKeyBytes;
        try (InputStream proxyPublicKeyStream =
                     this.getResources().openRawResource(R.raw.proxypublickey)) {
            proxyPublicKeyBytes = IOUtils.toByteArray(proxyPublicKeyStream);
        } catch (IOException e) {
            Log.e(PpaassVpnService.class.getName(), "Fail to read agent public key because of exception.", e);
            throw new RuntimeException(e);
        }
        CryptographyUtil.INSTANCE.init(proxyPublicKeyBytes, agentPrivateKeyBytes);
        this.id = UUID.randomUUID().toString().replace("-", "");
        Log.i(PpaassVpnService.class.getName(), "onCreate: " + this.id);
        var vpnBuilder = new Builder();
        vpnBuilder.addAddress(VPN_ADDRESS, 32).addRoute(VPN_ROUTE, 0)
                .addDnsServer(IVpnConst.DNS)
                .setMtu(IVpnConst.MTU)
                .setBlocking(true);
        vpnBuilder.setSession(getString(R.string.app_name));
        vpnBuilder.allowFamily(OsConstants.AF_INET);
        this.vpnBuilder = vpnBuilder;
        this.initialize();
    }

    private void initialize() {
        var vpnInterface = this.vpnBuilder.establish();
        PpaassVpnApplication application = (PpaassVpnApplication) this.getApplication();
        final FileDescriptor vpnFileDescriptor = vpnInterface.getFileDescriptor();
        var initializeResult =
                new PpaassVpnApplication.VpnInitializeResult(vpnInterface, new FileInputStream(vpnFileDescriptor),
                        new FileOutputStream(vpnFileDescriptor));
        application.attachInitializeResult(initializeResult);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        PpaassVpnApplication application = (PpaassVpnApplication) this.getApplication();
        if (application.isVpnStarted()) {
            Log.i(PpaassVpnService.class.getName(), "Receive start command when service is running: " + this.id);
            return Service.START_STICKY;
        }
        if (!application.isVpnInitializeResultAttached()) {
            this.initialize();
        }
        try {
            var ipPacketHandler =
                    new IpPacketHandler(application.getInitializeResult().getRawDeviceInputStream(),
                            application.getInitializeResult().getRawDeviceOutputStream(),
                            IVpnConst.READ_BUFFER_SIZE, this, application);
            ipPacketHandler.start();
        } catch (Exception e) {
            application.stopVpn();
            Log.e(PpaassVpnService.class.getName(), "Fail to start service: " + this.id, e);
            return Service.START_STICKY;
        }
        application.startVpn();
        Log.i(PpaassVpnService.class.getName(), "Success to start service: " + this.id);
        return Service.START_STICKY;
    }
}
