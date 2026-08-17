package com.shadow.mlbbcheat.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.os.Vibrator;
import android.view.WindowManager;

import com.shadow.mlbbcheat.memory.DataReceiver;
import com.shadow.mlbbcheat.models.PlayerData;
import com.shadow.mlbbcheat.overlay.OverlayView;
import com.shadow.mlbbcheat.overlay.WidgetManager;
import com.shadow.mlbbcheat.utils.BehaviorMimic;
import com.shadow.mlbbcheat.utils.CrashLog;
import com.shadow.mlbbcheat.utils.HoneypotDetector;
import com.shadow.mlbbcheat.utils.bypass.BypassStack;
import com.shadow.mlbbcheat.utils.bypass.EnemyLag;

import java.util.List;

/**
 * Foreground overlay service.
 *
 * Hosts:
 *  - full-screen touch-through ESP view fed by the bridge
 *  - floating Xmisus widget (v3: 5 modules + per-module settings)
 *  - enemy proximity alerts (vibration, gated by a human-like cooldown)
 *  - honeypot detection: when the data stream looks fake, ESP is throttled
 *    to a safe profile instead of painting a target on the user's back
 *  - the app→Lua command driver (lag frames, drone frames)
 */
public class OverlayService extends Service {

    private static final String CHANNEL_ID = "xmisus_overlay";
    private static final long ALERT_MIN_INTERVAL_MS = 900;

    private DataReceiver dataReceiver;
    private WidgetManager widgetManager;
    private OverlayView overlayView;
    private WindowManager windowManager;
    private Vibrator vibrator;
    private BypassStack bypassStack;

    private final HoneypotDetector honeypot = new HoneypotDetector();

    private boolean espEnabled = true;
    private boolean droneEnabled = false;
    private boolean aimEnabled = false;
    private boolean safeMode = false;
    private long lastAlertAt = 0L;
    private boolean stealthMode = false;

    private volatile boolean running = false;
    private volatile Thread lagDriver;

    private int droneZoom = 3000;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            startForeground(1, buildNotification());
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

            bypassStack = BypassStack.getInstance(this);
            bypassStack.onStart();

            overlayView = new OverlayView(this);
            WindowManager.LayoutParams overlayParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            windowManager.addView(overlayView, overlayParams);

            dataReceiver = DataReceiver.getInstance();
            try {
                dataReceiver.start();
            } catch (Exception ignored) {
            }
            dataReceiver.setListener(this::onPlayersUpdated);

            widgetManager = new WidgetManager(this,
                    this::onToggle,
                    this::onSetting);
            widgetManager.show();

