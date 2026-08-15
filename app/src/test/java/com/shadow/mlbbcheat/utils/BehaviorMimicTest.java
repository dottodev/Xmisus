package com.shadow.mlbbcheat.utils;

import static org.junit.Assert.*;

import org.junit.Test;

public class BehaviorMimicTest {

    @Test
    public void reactionDelay_isWithinHumanBand() {
        for (int i = 0; i < 5000; i++) {
            long d = BehaviorMimic.reactionDelayMs();
            assertTrue("delay " + d, d >= 90 && d <= 420);
        }
    }

    @Test
    public void aimError_isCapped() {
        for (int i = 0; i < 5000; i++) {
            float e = BehaviorMimic.aimErrorPx(2000f);
            assertTrue("error " + e, e >= -22f && e <= 22f);
        }
    }

    @Test
    public void skipRate_isBelowTenPercent() {
        int skips = 0;
        int n = 10000;
        for (int i = 0; i < n; i++) {
            if (BehaviorMimic.decidesToSkip()) skips++;
        }
        float rate = (float) skips / n;
        assertTrue("skip rate " + rate, rate < 0.10f);
    }

    @Test
    public void interpolation_isInRange() {
        for (int i = 0; i < 1000; i++) {
            float f = BehaviorMimic.interpolationFactor();
            assertTrue(f >= 0.25f && f <= 0.8f);
        }
    }
}
