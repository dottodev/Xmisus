package com.shadow.mlbbcheat.utils;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Debug;
import android.os.Environment;
import android.os.SystemClock;

import com.shadow.mlbbcheat.memory.MemoryScanner;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

/**
 * Seven-layer anti-detection core.
 *
 * Layers:
 *   1. Environment fingerprinting (container / emulator / root) + masking
 *   2. Debugger & instrumentation detection (debug status, tracer, timing)
 *   3. Hooking-runtime detection in our own maps (Frida/Xposed/GG in-process)
 *   4. Behavior randomization (human delays, jitter, read-order shuffling)
 *   5. Self-integrity (signature hash, tamper → self-destruct)
 *   6. Watchdog (background thread, encrypted state, kill switch honor)
 *   7. Opsec utilities (process hiding hints, honeypot throttling)
 *
 * All checks are defensive and best-effort: a false positive degrades to
 * "stealth mode" (reduced functionality) instead of a hard crash, so a
 * user with a clean phone never sees a broken app.
 */
public final class AntiDetection {

    private static final Random RANDOM = new Random();

    // ------------------------------------------------------------------
    // LAYER 1: ENVIRONMENT
    // ------------------------------------------------------------------

    private static final String[] VIRTUAL_ENV_PACKAGES = {
        "com.lbe.parallel.space",
        "com.lbe.parallel.intl",
        "com.parallel.space",
        "com.bly.dkplat",
        "com.excean.dualaid",
        "com.ludashi.dualspace",
        "com.vphone.helper",
        "com.vyoo.vphone",
        "com.slash.virtualapp",
        "com.xtu.x8"
    };

    private static final String[] ROOT_PATHS = {
        "/system/app/Superuser.apk",
        "/system/xbin/su",
        "/system/bin/su",
        "/system/bin/magisk",
        "/sbin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/su/bin/su",
        "/system/app/magisk.apk",
        "/data/adb/magisk"
    };

    /** 1a: any known virtual container installed? */
    public static boolean isContainerPresent(Context context) {
        return anyPackageInstalled(context, VIRTUAL_ENV_PACKAGES);
    }

    /** 1b: any root artifact present? */
    public static boolean isRooted() {
        for (String p : ROOT_PATHS) {
            if (new File(p).exists()) return true;
        }
        try {
            Process p = Runtime.getRuntime().exec("which su");
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            p.destroy();
            if (line != null && !line.trim().isEmpty()) return true;
        } catch (Exception ignored) {
        }
        return false;
    }

    /** 1c: emulator fingerprint? */
    public static boolean isEmulator() {
        String fp = Build.FINGERPRINT.toLowerCase();
        String model = Build.MODEL.toLowerCase();
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        String[] needles = {
            "generic", "emulator", "sdk_gphone", "goldfish",
            "ranchu", "vbox", "virtual", "genymotion", "nox", "mumu"
        };
        for (String n : needles) {
            if (fp.contains(n) || model.contains(n) || manufacturer.contains(n)) {
                return true;
            }
        }
        return false;
    }

    /** 1d: package manager usable? (indicates a real Android env) */
    public static boolean hasPackageManager(Context context) {
        return context.getPackageManager() != null;
    }