            startLagDriver();
        } catch (Throwable t) {
            // Never crash-loop: overlay permission revoked or env issues.
            CrashLog.log("OverlayService start failed: " + t);
            stopSelf();
        }
    }

    // ------------------------------------------------------------------
    // Widget callbacks
    // ------------------------------------------------------------------

    private void onToggle(String feature, boolean enabled) {
        switch (feature) {
            case "esp":
                espEnabled = enabled;
                break;
            case "drone":
                droneEnabled = enabled;
                sendDroneCommand(enabled);
                break;
            case "aim":
                aimEnabled = enabled;
                AutoRetriService svc = AutoRetriService.getInstance();
                if (svc != null) svc.setAimEnabled(enabled);
                break;
            case "safe":
                safeMode = enabled;
                applyStealth();
                break;
            case "lag":
                if (enabled) {
                    bypassStack.enemyLag.start();
                } else {
                    bypassStack.enemyLag.stop();
                }
                break;
            default:
                break;
        }
    }

    private void onSetting(String feature, String key, float value) {
        switch (feature) {
            case "esp":
                if ("distance".equals(key)) {
                    overlayView.setViewDistance(value);
                }
                break;
            case "drone":
                if ("zoom".equals(key)) {
                    droneZoom = Math.round(value);
                    if (droneEnabled) sendDroneCommand(true);
                }
                break;
            case "aim":
                if ("sensitivity".equals(key)) {
                    AutoRetriService svc = AutoRetriService.getInstance();
                    if (svc != null) svc.setAimSensitivity(value);
                }
                break;
            case "lag":
                if ("intensity".equals(key)) {
                    bypassStack.enemyLag.setIntensity(Math.round(value));
                } else if ("mode".equals(key)) {
                    bypassStack.enemyLag.setMode(EnemyLag.Mode.fromInt(Math.round(value)));
                }
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------
    // App → Lua command frames
    // ------------------------------------------------------------------

    /** cmd 4 = DRONE SET (zoom), cmd 5 = DRONE OFF. */
    private void sendDroneCommand(boolean on) {
        byte[] frame = new byte[17];
        frame[0] = EnemyLag.CMD_MARKER;
        frame[1] = on ? (byte) 4 : (byte) 5;
        if (on) {
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(frame)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            b.putFloat(3, droneZoom);
        }
        dataReceiver.sendCommand(frame);
    }

    /** Drive EnemyLag command cadence to the Lua bridge. */
    private void startLagDriver() {
        running = true;
        lagDriver = new Thread(() -> {
            while (running) {
                try {
                    long now = System.currentTimeMillis();
                    EnemyLag lag = bypassStack.enemyLag;
                    if (dataReceiver.bridgeConnected()) {
                        byte[] cmd = lag.nextCommand(now);
                        if (cmd != null) {
                            dataReceiver.sendCommand(cmd);
                            lag.noteDelivered();
                        }
                    } else if (lag.commandDue(now)) {
                        lag.skipCommandSlot(now);
                    }
                    Thread.sleep(BehaviorMimic.idleDelayMs(280, 420));
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable t) {
                    CrashLog.log("lagDriver: " + t);
                }
            }
        }, "lag-driver");
        lagDriver.setDaemon(true);
        lagDriver.start();
    }

    // ------------------------------------------------------------------
    // Stealth handling
    // ------------------------------------------------------------------

    private void applyStealth() {
        if (overlayView == null) return;
        boolean stealth = safeMode || bypassStack.espStealth();
        stealthMode = stealth;
        overlayView.setStealthMode(stealth);
    }

    private void onPlayersUpdated(List<PlayerData> players) {
        if (players == null || players.isEmpty()) return;

        long now = System.currentTimeMillis();
        List<PlayerData> safe = bypassStack.sanitizeEnemies(players, now);
        if (safe == null || safe.isEmpty()) return;

        HoneypotDetector.Verdict verdict = honeypot.assess(safe);
        stealthMode = safeMode || verdict.suspicious || bypassStack.espStealth();

        if (espEnabled && overlayView != null) {
            overlayView.setStealthMode(stealthMode);
            overlayView.setEnemies(safe);
        }

        if (vibrator != null && !stealthMode && bypassStack.allowVibrate()) {
            long now2 = System.currentTimeMillis();
            if (now2 - lastAlertAt >= ALERT_MIN_INTERVAL_MS) {
                for (PlayerData p : safe) {
                    if (p.isEnemy && p.isAlive()
                            && p.distanceTo(0f, 0f) < 200f) {
                        vibrator.vibrate(BehaviorMimic.idleDelayMs(120, 180));
                        lastAlertAt = now2;
                        break;
                    }
                }
            }
        }
    }

    private Notification buildNotification() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Xmisus overlay", NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(channel);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Xmisus")
                .setContentText("Overlay active")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (lagDriver != null) lagDriver.interrupt();
        try {
            if (widgetManager != null) widgetManager.hide();
            if (overlayView != null && overlayView.getParent() != null
                    && windowManager != null) {
                windowManager.removeView(overlayView);
            }
            if (dataReceiver != null) dataReceiver.stop();
        } catch (Throwable t) {
            CrashLog.log("OverlayService.onDestroy: " + t);
        }
        super.onDestroy();
    }
}
