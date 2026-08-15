package com.shadow.mlbbcheat.utils;

import java.util.Random;

public final class AntiDetection {

    private static final Random RANDOM = new Random();
    private static final long DELAY_MEAN_MS = 100;
    private static final long DELAY_SD_MS = 50;
    private static final long DELAY_MIN_MS = 50;
    private static final long DELAY_MAX_MS = 200;

    private static final String[] VIRTUAL_ENV_PACKAGES = {
        "com.lbe.parallel.space",
        "com.parallel.space",
        "com.bly.dkplat",
        "com.excean.dualaid",
        "com.ludashi.dualspace"
    };

    private static final String[] ROOT_PATHS = {
        "/system/app/Superuser.apk",
        "/system/xbin/su",
        "/system/bin/su",
        "/system/bin/magisk"
    };

    private AntiDetection() {}

    public static long humanDelayMs() {
        double sample = RANDOM.nextGaussian() * DELAY_SD_MS + DELAY_MEAN_MS;
        return Math.max(DELAY_MIN_MS, Math.min(DELAY_MAX_MS, (long) sample));
    }

    public static float jitter(float value, float range) {
        float delta = (RANDOM.nextFloat() * 2f - 1f) * range;
        return value + delta;
    }

    public static byte[] xorObfuscate(byte[] data, byte key) {
        if (data == null) return null;
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte) (data[i] ^ key);
        }
        return out;
    }

    public static boolean isSuspiciousEnv() {
        return isSuspiciousEnv(VIRTUAL_ENV_PACKAGES, ROOT_PATHS);
    }

    static boolean isSuspiciousEnv(String[] packages, String[] paths) {
        android.content.pm.PackageManager pm = null;
        try {
            pm = android.app.ActivityThread.currentApplication()
                    .getPackageManager();
        } catch (Throwable ignored) {
        }
        if (pm != null) {
            for (String pkg : packages) {
                try {
                    pm.getPackageInfo(pkg, 0);
                    return true;
                } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
                }
            }
        }
        for (String path : paths) {
            if (new java.io.File(path).exists()) return true;
        }
        return false;
    }
}
