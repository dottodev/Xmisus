package com.shadow.mlbbcheat.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

import com.shadow.mlbbcheat.memory.DataReceiver;
import com.shadow.mlbbcheat.utils.AntiDetection;

public class AutoRetriService extends AccessibilityService {

    private static final float RETRI_BTN_X = 900f;
    private static final float RETRI_BTN_Y = 1800f;
    private static final float BASE_DAMAGE = 500f;
    private static final float LEVEL_SCALE = 50f;
    private static final float SAFETY_MARGIN = 1.1f;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        DataReceiver receiver = DataReceiver.getInstance();

        float targetHp = 0f;
        for (com.shadow.mlbbcheat.models.PlayerData p : receiver.getPlayers()) {
            if (p.isEnemy && p.isAlive() && p.distanceTo(0f, 0f) < 500f) {
                targetHp = p.hp;
                break;
            }
        }
        int level = Math.round(receiver.getPlayerLevel());

        if (shouldUseRetribution(targetHp, level)) {
            scheduleTap();
        }
    }

    @Override
    public void onInterrupt() {
    }

    static float retriDamageForLevel(int level) {
        return BASE_DAMAGE + level * LEVEL_SCALE;
    }

    static boolean shouldUseRetribution(float targetHp, int level) {
        return targetHp > 0f && targetHp <= retriDamageForLevel(level) * SAFETY_MARGIN;
    }

    private void scheduleTap() {
        long delay = AntiDetection.humanDelayMs();
        final float x = AntiDetection.jitter(RETRI_BTN_X, 12f);
        final float y = AntiDetection.jitter(RETRI_BTN_Y, 12f);

        handler.postDelayed(() -> {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 60))
                    .build();
            dispatchGesture(gesture, null, null);
        }, delay);
    }
}
