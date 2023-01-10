package com.ppaass.agent.rust.activity;

import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.ppaass.agent.rust.R;
import com.ppaass.agent.rust.jni.RustLibrary;
import com.ppaass.agent.rust.service.PpaassVpnService;

public class MainActivity extends AppCompatActivity {
    private static final int VPN_SERVICE_REQUEST_CODE = 1;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        var startVpnButton = this.findViewById(R.id.startButton);
        startVpnButton.setOnClickListener(view -> {
            var prepareVpnIntent = VpnService.prepare(this);
            if (prepareVpnIntent != null) {
                startActivityForResult(prepareVpnIntent, VPN_SERVICE_REQUEST_CODE);
                Log.d(MainActivity.class.getName(), "VPN service instance(new) prepared ...");
            } else {
                Log.d(MainActivity.class.getName(), "VPN service instance(existing) prepared ...");
                onActivityResult(VPN_SERVICE_REQUEST_CODE, RESULT_OK, null);
            }
        });
        var stopVpnButton = this.findViewById(R.id.stopButton);
        stopVpnButton.setOnClickListener(view -> {
            var stopVpnServiceIntent = new Intent(MainActivity.this, PpaassVpnService.class);
            stopService(stopVpnServiceIntent);
            RustLibrary.stopVpn();
            Log.d(MainActivity.class.getName(), "Click stop button, going to stop VPN service");
        });
        var clearDnsButton = this.findViewById(R.id.clearDnsButton);
        clearDnsButton.setOnClickListener(view -> {

        });
//        var chooseAppButton = this.findViewById(R.id.chooseApplication);
//        chooseAppButton.setOnClickListener(view -> {
//            var packageManager = MainActivity.this.getPackageManager();
//            var packages = packageManager.getInstalledPackages(PackageManager.GET_SERVICES);
//            var dialogBuilder = new AlertDialog.Builder(this);
//            dialogBuilder.setTitle("Choose application");
//            var packageNameBuilder = new StringBuilder();
//            packages.forEach(p -> {
//                boolean isSysApp = (p.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 1;
//                if (!isSysApp) {
//                    packageNameBuilder.append(p.packageName).append("\n");
//                }
//            });
//            dialogBuilder.setMessage(packageNameBuilder.toString());
//            var dialog = dialogBuilder.create();
//            dialog.show();
//        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK) {
            Intent startVpnServiceIntent = new Intent(this, PpaassVpnService.class);
            this.startService(startVpnServiceIntent);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
    }
}
