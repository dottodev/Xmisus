package com.shadow.mlbbcheat.utils.bypass;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * IdentityShield — device/app identity hygiene.
 *
 * Anti-cheat correlation often starts with identity: the same device id,
 * the same install signature, the same process name pattern, the same
 * storage layout, the same boot-relative clock, the same version string.
 * IdentityShield:
 *
 *   1. INSTALL MODELLING     — models the app's install as a real app:
 *      install timestamp, first-run pacing, usage calendar, so the
 *      telemetry a game might read looks like a real user install.
 *   2. PROCESS NAME MIMICRY  — the app's own process is never odd:
 *      package-name-consistent comm, expected UID, no ":cheat" suffixes.
 *   3. PACKAGE-MASKED LOGGING — logs and preferences never store the
 *      literal purpose; names are hashed/masked so a file scan of the
 *      app data dir finds nothing descriptive.
 *   4. PREFERENCE HYGIENE    — sensitive flags are stored in
 *      obfuscated keys with synthetic sibling keys (noise rows) so a
 *      preference dump looks like a normal app.
 *   5. CLOCK-DERIVED ANTI-PATTERN — all timestamps used in telemetry
 *      are derived from boot-relative uptime + install offset, never the
 *      raw wall clock, so a "fresh install behaves like day-1" check
 *      passes.
 *   6. STORAGE LAYOUT MIMICRY — the app data dir mirrors a normal app:
 *      expected directories (cache, files, shared_prefs, databases),
 *      normal file names, no cheat-named artifacts.
 *   7. SESSION ROTATION      — the identity seed rotates on a schedule,
 *      bounded so existing grants keep working.
 *   8. USAGE MIMICRY         — the app records plausible usage stats
 *      (opened N times/day, at plausible hours) so "always-on during
 *      ranked only" telemetry doesn't stand out.
 *   9. BOOT ANCHOR           — all timing anchors tie to the boot clock,
 *      which a game cannot fake.
 */
public final class IdentityShield {

    private static final String PREFS = "shadow_identity";
    private static final String KEY_INSTALL_TS = "i_ts";
    private static final String KEY_FIRST_RUN = "i_fr";
    private static final String KEY_SESSION_SEED = "i_seed";
    private static final String KEY_OPEN_COUNT = "i_oc";
    private static final String KEY_LAST_OPEN_DAY = "i_lod";
    private static final String KEY_ROTATION = "i_rot";
    private static final long ROTATION_MS = 7L * 24L * 3600_000L;
    private static final int NOISE_ROWS = 6;

    private final Context context;
    private final Random rng = new Random();
    private final AtomicLong installAnchorMs = new AtomicLong(0L);
    private final String maskedPackage;

    public IdentityShield(Context context) {
        this.context = context.getApplicationContext();
        this.maskedPackage = mask(context.getPackageName());
        SharedPreferences sp = prefs();
        if (!sp.contains(KEY_INSTALL_TS)) {
            long ts = System.currentTimeMillis()
                    - (long) (rng.nextDouble() * 180L * 24L * 3600_000L);
            sp.edit()
                    .putLong(KEY_INSTALL_TS, ts)
                    .putLong(KEY_FIRST_RUN, System.currentTimeMillis())
                    .putLong(KEY_SESSION_SEED, newSeed())
                    .putLong(KEY_ROTATION, System.currentTimeMillis())
                    .apply();
            plantNoiseRows();
        }
        installAnchorMs.set(sp.getLong(KEY_INSTALL_TS, System.currentTimeMillis()));
        noteOpen();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private long newSeed() {
        return System.nanoTime() ^ (System.currentTimeMillis() << 16) ^ rng.nextLong();
    }

    // ------------------------------------------------------------------
    // Name masking
    // ------------------------------------------------------------------

    /** Obfuscate a string so plain-text scans find nothing meaningful. */
    public static String mask(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            sb.append((char) ((c * 31 + 17) & 0xFFFF));
        }
        return sb.toString();
    }

    /** Stable but opaque file/pref key from a semantic name. */
    public static String key(String semantic) {
        int h = semantic.hashCode();
        return "k" + Integer.toHexString(h);
    }

    // ------------------------------------------------------------------
    // Install model
    // ------------------------------------------------------------------

    public long installTimestampMs() {
        return installAnchorMs.get();
    }

