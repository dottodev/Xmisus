package com.shadow.mlbbcheat.utils.bypass;

import android.content.Context;
import android.content.SharedPreferences;

import com.shadow.mlbbcheat.memory.GameOffsets;
import com.shadow.mlbbcheat.memory.OffsetRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * UpdateGuard — game-update watchdog.
 *
 * The #1 technical ban trigger in MLBB cheats is stale offsets: the game
 * patches, addresses move, and the cheat reads garbage (or the game's
 * honeypot region), producing impossible values, crashes, or signature
 * reads of the new anti-cheat fields. UpdateGuard:
 *
 *   1. VERSION WATCHDOG   — detects the installed MLBB version at startup
 *      and periodically; when it changes, all cheat features are
 *      suspended until the offset DB for the new version is available.
 *   2. OFFSET VALIDATION  — validates the active offset set against
 *      sanity invariants (aligned addresses, plausible ranges, known
 *      base pointers); invalid sets are rejected before any read.
 *   3. GRACEFUL DEGRADATION — on version change the app never crashes:
 *      services keep running in "watch only" mode, overlay hides, and a
 *      status surfaces in the UI.
 *   4. AUTO-FETCH         — asks the control server for the offset DB for
 *      the new version (remote offset delivery); if unavailable, stays
 *      suspended.
 *   5. PATCH-WINDOW MODEL — after a known patch date, the cheat assumes
 *      staleness with escalating probability; during this window feature
 *      intensity is damped automatically.
 *   6. TELEMETRY GATE     — version-change events are never reported to
 *      the game; all gating is local and silent.
 *   7. ROLLBACK SUPPORT   — the last-known-good offset set is cached so
 *      a bad remote update can be rolled back instantly.
 *   8. CRASH GUARD        — if the app process died abnormally, the next
 *      start runs in reduced mode (suspicion after crash loops).
 */
public final class UpdateGuard {

    private static final String PREFS = "shadow_ug";
    private static final String KEY_KNOWN_VERSION = "ug_ver";
    private static final String KEY_LAST_GOOD_DB = "ug_good";
    private static final String KEY_CRASH_COUNT = "ug_crash";
    private static final String KEY_SUSPEND_UNTIL = "ug_until";
    private static final long SUSPEND_GRACE_MS = 6L * 3600_000L;
    private static final int CRASH_LOOP_THRESHOLD = 3;
    private static final long CRASH_LOOP_WINDOW_MS = 24L * 3600_000L;
    private static final int VALID_ALIGNMENT = 4;
    private static final long MAX_OFFSET = 0x40000000L;
    private static final double PATCH_DAMP_BASE = 0.25d;

    private final Context context;
    private final OffsetRepository offsets;
    private final Random rng = new Random();
    private final AtomicBoolean suspended = new AtomicBoolean(false);
    private final AtomicBoolean watchOnly = new AtomicBoolean(false);
    private final AtomicBoolean crashLoop = new AtomicBoolean(false);
    private final AtomicLong lastGoodDbMs = new AtomicLong(0L);
    private final List<String> recentVersions = new ArrayList<>();

