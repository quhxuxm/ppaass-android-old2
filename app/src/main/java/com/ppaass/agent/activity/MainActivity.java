package com.ppaass.agent.activity;

import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
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
        Button startVpnButton = this.findViewById(R.id.startButton);
        startVpnButton.setOnClickListener(view -> {
            Intent prepareVpnIntent = VpnService.prepare(getApplicationContext());
            if (prepareVpnIntent != null) {
                startActivityForResult(prepareVpnIntent, VPN_SERVICE_REQUEST_CODE);
                Log.d(MainActivity.class.getName(), "Vpn instance(new) prepared ...");
            } else {
                Log.d(MainActivity.class.getName(), "Vpn instance(existing) prepared ...");
                onActivityResult(VPN_SERVICE_REQUEST_CODE, RESULT_OK, null);
            }
        });
        Button stopVpnButton = this.findViewById(R.id.stopButton);
        stopVpnButton.setOnClickListener(view -> {
            Intent intent = new Intent(this, PpaassVpnService.class);
            stopService(intent);
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK) {
            Intent startVpnServiceIntent = new Intent(this, PpaassVpnService.class);
            this.startService(startVpnServiceIntent);
        }
    }
}
