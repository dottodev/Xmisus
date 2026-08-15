package com.shadow.mlbbcheat.services;

import android.app.ActivityManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import com.shadow.mlbbcheat.utils.AntiDetection;

import java.util.List;

public class ScriptService extends Service {

    private volatile boolean running = true;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Thread watcher = new Thread(this::watchLoop, "script-watcher");
        watcher.setDaemon(true);
        watcher.start();
        return START_STICKY;
    }

    private void watchLoop() {
        while (running) {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> procs =
                    am.getRunningAppProcesses();
            String mlbb = detectMlbbProcess(procs);
            if (mlbb != null) {
                stopSelf();
                return;
            }
            try {
                Thread.sleep(AntiDetection.humanDelayMs() * 3);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    static String detectMlbbProcess(List<ActivityManager.RunningAppProcessInfo> processes) {
        if (processes == null) return null;
        for (ActivityManager.RunningAppProcessInfo p : processes) {
            if (p.processName != null &&
                    (p.processName.contains("moonton")
                            || p.processName.contains("mlbb"))) {
                return p.processName;
            }
        }
        return null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }
}
