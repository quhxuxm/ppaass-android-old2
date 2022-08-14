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

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
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
                for (int j = 0; j < 1; j++) {
                    CountDownLatch countDownLatch = new CountDownLatch(2);
                    try (Socket testSocket = new Socket()) {
                        testSocket.connect(new InetSocketAddress("192.168.31.200", 65533));
                        OutputStream testOutput = testSocket.getOutputStream();
                        InputStream testInput = testSocket.getInputStream();
                        testThreadPool.execute(() -> {
                            try {
                                long timestamp = System.currentTimeMillis();
                                StringBuilder data = new StringBuilder();
                                data.append(">>>>>>>>[").append(timestamp).append("]>>>>>>>>");
                                for (int i = 0; i < 1000; i++) {
                                    data.append("[").append(i).append("]-abcdefghijklmnopqrstuvwxyz;");
                                }
                                data.append("<<<<<<<<[").append(timestamp).append("]<<<<<<<<");
                                testOutput.write(data.toString().getBytes());
                                testOutput.write("\n\n".getBytes());
                                testOutput.flush();
                                Log.d(MainActivity.class.getName(), "Finish output !!!!!");
                            } catch (Exception e) {
                                Log.e(MainActivity.class.getName(), "Exception happen for write data to server.", e);
                            } finally {
                                countDownLatch.countDown();
                            }
                        });
                        testThreadPool.execute(() -> {
                            try {
                                while (true) {
                                    byte[] readBuf = new byte[1024];
                                    int readSize = testInput.read(readBuf);
                                    if (readSize <= 0) {
                                        Log.d(MainActivity.class.getName(), "Finish input !!!!!");
                                        return;
                                    }
                                    byte[] readBufData = Arrays.copyOf(readBuf, readSize);
                                    String data = new String(readBufData);
                                    Log.v(MainActivity.class.getName(), "Read data from server:\n" + data + "\n");
                                }
                            } catch (Exception e) {
                                Log.e(MainActivity.class.getName(), "Exception happen for read data from server.", e);
                            } finally {
                                countDownLatch.countDown();
                            }
                        });
                        countDownLatch.await();
                        Log.d(MainActivity.class.getName(), "Finish testing !!!!!");
                    } catch (Exception e) {
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
