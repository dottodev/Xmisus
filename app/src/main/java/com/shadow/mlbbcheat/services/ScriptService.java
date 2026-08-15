package com.shadow.mlbbcheat.services;

import android.app.ActivityManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import com.shadow.mlbbcheat.memory.OffsetRepository;
import com.shadow.mlbbcheat.net.ServerClient;
import com.shadow.mlbbcheat.utils.AntiDetection;
import com.shadow.mlbbcheat.utils.BehaviorMimic;
import com.shadow.mlbbcheat.utils.Crypto;

import java.io.IOException;
import java.util.List;

/**
 * Orchestration service.
 *
 * Responsibilities:
 *  - watch for the MLBB process and keep the bridge warm
 *  - periodic encrypted heartbeat → remote config (offset DB hot-update)
 *  - license validation with offline grace
 *  - watchdog (anti-debug / anti-hook self-destruct) while active
 */
public class ScriptService extends Service {

    private volatile boolean running = true;
    private volatile Thread watcher;
    private volatile Thread heartbeatThread;
    private volatile Thread watchdog;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startWatchdogIfNeeded();

        watcher = new Thread(this::watchLoop, "script-watcher");
        watcher.setDaemon(true);
        watcher.start();

        heartbeatThread = new Thread(this::heartbeatLoop, "heartbeat");
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();

        return START_STICKY;
    }

    private void watchLoop() {
        while (running) {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> procs =
                    am.getRunningAppProcesses();
            String mlbb = detectMlbbProcess(procs);
            if (mlbb != null) {
                // MLBB is up — bridge is live; keep service resident
                try {
                    Thread.sleep(BehaviorMimic.idleDelayMs(3000, 5000));
                } catch (InterruptedException e) {
                    return;
                }
                continue;
            }
            try {
                Thread.sleep(BehaviorMimic.idleDelayMs(1500, 2500));
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void heartbeatLoop() {
        Crypto crypto = new Crypto(ServerClient.deviceId(this).getBytes());
        ServerClient client = new ServerClient(this, crypto);

        while (running) {
            try {
                String fp = OffsetRepository.fingerprint(this);
                ServerClient.HeartbeatResult r = client.heartbeat("1.0", fp);
                if (r != null && r.offsetDbJson != null) {
                    new OffsetRepository(this)
                            .applyServerUpdate(this, r.offsetDbJson);
                }
                if (r != null && r.killSwitch) {
                    stopCheatStack();
                    return;
                }
            } catch (IOException | RuntimeException ignored) {
            }
            try {
                Thread.sleep(BehaviorMimic.idleDelayMs(15000, 30000));
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void stopCheatStack() {
        running = false;
        stopService(new Intent(this, OverlayService.class));
        stopSelf();
    }

    private void startWatchdogIfNeeded() {
        if (watchdog == null || !watchdog.isAlive()) {
            watchdog = AntiDetection.startWatchdog(this);
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
        if (watcher != null) watcher.interrupt();
        if (heartbeatThread != null) heartbeatThread.interrupt();
        super.onDestroy();
    }
}