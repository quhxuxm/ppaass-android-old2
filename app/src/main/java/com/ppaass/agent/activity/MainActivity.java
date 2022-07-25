package com.ppaass.agent.activity;

import android.content.Intent;
import android.net.VpnManager;
import android.net.VpnService;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.ppaass.agent.R;
import com.ppaass.agent.service.PpaassVpnService;

public class MainActivity extends AppCompatActivity {
    private static final int VPN_SERVICE_REQUEST_CODE = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }

    public void startVpn(View view) {
        Intent prepareVpnIntent = VpnService.prepare(MainActivity.this);
        if (prepareVpnIntent != null) {
            startActivityForResult(prepareVpnIntent, VPN_SERVICE_REQUEST_CODE);
            Log.d(MainActivity.class.getName(), "Vpn instance(existing) prepared ...");
            return;
        }
        Log.d(MainActivity.class.getName(), "Vpn instance(new) prepared ...");
        Intent startVpnServiceIntent = new Intent(MainActivity.this, PpaassVpnService.class);
        this.startService(startVpnServiceIntent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_SERVICE_REQUEST_CODE) {
            Intent startVpnServiceIntent = new Intent(MainActivity.this, PpaassVpnService.class);
            this.startService(startVpnServiceIntent);
        }
    }

    public void stopVpn(View view) {
        Intent intent = new Intent(this, PpaassVpnService.class);
        stopService(intent);
    }
}
