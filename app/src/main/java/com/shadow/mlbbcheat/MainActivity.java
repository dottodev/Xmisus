package com.shadow.mlbbcheat;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Toast;

import com.shadow.mlbbcheat.services.OverlayService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Button startBtn = new Button(this);
        startBtn.setText("Launch Cheat");
        startBtn.setOnClickListener(v -> launchCheat());
        setContentView(startBtn);

        copyScriptToGameGuardian();
    }

    private void copyScriptToGameGuardian() {
        try {
            File dir = new File(Environment.getExternalStorageDirectory(),
                    "GameGuardian/scripts");
            if (!dir.exists() && !dir.mkdirs()) return;
            File target = new File(dir, "mlbb_cheat.lua");
            try (InputStream in = getAssets().open("scripts/mlbb_cheat.lua");
                 OutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Script copy failed: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void launchCheat() {
        Intent overlay = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(overlay);
        } else {
            startService(overlay);
        }
        Toast.makeText(this, "Cheat active. Open MLBB in Parallel Space.",
                Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }
}
