package com.shadow.mlbbcheat.utils.bypass;

import android.content.Context;

import com.shadow.mlbbcheat.memory.GameOffsets;
import com.shadow.mlbbcheat.memory.OffsetRepository;
import com.shadow.mlbbcheat.models.PlayerData;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * BypassStack — orchestrator for the anti-detection shield stack.
 *
 * All shields live here and are driven from two integration points:
 *
 *   1. OVERLAY HOT PATH  — every frame of enemy data passes through
 *      {@link #sanitizeEnemies(List, long)} which filters honeypot
 *      phantoms/clones/teleporters, then computes a stealth profile and
 *      a feature-intensity multiplier from every shield, so the overlay
 *      never paints a target in a suspicious way.
 *
 *   2. BACKGROUND CADENCE — {@link #tick()} is called periodically from
 *      the orchestration service and runs the slow audits (proc, mounts,
 *      packages, timing) on their own randomized schedules; heartbeat
 *      pacing and the game-update watchdog are exposed for the heartbeat
 *      loop.
 *
 * If any shield reaches a hard condition (kill-switch drain, crash loop,
 * update suspension, deep proc anomaly), {@link #hardStop()} reports it
 * so the caller can shut the cheat stack down silently.
 */
public final class BypassStack {

    private static volatile BypassStack instance;

    private static final long PROC_AUDIT_INTERVAL_MS = 20_000L;
    private static final long TICK_INTERVAL_MS = 800L;

    private final Context context;
    private final OffsetRepository offsets;

    public final ScanShield scanShield;
    public final ProcCloak procCloak;
    public final BehaviorCloak behaviorCloak;
    public final NetworkShield networkShield;
    public final HoneypotGuard honeypotGuard;
    public final IdentityShield identityShield;
    public final StatsCloak statsCloak;
    public final UpdateGuard updateGuard;
    public final EnemyLag enemyLag;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean hardStopFlag = new AtomicBoolean(false);
    private final AtomicLong lastTickMs = new AtomicLong(0L);
    private final AtomicLong lastAuditMs = new AtomicLong(0L);

    public static BypassStack getInstance(Context context) {
        if (instance == null) {
            synchronized (BypassStack.class) {
                if (instance == null) {
                    instance = new BypassStack(context);
                }
            }
        }
        return instance;
    }

    /** Test hook: drop the singleton so each test starts clean. */
    static void resetForTest() {
        synchronized (BypassStack.class) {
            instance = null;
        }
    }

    /** Test hook: feed a real offset table so EnemyLag gates pass. */
    void injectOffsetsForTest(String json) {
        offsets.applyServerUpdate(context, json);
    }

    private BypassStack(Context context) {
        this.context = context.getApplicationContext();
        this.offsets = new OffsetRepository(this.context);
        this.scanShield = new ScanShield();
        this.procCloak = new ProcCloak(this.context);
        this.behaviorCloak = new BehaviorCloak();
        this.networkShield = new NetworkShield();
        this.honeypotGuard = new HoneypotGuard();
        this.identityShield = new IdentityShield(this.context);
        this.statsCloak = new StatsCloak();
        this.updateGuard = new UpdateGuard(this.context, this.offsets);
        this.enemyLag = new EnemyLag(this);
    }

    // ------------------------------------------------------------------
    // Package-private accessors used by EnemyLag gates
    // ------------------------------------------------------------------

    /** True when the active offset table is real (not placeholder/zero). */
    boolean offsetsReady() {
        GameOffsets.OffsetSet s = offsets.getActive();
        return s != null && s.enemyBase > 0 && s.playerSize > 0;
    }

    /** True once the stack has hard-stopped (kill drain/crash loop). */
    boolean stackHardStopped() {
        return hardStopFlag.get();
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    public synchronized void onStart() {
        if (started.getAndSet(true)) return;
        identityShield.ensureNormalLayout();
        identityShield.noteOpen();
        updateGuard.versionChanged();
        updateGuard.noteCleanStart();
        procCloak.reset();
        networkShield.rotateSession();
        scanShield.noteSessionStart();
    }

    public synchronized void onStop() {
        started.set(false);
    }

    public void onMatchStart() {
        statsCloak.beginMatch();
        honeypotGuard.noteMatchStart();
        behaviorCloak.beginSession();
        scanShield.noteSessionStart();
        enemyLag.onMatchStart();
    }

    public void onMatchEnd(boolean won) {
        statsCloak.endMatch(won);
        behaviorCloak.beginSession();
        enemyLag.onMatchEnd(won);
    }

    public void onKill() {
        statsCloak.noteKill();
        statsCloak.noteKillWindow();
        if (statsCloak.shouldCoolDown()) behaviorCloak.noteEvent();
    }

    public void onDeath() {
        statsCloak.noteDeath();
        behaviorCloak.noteDeath();
    }

    public void onAssist() {
        statsCloak.noteAssist();
    }

    public void onDomination() {
        statsCloak.noteDomination();
    }

    public void onReportEvent() {
        honeypotGuard.noteReportEvent();
    }

    // ------------------------------------------------------------------
    // Overlay hot path
    // ------------------------------------------------------------------

    /** Filter honeypot entities and trap values out of a frame. */
    public List<PlayerData> sanitizeEnemies(List<PlayerData> input, long nowMs) {
        if (input == null || input.isEmpty()) return new ArrayList<>();
        for (PlayerData p : input) {
            honeypotGuard.observe(p, nowMs);
        }
        List<PlayerData> filtered = honeypotGuard.renderable(input, nowMs);
        List<PlayerData> out = new ArrayList<>(filtered.size());
        for (PlayerData p : filtered) {
            if (honeypotGuard.trapValue(p.hp)) continue;
            if (honeypotGuard.trapValue(p.x) || honeypotGuard.trapValue(p.y)) continue;
            if (p.isEnemy && honeypotGuard.isQuarantined(p.id)) continue;
            out.add(p);
        }
        return out;
    }

    /** True when the overlay should drop to the safe stealth profile. */
    public boolean espStealth() {
        if (hardStopFlag.get()) return true;
        if (updateGuard.reducedMode()) return true;
        if (honeypotGuard.quarantinedCount() > 0) return true;
        if (honeypotGuard.reportWaveActive()) return true;
        return statsCloak.shouldCoolDown() && statsCloak.hotness() > 0.5d;
    }

    /** Whether the ESP may render at all this frame. */
    public boolean espAllowed() {
        if (hardStopFlag.get()) return false;
        if (updateGuard.reducedMode()) return false;
        if (procCloak.degraded()) return false;
        if (procCloak.quickTracerCheck()) return false;
        if (scanShield.isSuspended()) return false;
        return started.get();
    }

    /** Composite feature-intensity multiplier for the overlay draw. */
    public float espIntensity() {
        float base = behaviorCloak.featureIntensity();
        base *= statsCloak.aggressionFactor();
        base *= updateGuard.intensityFactor();
        if (scanShield.isDegraded()) base *= 0.5f;
        if (procCloak.degraded()) base *= 0.4f;
        return Math.max(0.05f, Math.min(1f, base));
    }

    /** Intensity for aim assist (extra human error when hot). */
    public float aimIntensity() {
        float i = espIntensity() * behaviorCloak.aimErrorFactor();
        if (statsCloak.shouldCoolDown()) i *= 0.5f;
        return Math.max(0.05f, Math.min(1f, i));
    }

    /** Whether vibration alerts may fire this frame. */
    public boolean allowVibrate() {
        if (espStealth()) return false;
        return !behaviorCloak.inBreak() && !statsCloak.shouldCoolDown();
    }

    // ------------------------------------------------------------------
    // Background cadence
    // ------------------------------------------------------------------

    /** Periodic maintenance: audits, pacing, update watch. */
    public void tick() {
        long now = System.currentTimeMillis();
        if (now - lastTickMs.get() < TICK_INTERVAL_MS) return;
        lastTickMs.set(now);

        if (now - lastAuditMs.get() > PROC_AUDIT_INTERVAL_MS) {
            lastAuditMs.set(now);
            List<String> findings = procCloak.audit(context);
            if (!findings.isEmpty()) {
                // Findings that suggest active interception → go quiet.
                if (procCloak.traced() || procCloak.debuggerAttached()) {
                    scanShield.suspend();
                }
            }
        }
        updateGuard.versionChanged();
        enemyLag.tick(now);
    }

    // ------------------------------------------------------------------
    // Network pacing
    // ------------------------------------------------------------------

    public boolean heartbeatAllowed() {
        return networkShield.requestAllowed() && networkShield.heartbeatDue();
    }

    public void markHeartbeatSent() {
        networkShield.scheduleNextHeartbeat();
    }

    public void noteHeartbeatSuccess() {
        networkShield.noteSuccess();
    }

    /** Feed a server-delivered offset DB through the update guard. */
    public boolean applyRemoteOffsets(String offsetDbJson) {
        return updateGuard.tryRemoteUpdate(offsetDbJson);
    }

    // ------------------------------------------------------------------
    // Hard stop
    // ------------------------------------------------------------------

    public boolean hardStop() {
        if (hardStopFlag.get()) return true;
        if (networkShield.killDrainComplete()) {
            hardStopFlag.set(true);
            enemyLag.forceStop();
            return true;
        }
        if (updateGuard.crashLoop()) {
            hardStopFlag.set(true);
            enemyLag.forceStop();
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Diagnostics
    // ------------------------------------------------------------------

    public static final class StackStats {
        public final boolean started;
        public final boolean hardStop;
        public final boolean espAllowed;
        public final float espIntensity;
        public final boolean stealth;
        public final int scanPressure;
        public final int procAnomalies;
        public final boolean procDegraded;
        public final int honeypotQuarantined;
        public final double statsHotness;
        public final boolean updateSuspended;
        public final String updateVersion;
        StackStats(boolean started, boolean hardStop, boolean espAllowed,
                   float espIntensity, boolean stealth, int scanPressure,
                   int procAnomalies, boolean procDegraded, int honeypotQuarantined,
                   double statsHotness, boolean updateSuspended, String updateVersion) {
            this.started = started;
            this.hardStop = hardStop;
            this.espAllowed = espAllowed;
            this.espIntensity = espIntensity;
            this.stealth = stealth;
            this.scanPressure = scanPressure;
            this.procAnomalies = procAnomalies;
            this.procDegraded = procDegraded;
            this.honeypotQuarantined = honeypotQuarantined;
            this.statsHotness = statsHotness;
            this.updateSuspended = updateSuspended;
            this.updateVersion = updateVersion;
        }
    }

    public StackStats stats() {
        return new StackStats(
                started.get(),
                hardStopFlag.get(),
                espAllowed(),
                espIntensity(),
                espStealth(),
                scanShield.pressureLevel(),
                procCloak.anomalyScore(),
                procCloak.degraded(),
                honeypotGuard.quarantinedCount(),
                statsCloak.hotness(),
                updateGuard.suspended(),
                updateGuard.detectVersion());
    }
}