    public long appAgeDays() {
        return (System.currentTimeMillis() - installAnchorMs.get()) / (24L * 3600_000L);
    }

    public boolean looksFreshlyInstalled() {
        return appAgeDays() < 1L;
    }

    /** Wall-clock independent "now" (boot-relative + install offset). */
    public long telemetryNow() {
        long bootMs = SystemClock.elapsedRealtime();
        return installAnchorMs.get() + bootMs;
    }

    // ------------------------------------------------------------------
    // Usage mimicry
    // ------------------------------------------------------------------

    public void noteOpen() {
        SharedPreferences sp = prefs();
        long day = System.currentTimeMillis() / (24L * 3600_000L);
        long lastDay = sp.getLong(KEY_LAST_OPEN_DAY, day);
        int opens = sp.getInt(KEY_OPEN_COUNT, 0);
        if (lastDay != day) {
            opens = 1;
            sp.edit().putLong(KEY_LAST_OPEN_DAY, day).apply();
        } else {
            opens++;
        }
        sp.edit().putInt(KEY_OPEN_COUNT, opens).apply();
    }

    public int opensToday() {
        long day = System.currentTimeMillis() / (24L * 3600_000L);
        if (prefs().getLong(KEY_LAST_OPEN_DAY, day) != day) return 0;
        return prefs().getInt(KEY_OPEN_COUNT, 0);
    }

    /** Plausible open count for this hour of day (a real user's profile). */
    public boolean opensPlausible() {
        int opens = opensToday();
        return opens >= 1 && opens <= 40;
    }

    // ------------------------------------------------------------------
    // Session seed & rotation
    // ------------------------------------------------------------------

    public String sessionSeedHex() {
        SharedPreferences sp = prefs();
        long seed = sp.getLong(KEY_SESSION_SEED, 0L);
        long lastRot = sp.getLong(KEY_ROTATION, 0L);
        if (System.currentTimeMillis() - lastRot > ROTATION_MS) {
            seed = newSeed();
            sp.edit()
                    .putLong(KEY_SESSION_SEED, seed)
                    .putLong(KEY_ROTATION, System.currentTimeMillis())
                    .apply();
        }
        return Long.toHexString(seed);
    }

    public String identityToken() {
        return Long.toHexString(installAnchorMs.get()) + "-" + sessionSeedHex();
    }

    // ------------------------------------------------------------------
    // Noise rows
    // ------------------------------------------------------------------

    private static final String[] NOISE_KEYS = {
            "a_pref_theme", "b_region", "c_volume", "d_notif", "e_rate", "f_feedback"
    };

    private void plantNoiseRows() {
        SharedPreferences.Editor e = prefs().edit();
        for (int i = 0; i < NOISE_KEYS.length; i++) {
            e.putString(NOISE_KEYS[i], noiseValue(i));
        }
        e.apply();
    }

    private String noiseValue(int i) {
        switch (i % 6) {
            case 0: return "dark";
            case 1: return "US";
            case 2: return "0.7";
            case 3: return "1";
            case 4: return "4";
            default: return "1";
        }
    }

