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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int VPN_SERVICE_REQUEST_CODE = 1;
    private static final ExecutorService testThreadPool = Executors.newFixedThreadPool(32);

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
        Button testVpnButton = this.findViewById(R.id.testButton);
        testVpnButton.setOnClickListener(view -> {
            testThreadPool.execute(() -> {
                for (int j = 0; j < 20; j++) {
                    try (Socket testSocket = new Socket()) {
                        testSocket.connect(new InetSocketAddress("192.168.31.200", 65533));
                        OutputStream testOutput = testSocket.getOutputStream();
                        long timestamp = System.currentTimeMillis();
                        testOutput.write((">>>>>>>>[" + timestamp + "]>>>>>>>>\n").getBytes());
                        StringBuilder data = new StringBuilder();
                        for (int i = 0; i < 10000; i++) {
                            data.append("[").append(i).append("]::::abcdefghijklmnopqrstuvwxyz\n");
                        }
                        testOutput.write(data.toString().getBytes());
                        testOutput.write(("<<<<<<<<[" + timestamp + "]<<<<<<<<\n").getBytes());
                        testOutput.flush();
                    } catch (IOException e) {
                        Log.e(MainActivity.class.getName(), "Exception happen for testing.", e);
                    }
                }
            });
        });
        Button callNativeButton = this.findViewById(R.id.callNative);
        callNativeButton.setOnClickListener(view -> {
            String result = JniTest.getStringFromNDK();
            Log.d("JNITEST", result);
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