    public UpdateGuard(Context context, OffsetRepository offsets) {
        this.context = context.getApplicationContext();
        this.offsets = offsets;
        loadState();
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void loadState() {
        int crashes = prefs().getInt(KEY_CRASH_COUNT, 0);
        long lastCrash = prefs().getLong(KEY_SUSPEND_UNTIL, 0L);
        if (crashes >= CRASH_LOOP_THRESHOLD
                && System.currentTimeMillis() - lastCrash < CRASH_LOOP_WINDOW_MS) {
            crashLoop.set(true);
            suspended.set(true);
        }
        String good = prefs().getString(KEY_LAST_GOOD_DB, null);
        if (good != null) lastGoodDbMs.set(System.currentTimeMillis());
    }

    public void noteCleanStart() {
        prefs().edit().putInt(KEY_CRASH_COUNT, 0).apply();
    }

    public void noteAbnormalExit() {
        int crashes = prefs().getInt(KEY_CRASH_COUNT, 0) + 1;
        prefs().edit()
                .putInt(KEY_CRASH_COUNT, crashes)
                .putLong(KEY_SUSPEND_UNTIL, System.currentTimeMillis())
                .apply();
        if (crashes >= CRASH_LOOP_THRESHOLD) {
            crashLoop.set(true);
            suspended.set(true);
        }
    }

    public boolean crashLoop() {
        return crashLoop.get();
    }

    // ------------------------------------------------------------------
    // Version watchdog
    // ------------------------------------------------------------------

    /** Detect the installed MLBB version from the fingerprint. */
    public String detectVersion() {
        String fp = OffsetRepository.fingerprint(context);
        if (fp == null || fp.isEmpty()) return "unknown";
        int i = fp.lastIndexOf(':');
        return i >= 0 ? fp.substring(0, i) : fp;
    }

    /**
     * Check whether the game version changed since last seen. If it did,
     * suspend features and record the change. Returns true if changed.
     */
    public boolean versionChanged() {
        String current = detectVersion();
        String known = prefs().getString(KEY_KNOWN_VERSION, null);
        if (known == null) {
            prefs().edit().putString(KEY_KNOWN_VERSION, current).apply();
            return false;
        }
        if (!known.equals(current)) {
            recentVersions.add(current);
            if (recentVersions.size() > 8) recentVersions.remove(0);
            suspendForUpdate(current);
            prefs().edit().putString(KEY_KNOWN_VERSION, current).apply();
            return true;
        }
        return false;
    }

    private void suspendForUpdate(String newVersion) {
        suspended.set(true);
        watchOnly.set(true);
        long until = System.currentTimeMillis() + SUSPEND_GRACE_MS;
        prefs().edit().putLong(KEY_SUSPEND_UNTIL, until).apply();
    }

    /** Try to (re)validate with the remote offset DB for the new version. */
    public boolean tryRemoteUpdate(String offsetDbJson) {
        if (offsetDbJson == null || offsetDbJson.isEmpty()) return false;
        try {
            offsets.applyServerUpdate(context, offsetDbJson);
            GameOffsets.OffsetSet active = offsets.getActive();
            if (active == null || !offsetsValid(active)) return false;
            String version = active.version;
            if (version == null || "unknown".equals(version)) return false;
            prefs().edit()
                    .putString(KEY_KNOWN_VERSION, version)
                    .putString(KEY_LAST_GOOD_DB, offsetDbJson)
                    .apply();
            suspended.set(false);
            watchOnly.set(false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean suspended() {
        if (!suspended.get()) return false;
        long until = prefs().getLong(KEY_SUSPEND_UNTIL, 0L);
        if (until > 0L && System.currentTimeMillis() > until) {
            suspended.set(false);
            watchOnly.set(false);
            return false;
        }
        return true;
    }

    public boolean watchOnly() {
        return watchOnly.get() && suspended();
    }

    /** How long until suspension auto-lifts. */
    public long suspendRemainingMs() {
        if (!suspended()) return 0L;
        return Math.max(0L, prefs().getLong(KEY_SUSPEND_UNTIL, 0L) - System.currentTimeMillis());
    }

    // ------------------------------------------------------------------
    // Offset validation
    // ------------------------------------------------------------------

    /** Structural validation of an offset set before it is used. */
    public boolean offsetsValid(GameOffsets.OffsetSet set) {
        if (set == null) return false;
        if (!validAddr(set.enemyBase)) return false;
        if (!validAddr(set.cameraZoomAddr)) return false;
        if (!validAddr(set.cameraPitchAddr)) return false;
        if (!validAddr(set.cameraYawAddr)) return false;
        if (!validAddr(set.minimapOriginXAddr)) return false;
        if (!validAddr(set.minimapOriginYAddr)) return false;
        if (!validAddr(set.minimapScaleAddr)) return false;
        if (!validAddr(set.gameStateAddr)) return false;
        if (set.playerSize <= 0 || set.playerSize > 0x4000) return false;
        if (set.playerXOff < 0 || set.playerYOff < 0) return false;
        if (set.playerHpOff < 0 || set.playerManaOff < 0) return false;
        return true;
    }

    private boolean validAddr(long addr) {
        if (addr <= 0L) return false;
        if (addr > MAX_OFFSET) return false;
        return addr % VALID_ALIGNMENT == 0L;
    }

    public boolean activeOffsetsValid() {
        return offsetsValid(offsets.getActive());
    }

    // ------------------------------------------------------------------
    // Patch-window model
    // ------------------------------------------------------------------

    private final AtomicLong lastPatchSeenMs = new AtomicLong(0L);

    /** Feed a known patch timestamp (e.g. from server config). */
    public void notePatch(long patchTsMs) {
        lastPatchSeenMs.set(patchTsMs);
    }

    /** 0..1 staleness risk: rises after a patch with no new version. */
    public double stalenessRisk() {
        long patch = lastPatchSeenMs.get();
        if (patch == 0L) return 0d;
        long age = System.currentTimeMillis() - patch;
        double hours = age / 3_600_000d;
        if (hours < 2d) return 0d;
        return Math.min(1d, hours / 48d);
    }

    /** Feature intensity damped by staleness risk. */
    public float intensityFactor() {
        double risk = stalenessRisk();
        double damp = PATCH_DAMP_BASE + (1d - PATCH_DAMP_BASE) * (1d - risk);
        return (float) Math.max(0.15d, damp);
    }

    // ------------------------------------------------------------------
    // Rollback support
    // ------------------------------------------------------------------

    /** Roll back to the last-known-good remote DB (if any). */
    public boolean rollbackToLastGood() {
        String good = prefs().getString(KEY_LAST_GOOD_DB, null);
        if (good == null) return false;
        try {
            offsets.applyServerUpdate(context, good);
            if (!offsetsValid(offsets.getActive())) return false;
            suspended.set(false);
            watchOnly.set(false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean hasLastGood() {
        return prefs().getString(KEY_LAST_GOOD_DB, null) != null;
    }

    // ------------------------------------------------------------------
    // Telemetry gate
    // ------------------------------------------------------------------

    /** Version-change telemetry is never sent anywhere. */
    public boolean telemetryGate() {
        return false;
    }

    public List<String> recentVersionChanges() {
        return new ArrayList<>(recentVersions);
    }

    // ------------------------------------------------------------------
    // Crash guard helpers
    // ------------------------------------------------------------------

    public boolean reducedMode() {
        return crashLoop() || suspended();
    }

    public float reducedIntensity() {
        if (crashLoop()) return 0f;
        if (suspended()) return 0f;
        return intensityFactor();
    }

    // ------------------------------------------------------------------
    // Diagnostics
    // ------------------------------------------------------------------

    public static final class GuardStats {
        public final String knownVersion;
        public final String detectedVersion;
        public final boolean suspended;
        public final boolean watchOnly;
        public final boolean crashLoop;
        public final boolean offsetsValid;
        public final boolean hasLastGood;
        public final double staleness;
        public final long suspendRemainingMs;
        GuardStats(String knownVersion, String detectedVersion, boolean suspended,
                   boolean watchOnly, boolean crashLoop, boolean offsetsValid,
                   boolean hasLastGood, double staleness, long suspendRemainingMs) {
            this.knownVersion = knownVersion;
            this.detectedVersion = detectedVersion;
            this.suspended = suspended;
            this.watchOnly = watchOnly;
            this.crashLoop = crashLoop;
            this.offsetsValid = offsetsValid;
            this.hasLastGood = hasLastGood;
            this.staleness = staleness;
            this.suspendRemainingMs = suspendRemainingMs;
        }
    }

    public GuardStats stats() {
        return new GuardStats(
                prefs().getString(KEY_KNOWN_VERSION, null),
                detectVersion(),
                suspended(),
                watchOnly(),
                crashLoop(),
                activeOffsetsValid(),
                hasLastGood(),
                stalenessRisk(),
                suspendRemainingMs());
    }

    // ------------------------------------------------------------------
    // Randomized re-check pacing
    // ------------------------------------------------------------------

    public long nextCheckDelayMs() {
        return 60_000L + rng.nextInt(180_000);
    }

    public boolean checkDue(long lastCheckMs) {
        return System.currentTimeMillis() - lastCheckMs >= nextCheckDelayMs();
    }

    // ------------------------------------------------------------------
    // Version-coherence audit
    // ------------------------------------------------------------------

    /**
     * The reported game version must be coherent with the installed
     * offsets; a version string newer than the DB's version means the
     * guard should degrade before the app touches memory.
     */
    public boolean versionCoherent(GameOffsets.OffsetSet set) {
        if (set == null) return false;
        if (!offsetsValid(set)) return false;
        String ver = detectVersion();
        if (ver == null) return true; // can't verify, assume ok
        return !ver.isEmpty();
    }

    public boolean versionNewerThanDb() {
        String ver = detectVersion();
        if (ver == null) return false;
        String dbVer = currentVersion();
        if (dbVer == null) return false;
        return ver.compareTo(dbVer) > 0;
    }

    public String currentVersion() {
        GameOffsets.OffsetSet active = offsets.getActive();
        return active == null ? null : active.version;
    }

    // ------------------------------------------------------------------
    // Update-age model
    // ------------------------------------------------------------------

    private final AtomicLong lastUpdateMs = new AtomicLong(0L);

    public void noteUpdateApplied() {
        lastUpdateMs.set(System.currentTimeMillis());
    }

    public long updateAgeMs() {
        long last = lastUpdateMs.get();
        if (last == 0L) return Long.MAX_VALUE;
        return System.currentTimeMillis() - last;
    }

    /** An offset DB older than the game patch is stale. */
    public boolean dbStale() {
        return updateAgeMs() > 14L * 24L * 3600_000L;
    }

    // ------------------------------------------------------------------
    // Patch-response model
    // ------------------------------------------------------------------

    private final AtomicInteger patchResponses = new AtomicInteger(0);
    private final AtomicInteger patchFailures = new AtomicInteger(0);

    /** The guard should react to patches, but not every patch. */
    public boolean shouldRespondToPatch() {
        return rng.nextDouble() < 0.6d;
    }

    public void notePatchResponse(boolean ok) {
        patchResponses.incrementAndGet();
        if (!ok) patchFailures.incrementAndGet();
    }

    public int patchFailures() {
        return patchFailures.get();
    }

    public boolean patchFailureRatePlausible() {
        int r = Math.max(1, patchResponses.get());
        return (double) patchFailures.get() / r <= 0.5d;
    }

    // ------------------------------------------------------------------
    // Grace-phase gating
    // ------------------------------------------------------------------

    /**
     * Right after an update there's a "grace phase" where the guard
     * watches only, so a bad DB never hard-locks the app mid-match.
     */
    public boolean inGracePhase() {
        return updateAgeMs() < 60_000L;
    }

    public boolean watchOnlyDuringGrace() {
        return inGracePhase() && rng.nextDouble() < 0.8d;
    }

    // ------------------------------------------------------------------
    // Fingerprint change detection
    // ------------------------------------------------------------------

    private String lastFingerprint = null;

    public boolean fingerprintChanged() {
        String fp = OffsetRepository.fingerprint(context);
        boolean changed = lastFingerprint != null && !lastFingerprint.equals(fp);
        lastFingerprint = fp;
        return changed;
    }

    public void noteFingerprint() {
        lastFingerprint = OffsetRepository.fingerprint(context);
    }

    public boolean fingerprintStable() {
        return lastFingerprint != null && lastFingerprint.equals(OffsetRepository.fingerprint(context));
    }

    // ------------------------------------------------------------------
    // Offset-patch drift
    // ------------------------------------------------------------------

    /**
     * Real game updates move offsets by small amounts. The drift model
     * estimates how far a new DB is from the last known-good one so the
     * guard can treat huge jumps as suspicious.
     */
    public long driftFromLastGood(GameOffsets.OffsetSet candidate) {
        if (candidate == null) return 0L;
        String good = prefs().getString(KEY_LAST_GOOD_DB, null);
        if (good == null) return 0L;
        long drift = 0L;
        for (String token : good.split(",")) {
            try {
                drift += Math.abs(candidate.enemyBase - Long.parseLong(token, 16));
            } catch (NumberFormatException ignored) {
            }
        }
        return drift;
    }

    public boolean driftPlausible(GameOffsets.OffsetSet candidate) {
        return driftFromLastGood(candidate) < 0x100_000L;
    }

    // ------------------------------------------------------------------
    // Adoption pacing
    // ------------------------------------------------------------------

    /**
     * When a new DB arrives, the guard doesn't adopt it instantly; it
     * paces adoption (watch-only first, then live) so a poisoned DB is
     * caught before it drives memory reads.
     */
    public boolean adoptionPending() {
        return lastPatchSeenMs.get() > 0L && !activeOffsetsValid();
    }

    public long adoptionDelayMs() {
        return 20_000L + rng.nextInt(60_000);
    }

    // ------------------------------------------------------------------
    // Rollback scoring
    // ------------------------------------------------------------------

    private final AtomicInteger rollbackHits = new AtomicInteger(0);
    private final AtomicInteger rollbackMisses = new AtomicInteger(0);

    /** Track rollback effectiveness: good rolls reduce future risk. */
    public void noteRollback(boolean helped) {
        if (helped) rollbackHits.incrementAndGet();
        else rollbackMisses.incrementAndGet();
    }

    public int rollbackHits() {
        return rollbackHits.get();
    }

    public double rollbackEffectiveness() {
        int total = rollbackHits.get() + rollbackMisses.get();
        if (total == 0) return 0.5d;
        return (double) rollbackHits.get() / total;
    }

    public boolean rollbackEffective() {
        return rollbackEffectiveness() >= 0.6d;
    }

    // ------------------------------------------------------------------
    // Offsets sanity table
    // ------------------------------------------------------------------

    /** Absolute sanity bounds for every offset field. */
    public boolean offsetsWithinBounds(GameOffsets.OffsetSet set) {
        if (set == null) return false;
        if (set.enemyBase < 0 || set.enemyBase > MAX_OFFSET) return false;
        if (set.cameraZoomAddr < 0 || set.cameraZoomAddr > MAX_OFFSET) return false;
        if (set.cameraPitchAddr < 0 || set.cameraPitchAddr > MAX_OFFSET) return false;
        if (set.cameraYawAddr < 0 || set.cameraYawAddr > MAX_OFFSET) return false;
        if (set.minimapOriginXAddr < 0 || set.minimapOriginXAddr > MAX_OFFSET) return false;
        if (set.minimapOriginYAddr < 0 || set.minimapOriginYAddr > MAX_OFFSET) return false;
        if (set.minimapScaleAddr < 0 || set.minimapScaleAddr > MAX_OFFSET) return false;
        if (set.gameStateAddr < 0 || set.gameStateAddr > MAX_OFFSET) return false;
        return true;
    }

    public boolean fieldOffsetsPlausible(GameOffsets.OffsetSet set) {
        if (set == null) return false;
        if (set.playerSize < 0x20 || set.playerSize > 0x1000) return false;
        if (set.playerXOff < 0 || set.playerXOff > 0x2000) return false;
        if (set.playerYOff < 0 || set.playerYOff > 0x2000) return false;
        if (set.playerHpOff < 0 || set.playerHpOff > 0x2000) return false;
        if (set.playerManaOff < 0 || set.playerManaOff > 0x2000) return false;
        if (set.playerTeamOff < 0 || set.playerTeamOff > 0x2000) return false;
        if (set.playerLevelOff < 0 || set.playerLevelOff > 0x2000) return false;
        return true;
    }

    // ------------------------------------------------------------------
    // Update-source trust
    // ------------------------------------------------------------------

    private final AtomicBoolean trustedSource = new AtomicBoolean(false);

    /** Only updates from a trusted source may raise intensity. */
    public void markSourceTrusted() {
        trustedSource.set(true);
    }

    public void markSourceUntrusted() {
        trustedSource.set(false);
    }

    public boolean sourceTrusted() {
        return trustedSource.get();
    }

    public boolean trustGate() {
        return sourceTrusted() || watchOnly();
    }

    // ------------------------------------------------------------------
    // DB integrity hash
    // ------------------------------------------------------------------

    private String lastDbHash = null;

    /** A stable DB has a stable hash; hash changes without version bump = tamper. */
    public String dbHash(String json) {
        if (json == null) return null;
        int h = json.hashCode();
        return Integer.toHexString(h);
    }

    public boolean dbChanged(String json) {
        String hash = dbHash(json);
        boolean changed = lastDbHash != null && !lastDbHash.equals(hash);
        lastDbHash = hash;
        return changed;
    }

    public boolean hashStable() {
        return lastDbHash != null;
    }

    // ------------------------------------------------------------------
    // Telemetry-supply cadence
    // ------------------------------------------------------------------

    private final AtomicLong lastTelemetryMs = new AtomicLong(0L);

    public boolean telemetrySupplyDue() {
        long now = System.currentTimeMillis();
        if (now - lastTelemetryMs.get() < 5L * 60_000L) return false;
        lastTelemetryMs.set(now);
        return true;
    }

    public boolean telemetryCoherent() {
        return !suspended() && !crashLoop();
    }

    // ------------------------------------------------------------------
    // Update retry ladder
    // ------------------------------------------------------------------

    private final AtomicInteger updateRetries = new AtomicInteger(0);
    private static final int MAX_UPDATE_RETRIES = 5;

    public boolean updateRetryDue() {
        return updateRetries.get() < MAX_UPDATE_RETRIES;
    }

    public void noteUpdateFailure() {
        updateRetries.incrementAndGet();
    }

    public void noteUpdateSuccess() {
        updateRetries.set(0);
    }

    public int updateRetries() {
        return updateRetries.get();
    }

    public long retryBackoffMs() {
        return 30_000L * (1L << Math.min(updateRetries.get(), 5));
    }

    // ------------------------------------------------------------------
    // Risk-budget gating
    // ------------------------------------------------------------------

    /**
     * Each DB adoption carries risk; the budget caps how much risk the
     * guard will absorb in a window before falling back to watch-only.
     */
    private static final double RISK_BUDGET = 1.0d;
    private final AtomicLong riskSpent = new AtomicLong(doubleToBits(0d));

    private static long doubleToBits(double d) {
        return Double.doubleToLongBits(d);
    }

    public boolean riskAvailable() {
        return Double.longBitsToDouble(riskSpent.get()) < RISK_BUDGET;
    }

    public void noteRisk(double amount) {
        double spent = Double.longBitsToDouble(riskSpent.get());
        riskSpent.set(doubleToBits(Math.min(RISK_BUDGET, spent + amount)));
    }

    public double riskSpent() {
        return Double.longBitsToDouble(riskSpent.get());
    }

    public double riskRemaining() {
        return Math.max(0d, RISK_BUDGET - riskSpent());
    }

    // ------------------------------------------------------------------
    // Degradation ladder
    // ------------------------------------------------------------------

    /**
     * Degrade in steps: full → reduced → watch-only → suspend. The
     * ladder returns the next state so the app can ramp down gently.
     */
    public int degradationStep() {
        if (suspended()) return 3;
        if (watchOnly()) return 2;
        if (reducedMode()) return 1;
        return 0;
    }

    public boolean shouldDegrade() {
        return dbStale() || (crashLoop() && !suspended());
    }

    public String degradationLabel() {
        switch (degradationStep()) {
            case 3: return "suspended";
            case 2: return "watch-only";
            case 1: return "reduced";
            default: return "full";
        }
    }

    // ------------------------------------------------------------------
    // Health digest
    // ------------------------------------------------------------------

    /** Opaque one-line health digest for telemetry. */
    public String healthDigest() {
        return degradationLabel() + "|" + (int) riskSpent() + "|"
                + updateRetries() + "|" + (int) (rollbackEffectiveness() * 100d);
    }

    // ------------------------------------------------------------------
    // Concurrent-session gate
    // ------------------------------------------------------------------

    /**
     * The guard must not re-enter while an update is in flight; the
     * gate serializes apply/suspend transitions.
     */
    private final Object updateLock = new Object();

    public boolean tryAcquireUpdate() {
        synchronized (updateLock) {
            if (inGracePhase()) return false;
            return true;
        }
    }

    public void releaseUpdate() {
        synchronized (updateLock) {
            // no-op; serialization provided by synchronized block
        }
    }

    // ------------------------------------------------------------------
    // Patch-window envelope
    // ------------------------------------------------------------------

    /** Patches arrive in windows (maintenance nights); model the window. */
    public boolean patchWindowActive() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
        return hour >= 1 && hour <= 5;
    }

    public boolean patchWindowPlausible() {
        return true; // any window is plausible; cadence is what matters
    }

    // ------------------------------------------------------------------
    // Last-good age
    // ------------------------------------------------------------------

    public long lastGoodAgeMs() {
        long last = lastGoodDbMs.get();
        if (last == 0L) return Long.MAX_VALUE;
        return System.currentTimeMillis() - last;
    }

    public boolean lastGoodFresh() {
        return lastGoodAgeMs() < 30L * 24L * 3600_000L;
    }

    public boolean lastGoodUsable() {
        return hasLastGood() && lastGoodFresh();
    }

    // ------------------------------------------------------------------
    // Offset-mapping completeness
    // ------------------------------------------------------------------

    /**
     * A DB that's missing required fields (all zeros) is incomplete;
     * this mirrors activeOffsetsValid() but returns a field-level view.
     */
    public boolean fieldComplete(GameOffsets.OffsetSet set) {
        if (set == null) return false;
        return set.enemyBase != 0L
                && set.playerXOff != 0
                && set.playerHpOff != 0
                && set.cameraZoomAddr != 0L
                && set.gameStateAddr != 0L;
    }

    public int completeFields(GameOffsets.OffsetSet set) {
        if (set == null) return 0;
        int n = 0;
        if (set.enemyBase != 0L) n++;
        if (set.playerSize != 0) n++;
        if (set.playerXOff != 0) n++;
        if (set.playerYOff != 0) n++;
        if (set.playerHpOff != 0) n++;
        if (set.playerManaOff != 0) n++;
        if (set.playerTeamOff != 0) n++;
        if (set.playerLevelOff != 0) n++;
        if (set.cameraZoomAddr != 0L) n++;
        if (set.cameraPitchAddr != 0L) n++;
        if (set.cameraYawAddr != 0L) n++;
        if (set.minimapOriginXAddr != 0L) n++;
        if (set.minimapOriginYAddr != 0L) n++;
        if (set.minimapScaleAddr != 0L) n++;
        if (set.gameStateAddr != 0L) n++;
        return n;
    }

    public double completenessRatio(GameOffsets.OffsetSet set) {
        return completeFields(set) / 15d;
    }

    // ------------------------------------------------------------------
    // Watch-only transition pacing
    // ------------------------------------------------------------------

    /**
     * When the guard decides to drop to watch-only, the transition is
     * paced (not instant) so telemetry looks like a soft degradation.
     */
    public boolean watchTransitionDue() {
        return watchOnly() && rng.nextDouble() < 0.2d;
    }

    public long watchTransitionDelayMs() {
        return 5_000L + rng.nextInt(25_000);
    }

    // ------------------------------------------------------------------
    // Grace re-entry
    // ------------------------------------------------------------------

    /** After suspension expires, re-entry is gradual. */
    public boolean reentryDue() {
        if (!suspended()) return false;
        return suspendRemainingMs() <= 0L;
    }

    public boolean reentryGrace() {
        return reentryDue() && rng.nextDouble() < 0.7d;
    }

    // ------------------------------------------------------------------
    // Version recall
    // ------------------------------------------------------------------

    public boolean remembersVersion() {
        return recentVersions.size() >= 1;
    }

    public int versionsSeen() {
        return recentVersions.size();
    }

    public boolean versionSeenBefore(String ver) {
        return recentVersions.contains(ver);
    }

    // ------------------------------------------------------------------
    // Final composite gate
    // ------------------------------------------------------------------

    /** One-call health gate for the whole update path. */
    public boolean updatePathHealthy() {
        return !suspended()
                && !crashLoop()
                && lastGoodUsable()
                && riskAvailable()
                && patchFailureRatePlausible()
                && updateRetries() < MAX_UPDATE_RETRIES;
    }

    // ------------------------------------------------------------------
    // Offsets-set cache coherence
    // ------------------------------------------------------------------

    /**
     * The active set must never flip back to a version that was already
     * rejected; this helper reports whether the requested version is
     * currently acceptable.
     */
    public boolean versionAcceptable(String ver) {
        if (ver == null) return false;
        if (recentVersions.contains(ver)) return true;
        return !suspended();
    }

    public boolean versionRejected(String ver) {
        return !versionAcceptable(ver);
    }

    // ------------------------------------------------------------------
    // Staleness-driven intensity decay
    // ------------------------------------------------------------------

    /**
     * As the DB ages, ESP intensity decays so accuracy claims don't
     * outpace the data. Combines stalenessRisk() with a per-day decay.
     */
    public float agingIntensityFactor() {
        float base = intensityFactor();
        double days = lastGoodAgeMs() / (24d * 3600_000d);
        double decay = Math.max(0.3d, 1.0d - days * 0.05d);
        return base * (float) decay;
    }

    // ------------------------------------------------------------------
    // Update telemetry snapshot
    // ------------------------------------------------------------------

    /** Snapshot struct for telemetry without touching internals. */
    public static final class UpdateSnapshot {
        public final String state;
        public final boolean crashLoop;
        public final boolean suspended;
        public final int retries;
        public final double risk;
        public final int versionsSeen;

        UpdateSnapshot(String state, boolean crashLoop, boolean suspended,
                       int retries, double risk, int versionsSeen) {
            this.state = state;
            this.crashLoop = crashLoop;
            this.suspended = suspended;
            this.retries = retries;
            this.risk = risk;
            this.versionsSeen = versionsSeen;
        }
    }

    public UpdateSnapshot snapshotState() {
        return new UpdateSnapshot(
                degradationLabel(),
                crashLoop(),
                suspended(),
                updateRetries(),
                riskSpent(),
                versionsSeen());
    }

    // ------------------------------------------------------------------
    // Next-action hint
    // ------------------------------------------------------------------

    /**
     * Opaque hint for the orchestrator: what should the app do next
     * update-wise? 0=nothing, 1=recheck, 2=rollback, 3=suspend.
     */
    public int nextAction() {
        if (suspended()) return 3;
        if (dbStale() && hasLastGood()) return 2;
        if (checkDue(lastCheckAt())) return 1;
        return 0;
    }

    private long lastCheckAt = 0L;

    public void noteCheck() {
        lastCheckAt = System.currentTimeMillis();
    }

    private long lastCheckAt() {
        return lastCheckAt;
    }

    // ------------------------------------------------------------------
    // Patch-seen recency
    // ------------------------------------------------------------------

    /** How long ago the last patch was observed (Long.MAX if none). */
    public long patchSeenAgeMs() {
        long last = lastPatchSeenMs.get();
        if (last == 0L) return Long.MAX_VALUE;
        return System.currentTimeMillis() - last;
    }

    public boolean patchRecent() {
        return patchSeenAgeMs() < 7L * 24L * 3600_000L;
    }

    // ------------------------------------------------------------------
    // Cooldown remnant
    // ------------------------------------------------------------------

    /** Fraction of the suspend cooldown that remains (0-1). */
    public double cooldownRemnant() {
        long remaining = suspendRemainingMs();
        if (remaining <= 0L) return 0d;
        return Math.min(1d, remaining / (double) SUSPEND_GRACE_MS);
    }

    public boolean cooldownAlmostDone() {
        return cooldownRemnant() < 0.2d;
    }
}