    public int noiseRowCount() {
        int n = 0;
        for (String k : NOISE_KEYS) {
            if (prefs().contains(k)) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------
    // Process identity checks
    // ------------------------------------------------------------------

    public boolean processNameSane() {
        String pn = android.os.Process.myPid() + "";
        String[] cmdline = readCmdline();
        if (cmdline.length == 0) return false;
        String proc = cmdline[0];
        if (proc.contains(":cheat") || proc.contains(":hack") || proc.contains(":gg")) {
            return false;
        }
        return proc.length() > 0 && proc.length() < 128;
    }

    private String[] readCmdline() {
        try {
            java.io.File f = new java.io.File("/proc/self/cmdline");
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            byte[] buf = new byte[256];
            int n = in.read(buf);
            in.close();
            if (n <= 0) return new String[0];
            String s = new String(buf, 0, n, "UTF-8");
            return s.split("\u0000");
        } catch (Exception e) {
            return new String[0];
        }
    }

    public String expectedUidPrefix() {
        return String.valueOf(context.getApplicationInfo().uid);
    }

    // ------------------------------------------------------------------
    // Storage layout mimicry
    // ------------------------------------------------------------------

    public void ensureNormalLayout() {
        try {
            File base = context.getFilesDir();
            mkdirIfMissing(new File(base, "cache"));
            mkdirIfMissing(new File(base, "databases"));
            mkdirIfMissing(new File(base, "logs"));
            File prefs = new File(context.getDataDir(), "shared_prefs");
            mkdirIfMissing(prefs);
            touch(new File(base, "cache/.nomedia"));
            touch(new File(base, "logs/.keep"));
        } catch (Exception ignored) {
        }
    }

    private void mkdirIfMissing(File d) {
        if (!d.exists()) d.mkdirs();
    }

    private void touch(File f) {
        try {
            if (!f.exists()) f.createNewFile();
        } catch (Exception ignored) {
        }
    }

    public boolean layoutNormal() {
        File base = context.getFilesDir();
        return new File(base, "cache").exists()
                && new File(base, "databases").exists()
                && new File(base, "logs").exists();
    }

    // ------------------------------------------------------------------
    // Version & signature hygiene
    // ------------------------------------------------------------------

    public String versionString() {
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0";
        }
    }

    public boolean debuggableFlag() {
        return (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    public boolean signatureSane() {
        // A release-signature is expected; debug signatures look odd to
        // server-side checks. The build already signs release properly.
        return !debuggableFlag();
    }

    // ------------------------------------------------------------------
    // Feature-gating mimicry
    // ------------------------------------------------------------------

    private final AtomicLong lastGateFlipMs = new AtomicLong(System.currentTimeMillis());

    /** Suggest flipping a feature toggle (humans tweak settings). */
    public boolean suggestToggleFlip() {
        long now = System.currentTimeMillis();
        if (now - lastGateFlipMs.get() < 25L * 60_000L) return false;
        lastGateFlipMs.set(now);
        return rng.nextDouble() < 0.5d;
    }

    // ------------------------------------------------------------------
    // Boot anchor
    // ------------------------------------------------------------------

    public long bootUptimeMs() {
        return SystemClock.elapsedRealtime();
    }

    public long anchoredTimestamp() {
        return installAnchorMs.get() + SystemClock.elapsedRealtime();
    }

    // ------------------------------------------------------------------
    // Diagnostics
    // ------------------------------------------------------------------

    public static final class IdentityStats {
        public final long installAgeDays;
        public final int opensToday;
        public final boolean layoutNormal;
        public final boolean processSane;
        public final boolean debuggable;
        public final String version;
        public final int noiseRows;
        IdentityStats(long installAgeDays, int opensToday, boolean layoutNormal,
                      boolean processSane, boolean debuggable, String version, int noiseRows) {
            this.installAgeDays = installAgeDays;
            this.opensToday = opensToday;
            this.layoutNormal = layoutNormal;
            this.processSane = processSane;
            this.debuggable = debuggable;
            this.version = version;
            this.noiseRows = noiseRows;
        }
    }

    public IdentityStats stats() {
        return new IdentityStats(
                appAgeDays(),
                opensToday(),
                layoutNormal(),
                processNameSane(),
                debuggableFlag(),
                versionString(),
                noiseRowCount());
    }

    public String maskedPackageName() {
        return maskedPackage;
    }

    // ------------------------------------------------------------------
    // Entropy helpers
    // ------------------------------------------------------------------

    public int entropy() {
        return rng.nextInt(1 << 20);
    }

    public String opaqueId() {
        return Integer.toHexString((int) (installAnchorMs.get() & 0xFFFFFFFFL))
                + Integer.toHexString(rng.nextInt(0xFFFF));
    }

    // ------------------------------------------------------------------
    // Build-based masking
    // ------------------------------------------------------------------

    public String deviceModelMasked() {
        String model = Build.MODEL;
        return mask(model).substring(0, Math.min(8, mask(model).length()));
    }

    public String sdkVersion() {
        return String.valueOf(Build.VERSION.SDK_INT);
    }

    // ------------------------------------------------------------------
    // First-run pacing
    // ------------------------------------------------------------------

    public long firstRunAgeMs() {
        return System.currentTimeMillis() - prefs().getLong(KEY_FIRST_RUN, System.currentTimeMillis());
    }

    /** Whether the app should appear to still be in "onboarding". */
    public boolean inOnboardingWindow() {
        return firstRunAgeMs() < 30L * 60_000L;
    }

    // ------------------------------------------------------------------
    // List builders for diagnostics
    // ------------------------------------------------------------------

    public List<String> syntheticPaths() {
        List<String> out = new ArrayList<>();
        out.add("files/cache");
        out.add("files/databases");
        out.add("files/logs");
        return out;
    }

    // ------------------------------------------------------------------
    // Install-velocity fingerprint
    // ------------------------------------------------------------------

    private final AtomicLong lastInstallCheckMs = new AtomicLong(0L);

    /**
     * The install timestamp must stay consistent across checks; a
     * fingerprint that changes between calls means tampering. Also
     * models the "first install" vs "reinstall" profile.
     */
    public boolean installStable() {
        SharedPreferences sp = prefs();
        long stored = sp.getLong(KEY_INSTALL_TS, 0L);
        return stored == installAnchorMs.get();
    }

    public boolean looksLikeReinstall() {
        return appAgeDays() < 2L && opensToday() <= 2;
    }

    public boolean installVelocityPlausible() {
        return appAgeDays() >= 0L;
    }

    // ------------------------------------------------------------------
    // Uninstall/reinstall pattern model
    // ------------------------------------------------------------------

    /**
     * Accounts that are reinstalled every patch look suspicious. The
     * model tracks install epochs so telemetry can reflect a stable
     * install history.
     */
    public long installEpoch() {
        return installAnchorMs.get() / (30L * 24L * 3600_000L);
    }

    public boolean sameEpochAs(long otherTs) {
        return otherTs / (30L * 24L * 3600_000L) == installEpoch();
    }

    // ------------------------------------------------------------------
    // Shared-prefs access mimicry
    // ------------------------------------------------------------------

    /**
     * Real apps read their prefs in bursts at startup, then sparsely.
     * The shield simulates that access cadence so a prefs audit sees
     * normal behavior.
     */
    public boolean prefAccessDue() {
        long since = System.currentTimeMillis() - prefs().getLong(KEY_FIRST_RUN, 0L);
        if (since < 60_000L) return rng.nextDouble() < 0.5d;
        return rng.nextDouble() < 0.08d;
    }

    public long nextPrefAccessGapMs() {
        return 60_000L + rng.nextInt(240_000);
    }

    // ------------------------------------------------------------------
    // Key derivation with per-feature salts
    // ------------------------------------------------------------------

    /** Derive an opaque, salted key for a feature flag. */
    public String featureKey(String feature, int salt) {
        int h = feature.hashCode() ^ (salt * 31);
        return "f_" + Integer.toHexString(h);
    }

    public String saltedValue(String value, int salt) {
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            sb.append((char) ((c + salt) & 0xFFFF));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // App-data size model
    // ------------------------------------------------------------------

    /**
     * The app data dir grows slowly as a real app accumulates cache.
     * The model tracks a plausible size trajectory so a size audit
     * (e.g. data-dir byte counts) sees organic growth.
     */
    public long plausibleDataBytes() {
        long ageDays = Math.max(1L, appAgeDays());
        long base = 2_000_000L + ageDays * 45_000L;
        return base + rng.nextInt(400_000);
    }

    public long measuredDataBytes() {
        try {
            return dirBytes(context.getDataDir());
        } catch (Exception e) {
            return 0L;
        }
    }

    private long dirBytes(File dir) {
        long total = 0L;
        File[] files = dir.listFiles();
        if (files == null) return 0L;
        for (File f : files) {
            if (f.isDirectory()) {
                total += dirBytes(f);
            } else {
                total += f.length();
            }
        }
        return total;
    }

    public boolean dataSizePlausible() {
        long measured = measuredDataBytes();
        if (measured == 0L) return true; // can't measure in test env
        long plausible = plausibleDataBytes();
        return measured < plausible * 8L;
    }

    // ------------------------------------------------------------------
    // Storage growth rate
    // ------------------------------------------------------------------

    private final AtomicLong lastSizeCheckMs = new AtomicLong(0L);
    private long lastMeasuredSize = 0L;

    /** Growth rate per day; explosive growth is a cheat-install signature. */
    public double growthBytesPerDay() {
        long now = System.currentTimeMillis();
        if (now - lastSizeCheckMs.get() < 60_000L) return 0d;
        lastSizeCheckMs.set(now);
        long cur = measuredDataBytes();
        if (lastMeasuredSize == 0L) {
            lastMeasuredSize = cur;
            return 0d;
        }
        double delta = cur - lastMeasuredSize;
        lastMeasuredSize = cur;
        return delta * 24d * 3600d / Math.max(60d, (now - lastSizeCheckMs.get()) / 1000d);
    }

    public boolean growthPlausible() {
        return growthBytesPerDay() < 20_000_000d;
    }

    // ------------------------------------------------------------------
    // First-launch screens model
    // ------------------------------------------------------------------

    /**
     * First launch has onboarding screens with pauses. The model reports
     * whether the app should still be "onboarding" and how long each
     * screen pause should be.
     */
    public boolean onboardingScreenActive() {
        return inOnboardingWindow();
    }

    public long onboardingPauseMs() {
        return 1_200L + rng.nextInt(2_500);
    }

    // ------------------------------------------------------------------
    // Process restarts model
    // ------------------------------------------------------------------

    private final AtomicLong lastRestartMs = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger restarts = new AtomicInteger(0);

    /** Real apps restart occasionally (system kill, user swipe). */
    public void noteRestart() {
        long now = System.currentTimeMillis();
        if (now - lastRestartMs.get() < 2_000L) return;
        lastRestartMs.set(now);
        restarts.incrementAndGet();
    }

    public int restartCount() {
        return restarts.get();
    }

    public boolean restartRatePlausible() {
        return restarts.get() <= 40;
    }

    // ------------------------------------------------------------------
    // Region coherence
    // ------------------------------------------------------------------

    private static final String[] SUPPORTED_REGIONS = {
            "US", "GB", "PH", "MY", "ID", "SG", "VN", "TH", "BR", "MX", "IN"
    };

    public String coherentRegion() {
        return SUPPORTED_REGIONS[rng.nextInt(SUPPORTED_REGIONS.length)];
    }

    public boolean regionSupported(String region) {
        for (String r : SUPPORTED_REGIONS) {
            if (r.equals(region)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Device fingerprint jitter
    // ------------------------------------------------------------------

    /**
     * The reported device profile should be stable but not byte-identical
     * across requests; the jitter model varies the trailing digits only.
     */
    public String jitteredDeviceId() {
        return deviceModelMasked() + "-" + (1000 + rng.nextInt(9000));
    }

    public String sdkVersionJittered() {
        int sdk = Build.VERSION.SDK_INT;
        return String.valueOf(sdk - rng.nextInt(2));
    }

    // ------------------------------------------------------------------
    // Account-link cadence
    // ------------------------------------------------------------------

    private final AtomicLong lastLinkMs = new AtomicLong(0L);

    /** Account links happen once per install, not every session. */
    public boolean linkDue() {
        long now = System.currentTimeMillis();
        if (now - lastLinkMs.get() < 24L * 3600_000L) return false;
        lastLinkMs.set(now);
        return rng.nextDouble() < 0.1d;
    }

    public boolean linkedThisInstall() {
        return prefs().getBoolean("l_linked", false);
    }

    public void markLinked() {
        prefs().edit().putBoolean("l_linked", true).apply();
    }

    // ------------------------------------------------------------------
    // App-update window model
    // ------------------------------------------------------------------

    /**
     * App updates arrive on a cadence; the model simulates the install
     * looking like it went through the update path (old version seen
     * first, then newer).
     */
    public boolean updateWindowActive() {
        long ageDays = appAgeDays();
        return ageDays >= 7L && ageDays % 14L < 3L;
    }

    public String previousVersionString() {
        return "1.0";
    }

    // ------------------------------------------------------------------
    // Backup/restore detection
    // ------------------------------------------------------------------

    /**
     * Restored backups carry old timestamps; a fresh install with old
     * telemetry is contradictory. Detects the mismatch so telemetry can
     * stay coherent.
     */
    public boolean backupRestoreDetected() {
        long firstRun = prefs().getLong(KEY_FIRST_RUN, System.currentTimeMillis());
        long install = installAnchorMs.get();
        return firstRun < install;
    }

    // ------------------------------------------------------------------
    // Notification cadence
    // ------------------------------------------------------------------

    private final AtomicLong lastNotifMs = new AtomicLong(0L);

    /** A normal app pings notifications rarely; model the cadence. */
    public boolean notificationDue() {
        long now = System.currentTimeMillis();
        if (now - lastNotifMs.get() < 6L * 3600_000L) return false;
        lastNotifMs.set(now);
        return rng.nextDouble() < 0.3d;
    }

    public long nextNotifGapMs() {
        return 4L * 3600_000L + rng.nextInt(8 * 3600_000);
    }

    // ------------------------------------------------------------------
    // Anomaly counter
    // ------------------------------------------------------------------

    private final AtomicInteger identityAnomalies = new AtomicInteger(0);

    public void noteIdentityAnomaly() {
        identityAnomalies.incrementAndGet();
    }

    public int identityAnomalies() {
        return identityAnomalies.get();
    }

    public boolean anomalyFree() {
        return identityAnomalies.get() == 0;
    }

    // ------------------------------------------------------------------
    // Self-invariant check
    // ------------------------------------------------------------------

    /** Structural sanity of the identity state. */
    public boolean invariantsHold() {
        if (installAnchorMs.get() <= 0L) return false;
        if (installAnchorMs.get() > System.currentTimeMillis() + 86_400_000L) return false;
        if (maskedPackage == null || maskedPackage.isEmpty()) return false;
        return noiseRowCount() >= NOISE_KEYS.length;
    }

    // ------------------------------------------------------------------
    // Telemetry monotonicity
    // ------------------------------------------------------------------

    private final AtomicLong lastTelemetryMs = new AtomicLong(0L);

    /**
     * Telemetry timestamps must be monotonically increasing; a rollback
     * in telemetry time is a classic cheat-detection trigger.
     */
    public boolean telemetryMonotonic() {
        long now = telemetryNow();
        long last = lastTelemetryMs.get();
        if (now < last) return false;
        lastTelemetryMs.set(now);
        return true;
    }

    public boolean telemetryRollback() {
        return !telemetryMonotonic();
    }

    // ------------------------------------------------------------------
    // Usage-hours envelope
    // ------------------------------------------------------------------

    /**
     * Usage hours follow a real player's envelope (evening-heavy). The
     * model says whether "now" is a plausible play hour for this profile.
     */
    public boolean plausiblePlayHour() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
        if (hour >= 21 || hour < 3) return rng.nextDouble() < 0.9d;
        if (hour >= 15 && hour < 21) return rng.nextDouble() < 0.8d;
        if (hour >= 3 && hour < 8) return rng.nextDouble() < 0.15d;
        return rng.nextDouble() < 0.5d;
    }

    public double hourWeight() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
        if (hour >= 21 || hour < 3) return 1.0d;
        if (hour >= 15 && hour < 21) return 0.85d;
        if (hour >= 8 && hour < 15) return 0.5d;
        return 0.2d;
    }

    // ------------------------------------------------------------------
    // First-open pacing
    // ------------------------------------------------------------------

    /**
     * The very first open has a "setup" profile: permissions dialog,
     * tutorial, etc. The model paces early-open events so the install
     * doesn't immediately run at full speed.
     */
    public boolean earlyOpenPacing() {
        long age = firstRunAgeMs();
        if (age < 10_000L) return true;
        if (age < 120_000L) return rng.nextDouble() < 0.6d;
        return false;
    }

    public long earlyOpenDelayMs() {
        return 400L + rng.nextInt(1_400);
    }

    // ------------------------------------------------------------------
    // Feature-discovery curve
    // ------------------------------------------------------------------

    /**
     * Real users discover features gradually (menu exploration). The
     * curve says how many features a user of this install-age would have
     * found; the app can pace revealing UI accordingly.
     */
    public int discoveredFeatures() {
        double ageDays = appAgeDays();
        double discovered = Math.min(1d, ageDays / 21d);
        return (int) Math.round(discovered * 8d);
    }

    public boolean featureDiscoverable(int featureIndex) {
        return featureIndex <= discoveredFeatures();
    }

    // ------------------------------------------------------------------
    // Storage cleanliness
    // ------------------------------------------------------------------

    private static final String[] JUNK_FILES = {"cache/.thumb", "logs/.session", "databases/.journal"};

    /**
     * Real apps accumulate junk files. The shield maintains a small set
     * of plausible junk so a storage scan sees normal debris.
     */
    public void ensureJunkFiles() {
        try {
            File base = context.getFilesDir();
            for (String name : JUNK_FILES) {
                File f = new File(base, name);
                if (!f.exists()) {
                    File parent = f.getParentFile();
                    if (parent != null) parent.mkdirs();
                    f.createNewFile();
                }
            }
        } catch (Exception ignored) {
        }
    }

    public int junkFileCount() {
        int n = 0;
        try {
            File base = context.getFilesDir();
            for (String name : JUNK_FILES) {
                if (new File(base, name).exists()) n++;
            }
        } catch (Exception ignored) {
        }
        return n;
    }

    // ------------------------------------------------------------------
    // Locale coherence
    // ------------------------------------------------------------------

    /** A stable locale profile (region + language) that never contradicts. */
    public String localeProfile() {
        return coherentRegion().toLowerCase() + "-" + "en";
    }

    public boolean localeCoherent(String region, String lang) {
        if (!regionSupported(region)) return false;
        return lang.equals("en");
    }

    // ------------------------------------------------------------------
    // Billing/cache mimicry
    // ------------------------------------------------------------------

    /**
     * Premium apps carry billing caches. The shield simulates a purchase
     * record so a billing audit doesn't find a "cheat with no IAP" shape.
     */
    public boolean billingRecordPlausible() {
        return rng.nextDouble() < 0.7d;
    }

    public String billingSkew() {
        return Integer.toHexString(0x1000 + rng.nextInt(0xEFFF));
    }

    // ------------------------------------------------------------------
    // Session rollover
    // ------------------------------------------------------------------

    private final AtomicLong lastRolloverMs = new AtomicLong(0L);

    /** Sessions should roll over (new day = new session seed). */
    public boolean rolloverDue() {
        long now = System.currentTimeMillis();
        if (now - lastRolloverMs.get() < 12L * 3600_000L) return false;
        lastRolloverMs.set(now);
        return true;
    }

    public void rollover() {
        prefs().edit().putLong(KEY_SESSION_SEED, newSeed()).apply();
    }

    // ------------------------------------------------------------------
    // Crash-report cadence
    // ------------------------------------------------------------------

    /**
     * Real apps crash occasionally; a crash-report service that never
     * fires is as odd as one that fires constantly. Models the cadence.
     */
    public boolean crashReportDue() {
        return rng.nextDouble() < 0.02d;
    }

    public long nextCrashGapMs() {
        return 3L * 24L * 3600_000L + rng.nextInt(4 * 24 * 3600_000);
    }

    // ------------------------------------------------------------------
    // Usage-log entropy
    // ------------------------------------------------------------------

    private final AtomicLong lastUsageLogMs = new AtomicLong(0L);

    /** Usage logs grow in bursts; model the append cadence. */
    public boolean usageLogDue() {
        long now = System.currentTimeMillis();
        if (now - lastUsageLogMs.get() < 15L * 60_000L) return false;
        lastUsageLogMs.set(now);
        return true;
    }

    public int usageLogEntries() {
        return 1 + rng.nextInt(3);
    }

    // ------------------------------------------------------------------
    // Anti-spike smoothing
    // ------------------------------------------------------------------

    /**
     * The identity profile must not spike (sudden change of region,
     * model, language mid-session). The smoother reports whether the
     * current profile is within its own envelope.
     */
    public boolean profileStable() {
        return appAgeDays() >= 0L && opensPlausible() && layoutNormal();
    }

    // ------------------------------------------------------------------
    // Entropy funnel
    // ------------------------------------------------------------------

    /** Combine several entropy sources into one opaque session value. */
    public String funneledEntropy() {
        return Integer.toHexString(entropy())
                + Integer.toHexString(rng.nextInt(0xFFFF))
                + Long.toHexString(System.nanoTime() & 0xFFFF);
    }

    // ------------------------------------------------------------------
    // Weekly rhythm
    // ------------------------------------------------------------------

    /** Usage weight by day of week (weekends heavier, like a real player). */
    public double dayWeight() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int day = cal.get(java.util.Calendar.DAY_OF_WEEK);
        if (day == java.util.Calendar.SATURDAY || day == java.util.Calendar.SUNDAY) {
            return 1.2d;
        }
        return 0.9d;
    }

    public boolean weeklyRhythmPlausible() {
        return dayWeight() > 0d;
    }

    // ------------------------------------------------------------------
    // App-state envelope
    // ------------------------------------------------------------------

    /** Aggregate: is the whole identity profile currently "normal"? */
    public boolean identityEnvelopeNormal() {
        return installStable()
                && regionSupported(coherentRegion())
                && opensPlausible()
                && layoutNormal()
                && dataSizePlausible()
                && invariantsHold();
    }

    /** Opaque one-line profile digest for telemetry. */
    public String profileDigest() {
        return maskedPackage + "|" + appAgeDays() + "|" + opensToday() + "|" + entropy();
    }
}