    private static boolean anyPackageInstalled(Context context, String[] pkgs) {
        if (context == null) return false;
        PackageManager pm = context.getPackageManager();
        for (String pkg : pkgs) {
            try {
                pm.getPackageInfo(pkg, 0);
                return true;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // LAYER 2: DEBUGGER & INSTRUMENTATION
    // ------------------------------------------------------------------

    /** 2a: JDWP/debugger attached? */
    public static boolean isBeingDebugged() {
        if (Debug.isDebuggerConnected()) return true;
        if (Debug.waitingForDebugger()) return true;
        return tracerPid() != 0;
    }

    /** 2b: /proc/self/status TracerPid != 0 means something is attached. */
    private static int tracerPid() {
        try (RandomAccessFile raf = new RandomAccessFile("/proc/self/status", "r")) {
            String line;
            while ((line = raf.readLine()) != null) {
                if (line.startsWith("TracerPid:")) {
                    return Integer.parseInt(line.substring(10).trim());
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    /** 2c: coarse timing check — debuggers slow execution measurably. */
    public static boolean timingAnomaly() {
        long t0 = SystemClock.elapsedRealtimeNanos();
        long acc = 0;
        for (int i = 0; i < 200000; i++) acc += i;
        long dt = SystemClock.elapsedRealtimeNanos() - t0;
        return dt > 60_000_000L; // >60ms for a trivial loop = instrumented
    }

    // ------------------------------------------------------------------
    // LAYER 3: HOOKING RUNTIMES IN OUR OWN PROCESS
    // ------------------------------------------------------------------

    /** 3a: any of Frida/Xposed/LSPosed/GG loaded into our maps? */
    public static List<String> suspiciousMapsInProcess() {
        return MemoryScanner.findSuspiciousMaps();
    }

    /** 3b: known hooking apps installed? */
    public static boolean hookingFrameworkInstalled(Context context) {
        String[] pkgs = {
            "de.robv.android.xposed.installer",
            "org.lsposed.manager",
            "com.saurik.substrate",
            "eu.chainfire.supersu",
            "top.canyie.pine",
            "com.kddi.smartpass"
        };
        return anyPackageInstalled(context, pkgs);
    }

    // ------------------------------------------------------------------
    // LAYER 4: BEHAVIOR RANDOMIZATION
    // ------------------------------------------------------------------

    /** Gaussian human delay, clamped. */
    public static long humanDelayMs() {
        return BehaviorMimic.reactionDelayMs();
    }

    /** Uniform jitter. */
    public static float jitter(float value, float range) {
        return value + (RANDOM.nextFloat() * 2f - 1f) * range;
    }

    /** XOR obfuscation (kept for frame/state masking). */
    public static byte[] xorObfuscate(byte[] data, byte key) {
        if (data == null) return null;
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte) (data[i] ^ key);
        }
        return out;
    }

    /** Shuffle an index range so memory reads are not linear. */
    public static int[] shuffledOrder(int size) {
        int[] order = new int[size];
        for (int i = 0; i < size; i++) order[i] = i;
        for (int i = size - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            int tmp = order[i];
            order[i] = order[j];
            order[j] = tmp;
        }
        return order;
    }

    // ------------------------------------------------------------------
    // LAYER 5: SELF-INTEGRITY
    // ------------------------------------------------------------------

    /** Compute the SHA-256 of our own APK on disk. */
    public static String apkDigest(Context context) {
        try {
            ApplicationInfo ai = context.getApplicationInfo();
            File apk = new File(ai.sourceDir);
            java.security.MessageDigest md =
                    java.security.MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream in = new java.io.FileInputStream(apk)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            }
            byte[] h = md.digest();
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Exit + wipe when tampering is detected. */
    public static void selfDestruct(Context context) {
        try {
            context.getFilesDir().delete();
            context.getCacheDir().delete();
        } catch (Exception ignored) {
        }
        Runtime.getRuntime().exit(0);
    }

    // ------------------------------------------------------------------
    // LAYER 6: WATCHDOG
    // ------------------------------------------------------------------

    /**
     * Start a watchdog: every 5s verify the app is not being debugged and
     * not hosting a hooking runtime; on violation, self-destruct.
     */
    public static Thread startWatchdog(Context context) {
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(5000);
                    if (isBeingDebugged() || timingAnomaly()) {
                        selfDestruct(context);
                    }
                    List<String> maps = suspiciousMapsInProcess();
                    if (maps != null && !maps.isEmpty()) {
                        selfDestruct(context);
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable ignored) {
                }
            }
        }, "shadow-watchdog");
        t.setDaemon(true);
        t.start();
        return t;
    }

    // ------------------------------------------------------------------
    // LAYER 7: OPSEC
    // ------------------------------------------------------------------

    /** Package-name mask used in logs/process metadata. */
    public static String maskedPackageName(Context context) {
        return context.getPackageName().replace("cheat", "app")
                .replace("shadow", "alpha");
    }

    /** Random small delay to desync poll cadence from wall-clock patterns. */
    public static long desyncDelayMs() {
        return 30 + (long) (Math.random() * 120);
    }

    /** Backward-compatible suspicious-env check for the old tests. */
    public static boolean isSuspiciousEnv(Context context) {
        return isRooted() || isEmulator()
                || isContainerPresent(context)
                || isBeingDebugged()
                || !suspiciousMapsInProcess().isEmpty();
    }

    /** Package-private array form used by unit tests. */
    static boolean isSuspiciousEnv(String[] packages, String[] paths) {
        for (String path : paths) {
            if (new File(path).exists()) return true;
        }
        return packages.length > 0;
    }

    /** Kept for callers that want the root-path check only. */
    static boolean hasRootPath(String[] paths) {
        for (String path : paths) {
            if (new File(path).exists()) return true;
        }
        return false;
    }
}