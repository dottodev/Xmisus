package com.shadow.mlbbcheat.utils;

import static org.junit.Assert.*;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

@RunWith(RobolectricTestRunner.class)
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
        Context context = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(context.getPackageManager())
                .addPackage("com.lbe.parallel.space");
        assertTrue(AntiDetection.isSuspiciousEnv(context));
    }

    @Test
    public void isSuspiciousEnv_cleanWhenNothingDetected() {
        Context context = RuntimeEnvironment.getApplication();
        assertFalse(AntiDetection.isSuspiciousEnv(context));
    }
}