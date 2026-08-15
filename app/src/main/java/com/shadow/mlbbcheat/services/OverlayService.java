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

import com.shadow.mlbbcheat.models.PlayerData;
import com.shadow.mlbbcheat.overlay.OverlayView;
import com.shadow.mlbbcheat.overlay.WidgetManager;
import com.shadow.mlbbcheat.memory.DataReceiver;

import java.util.List;

public class OverlayService extends Service {

    private static final String CHANNEL_ID = "mlbb_cheat_overlay";

    private DataReceiver dataReceiver;
    private WidgetManager widgetManager;
    private OverlayView overlayView;
    private WindowManager windowManager;
    private Vibrator vibrator;

    private boolean espEnabled = true;
    private boolean droneEnabled = false;
    private boolean aimEnabled = false;

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(1, buildNotification());
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

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
        if ("esp".equals(feature)) espEnabled = enabled;
        if ("drone".equals(feature)) droneEnabled = enabled;
        if ("aim".equals(feature)) aimEnabled = enabled;
    }

    private void onPlayersUpdated(List<PlayerData> players) {
        if (espEnabled && overlayView != null) {
            overlayView.setEnemies(players);
        }
        if (vibrator != null) {
            for (PlayerData p : players) {
                if (p.isEnemy && p.isAlive() && p.distanceTo(0f, 0f) < 200f) {
                    vibrator.vibrate(150);
                    break;
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
