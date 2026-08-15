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
 * Accessibility automation: auto-retribution + aim-assist.
 *
 * Retribution (jungle smite) timing:
 *   - reads the target's HP from the bridge data
 *   - if HP is inside kill range for the current level, taps the retri
 *     button with human jitter + reaction delay
 *
 * Aim assist:
 *   - when a widget toggle enables it, every poll picks the best target via
 *     {@link AimEngine.selectTarget} and drags the skill joystick toward the
 *     projected screen point with leading + human error.
 *
 * Everything is dispatched through accessibility gestures so no root and no
 * injected input manager is needed, and every dispatch is randomized.
 */
public class AutoRetriService extends AccessibilityService {

    // Retribution button (bottom-center skill slot), landscape MLBB layout
    private static final float RETRI_BTN_X = 900f;
    private static final float RETRI_BTN_Y = 1800f;
    private static final float RETRI_TAP_RANGE_PX = 14f;

    // Skill aim joystick origin + radius (drag vector space)
    private static final float SKILL_STICK_X = 150f;
    private static final float SKILL_STICK_Y = 1800f;
    private static final float SKILL_STICK_RADIUS_PX = 110f;

    // Retribution damage model: base + per-level scaling
    private static final float RETRI_BASE_DAMAGE = 500f;
    private static final float RETRI_LEVEL_SCALE = 50f;
    private static final float RETRI_SAFETY_MARGIN = 1.1f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean aimEnabled = false;
    private long lastAimAt = 0L;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        DataReceiver receiver = DataReceiver.getInstance();
        List<PlayerData> players = receiver.getPlayers();
        if (players == null || players.isEmpty()) return;

        float playerLevel = receiver.getPlayerLevel();

        // --- Retribution -------------------------------------------------
        PlayerData retriTarget = findRetriTarget(players);
        if (retriTarget != null && shouldUseRetribution(retriTarget.hp, Math.round(playerLevel))) {
            scheduleRetriTap();
        }

        // --- Aim assist --------------------------------------------------
        if (aimEnabled && System.currentTimeMillis() - lastAimAt > 220L) {
            scheduleAimDrag(players);
        }
    }

    @Override
    public void onInterrupt() {
    }

    // ------------------------------------------------------------------
    // Retribution
    // ------------------------------------------------------------------

    private PlayerData findRetriTarget(List<PlayerData> players) {
        for (PlayerData p : players) {
            if (p.isEnemy && p.isAlive()
                    && p.distanceTo(0f, 0f) < 450f) {
                return p;
            }
        }
        return null;
    }

    static float retriDamageForLevel(int level) {
        return RETRI_BASE_DAMAGE + level * RETRI_LEVEL_SCALE;
    }

    static boolean shouldUseRetribution(float targetHp, int level) {
        return targetHp > 0f
                && targetHp <= retriDamageForLevel(level) * RETRI_SAFETY_MARGIN;
    }

    private void scheduleRetriTap() {
        if (BehaviorMimic.decidesToSkip()) return;
        long delay = BehaviorMimic.reactionDelayMs();
        final float x = RETRI_BTN_X + BehaviorMimic.tapJitterPx(RETRI_TAP_RANGE_PX);
        final float y = RETRI_BTN_Y + BehaviorMimic.tapJitterPx(RETRI_TAP_RANGE_PX);
        handler.postDelayed(() -> dispatchTap(x, y, 60), delay);
    }

    // ------------------------------------------------------------------
    // Aim assist
    // ------------------------------------------------------------------

    private void scheduleAimDrag(List<PlayerData> players) {
        if (BehaviorMimic.decidesToSkip()) return;
        AimEngine.Target target = AimEngine.selectTarget(
                players, 0f, 0f, 2.0f,
                retriDamageForLevel(Math.round(DataReceiver.getInstance().getPlayerLevel())),
                AimEngine.MAX_AIM_RANGE_WORLD);
        if (target == null) return;

        float[] drag = AimEngine.skillDragVector(
                SKILL_STICK_X, SKILL_STICK_Y,
                target.screenX, target.screenY,
                SKILL_STICK_RADIUS_PX);
        long delay = BehaviorMimic.reactionDelayMs() + BehaviorMimic.hesitationMs();

        final float fromX = SKILL_STICK_X;
        final float fromY = SKILL_STICK_Y;
        final float toX = fromX + drag[0];
        final float toY = fromY + drag[1];
        lastAimAt = System.currentTimeMillis();

        handler.postDelayed(() -> dispatchDrag(fromX, fromY, toX, toY, 90), delay);
    }

    // ------------------------------------------------------------------
    // Gesture dispatch
    // ------------------------------------------------------------------

    private void dispatchTap(float x, float y, long durationMs) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs))
                .build();
        dispatchGesture(gesture, null, null);
    }

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