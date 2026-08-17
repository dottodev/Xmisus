package com.shadow.mlbbcheat.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

import com.shadow.mlbbcheat.aim.AimEngine;
import com.shadow.mlbbcheat.memory.DataReceiver;
import com.shadow.mlbbcheat.models.PlayerData;
import com.shadow.mlbbcheat.utils.BehaviorMimic;

import java.util.List;

/**
 * Accessibility automation: aim assist only (auto-retribution removed).
 *
 * Aim assist:
 *   - when the widget toggle enables it, every poll picks the best target
 *     via {@link AimEngine.selectTarget} and drags the skill joystick
 *     toward the projected screen point with leading + human error.
 *
 * Everything is dispatched through accessibility gestures so no root and no
 * injected input manager is needed, and every dispatch is randomized.
 *
 * NOTE: this service is NOT requested at launch anymore; it only runs if
 * the user enables the accessibility permission manually.
 */
public class AutoRetriService extends AccessibilityService {

    // Skill aim joystick origin + radius (drag vector space)
    private static final float SKILL_STICK_X = 150f;
    private static final float SKILL_STICK_Y = 1800f;
    private static final float SKILL_STICK_RADIUS_PX = 110f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean aimEnabled = false;
    private volatile float aimSensitivity = 1.0f;
    private long lastAimAt = 0L;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            DataReceiver receiver = DataReceiver.getInstance();
            List<PlayerData> players = receiver.getPlayers();
            if (players == null || players.isEmpty()) return;

            if (aimEnabled && System.currentTimeMillis() - lastAimAt > 220L) {
                scheduleAimDrag(players);
            }
        } catch (Throwable t) {
            com.shadow.mlbbcheat.utils.CrashLog.log("AutoRetriService.onEvent: " + t);
        }
    }

    @Override
    public void onInterrupt() {
    }

    // ------------------------------------------------------------------
    // Aim assist
    // ------------------------------------------------------------------

    private void scheduleAimDrag(List<PlayerData> players) {
        if (BehaviorMimic.decidesToSkip()) return;
        AimEngine.Target target = AimEngine.selectTarget(
                players, 0f, 0f, 2.0f,
                0f,
                AimEngine.MAX_AIM_RANGE_WORLD);
        if (target == null) return;

        float[] drag = AimEngine.skillDragVector(
                SKILL_STICK_X, SKILL_STICK_Y,
                target.screenX, target.screenY,
                SKILL_STICK_RADIUS_PX);
        long delay = BehaviorMimic.reactionDelayMs() + BehaviorMimic.hesitationMs();

        final float fromX = SKILL_STICK_X;
        final float fromY = SKILL_STICK_Y;
        final float toX = fromX + drag[0] * aimSensitivity;
        final float toY = fromY + drag[1] * aimSensitivity;
        lastAimAt = System.currentTimeMillis();

        handler.postDelayed(() -> dispatchDrag(fromX, fromY, toX, toY, 90), delay);
    }

    // ------------------------------------------------------------------
    // Gesture dispatch
    // ------------------------------------------------------------------

    private void dispatchDrag(float fromX, float fromY, float toX, float toY, long durationMs) {
        Path path = new Path();
        path.moveTo(fromX, fromY);
        path.lineTo(toX, toY);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs))
                .build();
        dispatchGesture(gesture, null, null);
    }

    // ------------------------------------------------------------------
    // External controls (from the floating widget / service)
    // ------------------------------------------------------------------

    public void setAimEnabled(boolean enabled) {
        this.aimEnabled = enabled;
    }

    /** Drag-distance multiplier (settings slider, 0.5x–2.0x). */
    public void setAimSensitivity(float sensitivity) {
        this.aimSensitivity = Math.max(0.3f, Math.min(3f, sensitivity));
    }

    public float aimSensitivity() {
        return aimSensitivity;
    }

    public static AutoRetriService getInstance() {
        return instance;
    }

    private static AutoRetriService instance;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }
}
