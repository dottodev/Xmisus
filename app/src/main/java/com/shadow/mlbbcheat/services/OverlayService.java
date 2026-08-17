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
import com.shadow.mlbbcheat.utils.HoneypotDetector;
import com.shadow.mlbbcheat.utils.bypass.BypassStack;

import java.util.List;

/**
 * Foreground overlay service.
 *
 * Hosts:
 *  - full-screen touch-through ESP view fed by the bridge
 *  - floating toggle widget
 *  - enemy proximity alerts (vibration, gated by a human-like cooldown)
 *  - honeypot detection: when the data stream looks fake, ESP is throttled
 *    to a safe profile instead of painting a target on the user's back.
 */
public class OverlayService extends Service {

    private static final String CHANNEL_ID = "mlbb_cheat_overlay";
    private static final long ALERT_MIN_INTERVAL_MS = 900;

    private DataReceiver dataReceiver;
    private WidgetManager widgetManager;
    private OverlayView overlayView;
    private WindowManager windowManager;
    private Vibrator vibrator;

    private final HoneypotDetector honeypot = new HoneypotDetector();
    private BypassStack bypassStack;

    private boolean espEnabled = true;
    private boolean droneEnabled = false;
    private boolean aimEnabled = false;
    private long lastAlertAt = 0L;
    private boolean stealthMode = false;

    @Override
    public void onCreate() {
        super.onCreate();
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

        widgetManager = new WidgetManager(this, this::onToggle);
        widgetManager.show();
    }

    private void onToggle(String feature, boolean enabled) {
        if ("esp".equals(feature)) {
            espEnabled = enabled;
        } else if ("drone".equals(feature)) {
            droneEnabled = enabled;
        } else if ("aim".equals(feature)) {
            aimEnabled = enabled;
            AutoRetriService svc = AutoRetriService.getInstance();
            if (svc != null) svc.setAimEnabled(enabled);
        }
    }

    private void onPlayersUpdated(List<PlayerData> players) {
        if (players == null || players.isEmpty()) return;

        long now = System.currentTimeMillis();
        List<PlayerData> safe = bypassStack.sanitizeEnemies(players, now);
        if (safe == null || safe.isEmpty()) return;

        HoneypotDetector.Verdict verdict = honeypot.assess(safe);
        stealthMode = verdict.suspicious || bypassStack.espStealth();

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
                CHANNEL_ID, "Overlay", NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(channel);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("MLBB Cheat")
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
        if (widgetManager != null) widgetManager.hide();
        if (overlayView != null && overlayView.getParent() != null) {
            windowManager.removeView(overlayView);
        }
        if (dataReceiver != null) dataReceiver.stop();
        super.onDestroy();
    }
}