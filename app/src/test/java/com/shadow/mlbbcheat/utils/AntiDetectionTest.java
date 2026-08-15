package com.shadow.mlbbcheat.utils;

import static org.junit.Assert.*;

import org.junit.Test;

public class AntiDetectionTest {

    @Test
    public void humanDelayMs_isWithinClamp() {
        for (int i = 0; i < 1000; i++) {
            long d = AntiDetection.humanDelayMs();
            assertTrue("delay " + d + " below 50", d >= 50);
            assertTrue("delay " + d + " above 200", d <= 200);
        }
    }

    @Test
    public void jitter_returnsValueWithinRange() {
        for (int i = 0; i < 1000; i++) {
            float v = AntiDetection.jitter(100f, 5f);
            assertTrue(v >= 95f && v <= 105f);
        }
    }

    @Test
    public void xorObfuscate_roundTrips() {
        byte[] original = {1, 2, 3, 4, 5};
        byte key = 0x5A;
        byte[] obfuscated = AntiDetection.xorObfuscate(original, key);
        byte[] restored = AntiDetection.xorObfuscate(obfuscated, key);
        assertArrayEquals(original, restored);
        assertFalse(java.util.Arrays.equals(original, obfuscated));
    }

    @Test
    public void isSuspiciousEnv_detectsParallelSpacePackages() {
        assertTrue(AntiDetection.isSuspiciousEnv(
            new String[]{"com.lbe.parallel.space"}, new String[]{}));
    }

    @Test
    public void isSuspiciousEnv_detectsRootPaths() {
        assertTrue(AntiDetection.isSuspiciousEnv(
            new String[]{}, new String[]{"/system/xbin/su"}));
    }

    @Test
    public void isSuspiciousEnv_cleanWhenNothingDetected() {
        assertFalse(AntiDetection.isSuspiciousEnv(
            new String[]{}, new String[]{}));
    }
}
