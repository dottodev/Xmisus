package com.shadow.mlbbcheat.utils;

import java.util.Random;

/**
 * Produces input distributions that look human.
 *
 * Anti-cheat engines model legitimate players: reaction times follow a
 * roughly Gaussian distribution with a floor, aim error grows with distance,
 * and players occasionally miss or hesitate. Every automation decision in
 * the app routes through this class so the whole signal envelope stays
 * inside the "human band".
 */
public final class BehaviorMimic {

    private static final Random RANDOM = new Random();

    // Reaction-time envelope (ms)
    private static final long REACTION_MEAN_MS = 180;
    private static final long REACTION_SD_MS = 60;
    private static final long REACTION_MIN_MS = 90;
    private static final long REACTION_MAX_MS = 420;

    // Flick-aim envelope
    private static final float AIM_SD_PX = 6f;
    private static final float AIM_MAX_PX = 22f;

    private BehaviorMimic() {}

    /** Human reaction time before acting on a state change. */
    public static long reactionDelayMs() {
        return clamp(gaussian(REACTION_MEAN_MS, REACTION_SD_MS),
                REACTION_MIN_MS, REACTION_MAX_MS);
    }

    /** Randomized idle between poll cycles. */
    public static long idleDelayMs(int min, int max) {
        return min + (long) (RANDOM.nextDouble() * (max - min));
    }

    /**
     * Add human aim error. Error grows with target distance (tremor) and is
     * capped so we never visibly whip the reticle off target.
     */
    public static float aimErrorPx(float distancePx) {
        double sd = AIM_SD_PX + (distancePx / 1200f) * AIM_SD_PX;
        float e = (float) (RANDOM.nextGaussian() * sd);
        return clamp(e, -AIM_MAX_PX, AIM_MAX_PX);
    }

    /** Small uniform jitter for tap positions (button presses). */
    public static float tapJitterPx(float range) {
        return (RANDOM.nextFloat() * 2f - 1f) * range;
    }

    /** 2-6% chance the automation "decides" to pass on an action. */
    public static boolean decidesToSkip() {
        return RANDOM.nextFloat() < 0.04f;
    }

    /** Smooth interpolation factor between frames (0..1), noisy on purpose. */
    public static float interpolationFactor() {
        return clamp(0.5f + (float) RANDOM.nextGaussian() * 0.08f, 0.25f, 0.8f);
    }

    /** Occasionally produce a micro-delay mid-action (hesitation). */
    public static long hesitationMs() {
        if (RANDOM.nextFloat() < 0.12f) {
            return 60 + (long) (RANDOM.nextDouble() * 140);
        }
        return 0;
    }

    // ------------------------------------------------------------------

    private static long gaussian(long mean, long sd) {
        return Math.round(RANDOM.nextGaussian() * sd + mean);
    }

    private static long clamp(long v, long lo, long hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
