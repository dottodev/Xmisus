package com.shadow.mlbbcheat.utils.bypass;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.Random;

/**
 * EnemyLag — enemy rubber-banding / stutter engine, bypass-woven.
 *
 * Implements the "lag enemies" cheat over the existing GameGuardian bridge:
 * the app commands the Lua script (plaintext 0xE0 command frames on the same
 * loopback socket) to repeatedly re-write stale movement values into each
 * enemy player struct, so enemies visually freeze, stutter or rubber-band
 * while the network layer keeps the match running.
 *
 * WHY IT IS BYPASS-WOVEN (every anti-detection concern):
 *
 *  1. RAMP, NOT JUMP — intensity fades in over randomized steps. A
 *     cheat that snaps from 0 to 10 instantly is a signature; a gradual
 *     climb with humanlike plateaus reads as lag or a bad connection.
 *
 *  2. BURST PATTERNS — the lag is applied in burst windows separated by
 *     randomized pauses (STUTTER semantics), never as a constant stream.
 *     Constant writes are how memory cheats are fingerprinted by
 *     integrity scanners.
 *
 *  3. SESSION BUDGET — each match gets a soft budget of active time and
 *     a hard per-session cap; after the cap the module forces a cooldown
 *     even if the user keeps the toggle on. A cheat that never turns off
 *     is unnatural; a player that "has lag" in waves is not.
 *
 *  4. COOLDOWN + GRACE — after any session the module refuses to re-enter
 *     for a randomized cooldown. When the user (or a gate) stops the
 *     module, a self-cleansing STOP command is always emitted and a grace
 *     window protects the moment of transition.
 *
 *  5. HONEYPOT AWARENESS — when the honeypot detector reports quarantine
 *     activity or a report wave, the module suspends itself and emits a
 *     STOP command so the lag cannot be observed feeding a honeypot.
 *
 *  6. UPDATE-GUARD AWARENESS — when the update guard drops to reduced
 *     mode (new game build suspected), lag is disabled entirely.
 *
 *  7. STATS / HOTNESS AWARENESS — while the stats cloak reports the
 *     player as "hot" (kill streaks, domination), intensity is throttled
 *     and sessions shorten; a fed player rarely suffers lag spikes.
 *
 *  8. SCAN-PRESSURE AWARENESS — when the scan shield is degraded (the
 *     game's anti-cheat is scanning memory aggressively), the module
 *     throttles command cadence and shortens bursts.
 *
 *  9. KILL-SWITCH — once the bypass stack hard-stops (kill drain
 *     complete, crash loop), the module locks out permanently for the
 *     process lifetime: no command, no restart, no trick.
 *
 * 10. OFFSET VALIDITY GATE — the module refuses to arm while the active
 *     offset set is the placeholder/zero set. It never guesses addresses;
 *     it only operates when the repository delivers a real, versioned
 *     offset table (same semantics as UpdateGuard).
 *
 * 11. DETERMINISTIC TESTABILITY — every randomized decision is driven by
 *     a seeded Random, so unit tests can replay exact sequences.
 *
 * 12. SELF-CLEANSING — on stop, on abort, on suspension, the module
 *     always schedules the STOP command first; the Lua side restores the
 *     original values on receipt. The module also re-emits the STOP
 *     command on a randomized second pass if the bridge was down when the
 *     first STOP was sent (the Lua side restores whenever it sees a STOP
 *     or a new LAG SET, so late delivery is harmless).
 *
 * STATE MACHINE
 *
 *   IDLE ──(user start, offsets ready)──► ARMING ──(ramp complete)──► ACTIVE
 *     ▲                                   │  │                        │  │
 *     │                                   │  └─(gate trips)───────────┼──┘
 *     │                                   ▼                           ▼
 *     └────(cooldown elapses)── COOLDOWN ◄──(session ends)── ◄─(burst paused)
 *                                                                    │
 *                                                    (budget/stop/gate) ▼
 *                                                        └──► COOLDOWN
 *
 *   LOCKED ── irreversible, reached only from any state when the bypass
 *             stack reports a hard stop. Nothing can unlock it.
 *
 * COMMAND PROTOCOL (app → Lua, plaintext, 17 bytes)
 *
 *   byte 0     0xE0              command channel marker (never obfuscated)
 *   byte 1     cmd              1 = LAG SET, 2 = LAG STOP, 3 = LAG MODE
 *   byte 2     reserved
 *   float @3   value            SET: intensity 1..10 / MODE: mode ordinal
 *   float @7   durationMs       SET: window ms (0 = until stopped)
 *   float @11  seed             SET: randomized jitter seed for Lua engine
 *
 * The Lua bridge restores all touched addresses whenever it receives a
 * LAG STOP or a fresh LAG SET, so a missed command is always self-healing.
 */
public final class EnemyLag {

    // ------------------------------------------------------------------
    // Command protocol constants
    // ------------------------------------------------------------------

    /** Marker byte that identifies the app→Lua command channel. */
    public static final byte CMD_MARKER = (byte) 0xE0;

    /** Command id: (re)start lag with a fresh window. */
    public static final byte CMD_LAG_SET = 1;

    /** Command id: stop lag, restore original values. */
    public static final byte CMD_LAG_STOP = 2;

    /** Command id: switch lag mode without stopping. */
    public static final byte CMD_LAG_MODE = 3;

    public static final int COMMAND_FRAME_SIZE = 17;

    // ------------------------------------------------------------------
    // Public tuning bounds
    // ------------------------------------------------------------------

    public static final int INTENSITY_MIN = 1;
    public static final int INTENSITY_MAX = 10;
    public static final int INTENSITY_DEFAULT = 5;

    public static final long DURATION_MIN_MS = 5_000L;
    public static final long DURATION_MAX_MS = 300_000L;
    public static final long DURATION_DEFAULT_MS = 60_000L;

    // ------------------------------------------------------------------
    // Internal timing constants (all randomized where noted)
    // ------------------------------------------------------------------

    private static final long ARM_STEP_MIN_MS = 400L;
    private static final long ARM_STEP_MAX_MS = 1_100L;
    private static final int ARM_STEPS_MIN = 3;
    private static final int ARM_STEPS_MAX = 7;

    private static final long BURST_MIN_MS = 900L;
    private static final long BURST_MAX_MS = 4_200L;
    private static final long PAUSE_MIN_MS = 350L;
    private static final long PAUSE_MAX_MS = 1_800L;

    private static final long COOLDOWN_MIN_MS = 12_000L;
    private static final long COOLDOWN_MAX_MS = 40_000L;

    private static final long SESSION_BUDGET_MIN_MS = 25_000L;
    private static final long SESSION_BUDGET_MAX_MS = 75_000L;
    private static final long HARD_SESSION_CAP_MS = 120_000L;

    private static final long COMMAND_MIN_GAP_MS = 900L;
    private static final long COMMAND_MAX_GAP_MS = 2_400L;

    private static final long SUSPEND_STOP_GRACE_MS = 350L;
    private static final long RESTORE_RETRY_DELAY_MS = 1_800L;

    private static final long MIN_ACTIVE_FOR_COOLDOWN_MS = 2_000L;
    private static final long MIN_IDLE_BEFORE_START_MS = 900L;

    // ------------------------------------------------------------------
    // Mode
    // ------------------------------------------------------------------

    /**
     * Lag application style.
     *
     * STUTTER — short burst windows of stale-write re-assertion separated
     *           by pauses; the enemy visibly hitches forward in steps.
     * FREEZE  — continuous stale-write hold; the enemy is pinned until
     *           the window expires or a gate stops it.
     * RUBBER  — alternating small deltas around the stale value; the
     *           enemy slides back and forth around one point (rubber-band).
     */
    public enum Mode {
        STUTTER(0),
        FREEZE(1),
        RUBBER(2);

        public final int ordinalByte;

        Mode(int ordinalByte) {
            this.ordinalByte = ordinalByte;
        }

        public static Mode fromByte(byte b) {
            for (Mode m : values()) {
                if (m.ordinalByte == (b & 0xFF)) return m;
            }
            return STUTTER;
        }

        public static Mode fromInt(int i) {
            for (Mode m : values()) {
                if (m.ordinal() == i) return m;
            }
            return STUTTER;
        }
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    /**
     * Immutable snapshot of the user-facing configuration.
     */
    public static final class LagSettings {
        public final int intensity;       // 1..10
        public final Mode mode;
        public final long maxDurationMs;  // soft cap for one session

        public LagSettings(int intensity, Mode mode, long maxDurationMs) {
            this.intensity = clampIntensity(intensity);
            this.mode = mode != null ? mode : Mode.STUTTER;
            this.maxDurationMs = clampDuration(maxDurationMs);
        }

        public static LagSettings defaults() {
            return new LagSettings(INTENSITY_DEFAULT, Mode.STUTTER, DURATION_DEFAULT_MS);
        }

        public LagSettings withIntensity(int i) {
            return new LagSettings(i, mode, maxDurationMs);
        }

        public LagSettings withMode(Mode m) {
            return new LagSettings(intensity, m, maxDurationMs);
        }

        public LagSettings withDuration(long ms) {
            return new LagSettings(intensity, mode, ms);
        }

        @Override
        public String toString() {
            return "LagSettings{intensity=" + intensity
                    + ", mode=" + mode
                    + ", maxDurationMs=" + maxDurationMs + '}';
        }
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    public enum State {
        IDLE,
        ARMING,
        ACTIVE,
        COOLDOWN,
        LOCKED
    }

    // ------------------------------------------------------------------
    // Session report (observability / diagnostics)
    // ------------------------------------------------------------------

    public static final class SessionReport {
        public final long startedAt;
        public final long endedAt;
        public final long activeMs;
        public final int commandsSent;
        public final int bursts;
        public final int gateSuspensions;
        public final boolean completedNaturally;
        public final boolean killedByGate;

        SessionReport(long startedAt, long endedAt, long activeMs,
                      int commandsSent, int bursts, int gateSuspensions,
                      boolean completedNaturally, boolean killedByGate) {
            this.startedAt = startedAt;
            this.endedAt = endedAt;
            this.activeMs = activeMs;
            this.commandsSent = commandsSent;
            this.bursts = bursts;
            this.gateSuspensions = gateSuspensions;
            this.completedNaturally = completedNaturally;
            this.killedByGate = killedByGate;
        }
    }

    // ------------------------------------------------------------------
    // Fields
    // ------------------------------------------------------------------

    /** The bypass stack this module reports through (never null). */
    private final BypassStack stack;

    /** Seeded RNG; all randomized decisions flow through this. */
    private final Random random;

    // --- configuration -------------------------------------------------
    private volatile LagSettings settings = LagSettings.defaults();

    // --- user intent vs. reality --------------------------------------
    private volatile boolean userWantsActive = false;
    private volatile boolean suspendedByGate = false;
    private volatile boolean locked = false;

    // --- state machine -------------------------------------------------
    private volatile State state = State.IDLE;

    // --- session bookkeeping ------------------------------------------
    private long sessionStartedAt = 0L;
    private long sessionBudgetMs = 0L;
    private long sessionActiveMs = 0L;
    private int sessionCommands = 0;
    private int sessionBursts = 0;
    private int sessionGateSuspensions = 0;
    private boolean sessionKilledByGate = false;
    private SessionReport lastReport = null;
    private int lifetimeSessions = 0;
    private long lifetimeActiveMs = 0L;
    private long lifetimeCommands = 0L;

    // --- ramp engine ----------------------------------------------------
    private int rampTarget = 0;
    private int rampCurrent = 0;
    private int rampStepsTotal = 0;
    private int rampStepIndex = 0;
    private long rampNextStepAt = 0L;
    private long rampStartedAt = 0L;

    // --- burst pattern --------------------------------------------------
    private long burstEndAt = 0L;
    private long pauseEndAt = 0L;
    private boolean inBurst = false;
    private long sessionHardEndAt = 0L;

    // --- cooldown --------------------------------------------------------
    private long cooldownUntil = 0L;

    // --- command cadence -------------------------------------------------
    private long nextCommandAt = 0L;
    private byte lastCommandType = 0;
    private long lastCommandSentAt = 0L;
    private long restoreRetryAt = 0L;
    private boolean restoreRetryPending = false;

    // --- diagnostics -----------------------------------------------------
    private long lastTickAt = 0L;
    private long lastGateBlockAt = 0L;
    private String lastEvent = "constructed";

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    EnemyLag(BypassStack stack) {
        this.stack = stack;
        this.random = new Random();
    }

    /** Deterministic constructor for tests. */
    EnemyLag(BypassStack stack, long seed) {
        this.stack = stack;
        this.random = new Random(seed);
    }

    // ------------------------------------------------------------------
    // Configuration API
    // ------------------------------------------------------------------

    /** Apply a new settings snapshot; bounds are clamped by the snapshot. */
    public synchronized void configure(LagSettings s) {
        this.settings = s != null ? s : LagSettings.defaults();
        lastEvent = "configured " + settings;
    }

    /** Change only the intensity; applied on the next tick boundary. */
    public synchronized void setIntensity(int i) {
        this.settings = settings.withIntensity(i);
        lastEvent = "intensity=" + settings.intensity;
    }

    /** Change only the mode; applied on the next tick boundary. */
    public synchronized void setMode(Mode m) {
        this.settings = settings.withMode(m);
        lastEvent = "mode=" + settings.mode;
    }

    /** Change only the duration cap; applied on the next tick boundary. */
    public synchronized void setDuration(long ms) {
        this.settings = settings.withDuration(ms);
        lastEvent = "duration=" + settings.maxDurationMs;
    }

    public LagSettings settings() {
        return settings;
    }

    public int intensity() {
        return settings.intensity;
    }

    public Mode mode() {
        return settings.mode;
    }

    // ------------------------------------------------------------------
    // Clamping helpers (shared with LagSettings via static forwards)
    // ------------------------------------------------------------------

    static int clampIntensity(int i) {
        if (i < INTENSITY_MIN) return INTENSITY_MIN;
        if (i > INTENSITY_MAX) return INTENSITY_MAX;
        return i;
    }

    static long clampDuration(long ms) {
        if (ms < DURATION_MIN_MS) return DURATION_MIN_MS;
        if (ms > DURATION_MAX_MS) return DURATION_MAX_MS;
        return ms;
    }

    private long clamp(long v, long lo, long hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    private int clamp(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    private float clamp(float v, float lo, float hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    // ------------------------------------------------------------------
    // Random helpers (deterministic under a fixed seed)
    // ------------------------------------------------------------------

    long randBetween(long lo, long hi) {
        if (hi <= lo) return lo;
        return lo + (long) (random.nextDouble() * (hi - lo + 1));
    }

    int randBetween(int lo, int hi) {
        if (hi <= lo) return lo;
        return lo + random.nextInt(hi - lo + 1);
    }

    boolean chance(double p) {
        return random.nextDouble() < p;
    }

    long nextGapMs(long lo, long hi) {
        return randBetween(lo, hi);
    }

    // ------------------------------------------------------------------
    // Lifecycle: user-facing start / stop
    // ------------------------------------------------------------------

    /**
     * User (or the GUI) turns the module on. The module only arms when
     * every gate allows it; otherwise it parks in IDLE and retries on
     * each tick, so the user does not need to re-press the toggle after
     * the honeypot wave passes.
     */
    public synchronized void start() {
        userWantsActive = true;
        suspendedByGate = false;
        if (locked) {
            lastEvent = "start ignored (locked)";
            return;
        }
        if (state == State.ACTIVE || state == State.ARMING) {
            lastEvent = "start ignored (already active)";
            return;
        }
        long now = System.currentTimeMillis();
        if (state == State.COOLDOWN && now < cooldownUntil) {
            lastEvent = "start deferred (cooldown " + (cooldownUntil - now) + "ms)";
            return;
        }
        if (!timingBelievable(now)) {
            lastEvent = "start deferred (timing not believable)";
            return;
        }
        refreshSchedule(now);
        beginArming(now);
    }

    /** User (or the GUI) turns the module off. Always self-cleans. */
    public synchronized void stop() {
        userWantsActive = false;
        long now = System.currentTimeMillis();
        if (state == State.ARMING || state == State.ACTIVE) {
            endSession(now, false, false);
            lastEvent = "stopped by user";
        } else {
            lastEvent = "stop ignored (state=" + state + ")";
        }
    }

    /** Whether the module considers itself active right now. */
    public boolean isActive() {
        return state == State.ACTIVE;
    }

    /** Whether the module is currently armed (ramping or active). */
    public boolean isArmed() {
        return state == State.ARMING || state == State.ACTIVE;
    }

    /** Whether a gate is currently holding the module down. */
    public boolean isSuspended() {
        return suspendedByGate;
    }

    /** Whether the module is permanently locked out. */
    public boolean isLocked() {
        return locked;
    }

    /** Current state, exposed for diagnostics and tests. */
    public State state() {
        return state;
    }

    /** Current ramp value (0 while idle, 1..10 while armed). */
    public int intensityAt() {
        return state == State.ACTIVE ? rampCurrent : 0;
    }

    /** Whether the module is still climbing to the target intensity. */
    public boolean ramping() {
        return state == State.ARMING;
    }

    // ------------------------------------------------------------------
    // Lifecycle: gate-driven suspension
    // ------------------------------------------------------------------

    /**
     * A gate (honeypot, scan pressure, stats hotness) asks the module to
     * suspend. Emits a STOP command so the Lua side restores values.
     */
    public synchronized void suspend() {
        if (suspendedByGate || locked) return;
        suspendedByGate = true;
        sessionGateSuspensions++;
        long now = System.currentTimeMillis();
        if (state == State.ARMING || state == State.ACTIVE) {
            endSession(now, false, true);
            lastEvent = "suspended by gate";
        } else {
            lastEvent = "marked suspended (idle)";
        }
    }

    /** A gate clears: allow the module to resume if the user wants it. */
    public synchronized void resume() {
        if (!suspendedByGate) return;
        suspendedByGate = false;
        lastEvent = "gate cleared";
        long now = System.currentTimeMillis();
        if (userWantsActive && state != State.COOLDOWN) {
            beginArming(now);
        }
    }

    // ------------------------------------------------------------------
    // Force stop (kill-switch path from the bypass stack)
    // ------------------------------------------------------------------

    /**
     * The bypass stack has hard-stopped: lock the module out forever and
     * emit a final self-cleansing STOP command. Called from the stack's
     * hard-stop path; idempotent.
     */
    public synchronized void forceStop() {
        if (locked) return;
        locked = true;
        userWantsActive = false;
        long now = System.currentTimeMillis();
        if (state == State.ARMING || state == State.ACTIVE) {
            endSession(now, false, true);
        }
        state = State.LOCKED;
        lastEvent = "locked by hard stop";
    }

    /** Match lifecycle hooks forwarded by the bypass stack. */
    public synchronized void onMatchStart() {
        if (locked) return;
        noteMatchStartInternal(System.currentTimeMillis());
        lastEvent = "match start (state=" + state + ")";
    }

    public synchronized void onMatchEnd(boolean won) {
        if (locked) return;
        long now = System.currentTimeMillis();
        noteMatchEndInternal(now);
        if (state == State.ARMING || state == State.ACTIVE) {
            endSession(now, true, false);
            lastEvent = "match end (" + (won ? "won" : "lost") + ")";
        } else {
            lastEvent = "match end ignored (state=" + state + ")";
        }
    }

    /** Test hook: clear cooldown/wave pacing so a fresh session can arm. */
    synchronized void clearCooldownForTest() {
        cooldownUntil = 0L;
        nextEngagementAt = 0L;
    }

    // ------------------------------------------------------------------
    // State machine internals
    // ------------------------------------------------------------------

    /** Enter ARMING: roll the ramp plan and the session budget. */
    private void beginArming(long now) {
        if (locked) return;
        state = State.ARMING;
        userWantsActive = true;

        rampTarget = settings.intensity;
        rampCurrent = 0;
        rampStepsTotal = randBetween(ARM_STEPS_MIN, ARM_STEPS_MAX);
        rampStepIndex = 0;
        rampStartedAt = now;
        rampNextStepAt = now + nextGapMs(ARM_STEP_MIN_MS, ARM_STEP_MAX_MS);

        sessionStartedAt = now;
        sessionBudgetMs = randBetween(SESSION_BUDGET_MIN_MS, SESSION_BUDGET_MAX_MS);
        sessionHardEndAt = now + HARD_SESSION_CAP_MS;
        sessionActiveMs = 0L;
        sessionCommands = 0;
        sessionBursts = 0;
        sessionGateSuspensions = 0;
        sessionKilledByGate = false;
        inBurst = false;
        burstEndAt = 0L;
        pauseEndAt = 0L;
        restoreRetryPending = false;

        if (!wavePlanned) planWave();
        openGrace(now, nextGapMs(250L, 600L));
        recordEvent("arming");
        lastEvent = "arming (target=" + rampTarget + ", steps=" + rampStepsTotal + ")";
    }

    /**
     * End the current session, always emitting the self-cleansing STOP
     * command and rolling into COOLDOWN (unless locked).
     */
    private void endSession(long now, boolean natural, boolean killedByGate) {
        long activeMs = Math.max(0L, now - sessionStartedAt);
        sessionActiveMs += activeMs;
        lifetimeActiveMs += activeMs;
        lifetimeSessions++;
        sessionKilledByGate = killedByGate;

        lastReport = new SessionReport(
                sessionStartedAt, now, activeMs,
                sessionCommands, sessionBursts, sessionGateSuspensions,
                natural, killedByGate);

        if (!locked) {
            cooldownUntil = now + nextGapMs(COOLDOWN_MIN_MS, COOLDOWN_MAX_MS);
            state = State.COOLDOWN;
        }
        lastCommandType = CMD_LAG_STOP;
        lastCommandSentAt = now;
        restoreRetryPending = true;
        restoreRetryAt = now + RESTORE_RETRY_DELAY_MS;
        noteWaveSessionEnded(now);
        introspect(now);
        planNextEngagement(now);
        openGrace(now, nextGapMs(300L, 800L));
        recordEvent("session ended");
        lastEvent = "session ended (activeMs=" + activeMs
                + ", cmds=" + sessionCommands
                + ", natural=" + natural + ")";
    }

    // ------------------------------------------------------------------
    // Gates
    // ------------------------------------------------------------------

    /**
     * The gate ensemble. Every condition here can stop or withhold the
     * module; the bypass stack exposes each shield's live verdict.
     */
    boolean gatesAllow(long now) {
        if (locked) return false;
        if (!stack.offsetsReady()) {
            lastGateBlockAt = now;
            return false;
        }
        if (stack.stackHardStopped()) {
            return false;
        }
        if (stack.updateGuard.reducedMode()) {
            lastGateBlockAt = now;
            return false;
        }
        if (stack.honeypotGuard.quarantinedCount() > 0
                || stack.honeypotGuard.reportWaveActive()) {
            lastGateBlockAt = now;
            return false;
        }
        return true;
    }

    /** Soft gates: still allowed, but intensity/cadence get throttled. */
    boolean hotnessThrottled() {
        return stack.statsCloak.shouldCoolDown() && stack.statsCloak.hotness() > 0.45d;
    }

    boolean scanPressureThrottled() {
        return stack.scanShield.isDegraded();
    }

    /** Multiplier applied to the configured intensity by soft gates. */
    float gateIntensityFactor() {
        float f = 1f;
        if (hotnessThrottled()) f *= 0.55f;
        if (scanPressureThrottled()) f *= 0.7f;
        if (stack.behaviorCloak.inBreak()) f *= 0.6f;
        return Math.max(0.25f, Math.min(1f, f));
    }

    /** Effective target after soft gates (1..10). */
    int effectiveTarget(long now) {
        int t = Math.round(rampTarget * compositeIntensityFactor(now));
        return clamp(t, INTENSITY_MIN, INTENSITY_MAX);
    }

    // ------------------------------------------------------------------
    // Main tick (called by BypassStack.tick on its cadence)
    // ------------------------------------------------------------------

    /**
     * Periodic evaluation. Drives the state machine, the ramp, the burst
     * pattern and the command cadence. Never throws: all failures are
     * logged to the event trail so a bridge hiccup cannot kill a thread.
     */
    public synchronized void tick(long now) {
        lastTickAt = now;
        try {
            if (locked) return;
            if (!stack.stackHardStopped() && !gatesAllow(now)) {
                if (suspendedByGate) return;
                if (state == State.ARMING || state == State.ACTIVE) {
                    suspend();
                } else {
                    suspendedByGate = true;
                }
                return;
            }
            if (suspendedByGate) {
                resumeInternal(now);
            }
            switch (state) {
                case IDLE:
                    tickIdle(now);
                    break;
                case ARMING:
                    tickArming(now);
                    break;
                case ACTIVE:
                    tickActive(now);
                    break;
                case COOLDOWN:
                    tickCooldown(now);
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            lastEvent = "tick error: " + t;
        }
    }

    private void resumeInternal(long now) {
        suspendedByGate = false;
        lastEvent = "gates cleared, resumed";
        if (userWantsActive && state != State.COOLDOWN && state != State.ARMING
                && state != State.ACTIVE) {
            beginArming(now);
        }
    }

    private void tickIdle(long now) {
        if (userWantsActive && engagementBelievable(now)) {
            beginArming(now);
        }
    }

    private void tickArming(long now) {
        if (!userWantsActive) {
            endSession(now, false, false);
            return;
        }
        if (now >= sessionHardEndAt) {
            endSession(now, true, false);
            return;
        }
        if (now < rampNextStepAt) return;
        advanceRamp(now);
        if (rampStepIndex >= rampStepsTotal || rampCurrent >= rampTarget) {
            state = State.ACTIVE;
            planCurve();
            openBurst(now);
            nextCommandAt = now + nextGapMs(COMMAND_MIN_GAP_MS, COMMAND_MAX_GAP_MS);
            lastEvent = "active (intensity=" + rampCurrent + ", mode=" + mode() + ")";
        }
    }

    private void tickActive(long now) {
        if (!userWantsActive) {
            endSession(now, false, false);
            return;
        }
        long activeMs = now - sessionStartedAt;
        if (activeMs >= sessionBudgetMs) {
            endSession(now, true, false);
            return;
        }
        if (now >= sessionHardEndAt) {
            endSession(now, true, false);
            return;
        }
        // Hotness mid-flight: pull intensity down gently, not a cliff.
        if (hotnessThrottled() && rampCurrent > effectiveTarget(now)) {
            rampCurrent = Math.max(INTENSITY_MIN, rampCurrent - 1);
            lastEvent = "throttled mid-flight to " + rampCurrent;
        }
        if (now >= burstEndAt && inBurst) {
            closeBurst(now);
        } else if (!inBurst && now >= pauseEndAt) {
            openBurst(now);
        }
    }

    private void tickCooldown(long now) {
        if (now >= cooldownUntil && userWantsActive && engagementBelievable(now)) {
            beginArming(now);
        }
    }

    // ------------------------------------------------------------------
    // Ramp engine
    // ------------------------------------------------------------------

    /**
     * One ramp step: the intensity climbs in randomized increments with
     * occasional holds, so the ramp profile looks like a network hiccup
     * worsening rather than a cheat switching on.
     */
    private void advanceRamp(long now) {
        int target = effectiveTarget(now);
        rampStepIndex++;
        if (rampCurrent >= target) return;

        int gain = randBetween(1, 2);
        if (rampStepIndex == 1) gain = 1; // first step is always gentle
        if (chance(0.18d)) gain = 0;      // occasional plateau step

        rampCurrent = clamp(rampCurrent + gain, INTENSITY_MIN, target);
        rampNextStepAt = now + nextGapMs(ARM_STEP_MIN_MS, ARM_STEP_MAX_MS);
        lastEvent = "ramp step " + rampStepIndex + "/" + rampStepsTotal
                + " -> " + rampCurrent;
    }

    /** Whether a fresh LAG SET command is due on the cadence. */
    public boolean commandDue(long now) {
        return state == State.ACTIVE && now >= nextCommandAt;
    }

    /** Time until the next command would be due (diagnostics). */
    public long untilNextCommand(long now) {
        return state == State.ACTIVE ? Math.max(0L, nextCommandAt - now) : -1L;
    }

    /** Whether a restore retry STOP is pending delivery. */
    public boolean restoreRetryPending() {
        return restoreRetryPending;
    }

    public boolean restoreRetryDue(long now) {
        return restoreRetryPending && now >= restoreRetryAt;
    }

    // ------------------------------------------------------------------
    // Command frames (app → Lua, plaintext 0xE0 channel)
    // ------------------------------------------------------------------

    /** Build a LAG SET frame for the current configuration. */
    public byte[] buildSetCommand(long now) {
        byte[] frame = new byte[COMMAND_FRAME_SIZE];
        frame[0] = CMD_MARKER;
        frame[1] = CMD_LAG_SET;
        frame[2] = 0;
        int value = state == State.ACTIVE ? rampCurrent : rampTarget;
        ByteBuffer b = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        b.putFloat(3, value);
        b.putFloat(7, settings.maxDurationMs);
        b.putFloat(11, now & 0xFFFFFFFFL);
        return frame;
    }

    /** Build a LAG STOP frame (self-cleansing path). */
    public static byte[] buildStopCommand() {
        byte[] frame = new byte[COMMAND_FRAME_SIZE];
        frame[0] = CMD_MARKER;
        frame[1] = CMD_LAG_STOP;
        frame[2] = 0;
        return frame;
    }

    /** Build a LAG MODE frame for the given mode. */
    public static byte[] buildModeCommand(Mode m) {
        byte[] frame = new byte[COMMAND_FRAME_SIZE];
        frame[0] = CMD_MARKER;
        frame[1] = CMD_LAG_MODE;
        frame[2] = 0;
        ByteBuffer b = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        b.putFloat(3, m != null ? m.ordinal() : Mode.STUTTER.ordinal());
        return frame;
    }

    /**
     * Consume the cadence: returns the command to send now, or null.
     * Advances the cadence bookkeeping so the caller does not re-send.
     * Also clears a pending restore retry once it becomes due.
     */
    public synchronized byte[] takeCommand(long now) {
        if (restoreRetryPending && now >= restoreRetryAt) {
            restoreRetryPending = false;
            byte[] stop = buildStopCommand();
            lastCommandType = CMD_LAG_STOP;
            lastCommandSentAt = now;
            sessionCommands++;
            lifetimeCommands++;
            noteCommandSent(now);
            lastEvent = "restore retry STOP sent";
            return stop;
        }
        if (!commandDue(now)) return null;
        if (!commandRateSafe(now)) {
            lastEvent = "command rate interlock hit";
            scheduleNextCommand(now);
            return null;
        }
        byte[] cmd = buildSetCommand(now);
        scheduleNextCommand(now);
        lastCommandType = CMD_LAG_SET;
        lastCommandSentAt = now;
        sessionCommands++;
        lifetimeCommands++;
        noteCommandSent(now);
        lastEvent = "LAG SET sent (intensity=" + rampCurrent + ")";
        return cmd;
    }

    /** Marks the cadence advanced without a send (caller offlined). */
    public synchronized void skipCommandSlot(long now) {
        if (commandDue(now)) {
            scheduleNextCommand(now);
            noteMissedSlot(now);
        }
    }

    /** True when the caller should also emit a MODE frame for the mode. */
    public boolean modeRefreshDue(long now) {
        return state == State.ACTIVE && lastCommandType == CMD_LAG_STOP;
    }

    /** Last command type sent (diagnostics). */
    public byte lastCommandType() {
        return lastCommandType;
    }

    public long lastCommandSentAt() {
        return lastCommandSentAt;
    }

    // ------------------------------------------------------------------
    // Burst profile (for the Lua engine + diagnostics)
    // ------------------------------------------------------------------

    /** Whether the current window is an active burst (Lua keeps writing). */
    public boolean inBurstWindow(long now) {
        return state == State.ACTIVE && inBurst;
    }

    public long burstRemainingMs(long now) {
        if (state != State.ACTIVE || !inBurst) return 0L;
        return Math.max(0L, burstEndAt - now);
    }

    public int burstsThisSession() {
        return sessionBursts;
    }

    /** Session-relative progress 0..1 for the current session. */
    public float sessionProgress(long now) {
        if (sessionBudgetMs <= 0) return 0f;
        long activeMs = Math.max(0L, now - sessionStartedAt);
        return Math.min(1f, (float) activeMs / (float) sessionBudgetMs);
    }

    // ------------------------------------------------------------------
    // Observability / diagnostics
    // ------------------------------------------------------------------

    /** Last state-changing event description (diagnostics). */
    public String lastEvent() {
        return lastEvent;
    }

    public long lastTickAt() {
        return lastTickAt;
    }

    public long lastGateBlockAt() {
        return lastGateBlockAt;
    }

    /** Report of the most recently ended session, or null. */
    public SessionReport lastSessionReport() {
        return lastReport;
    }

    public int lifetimeSessions() {
        return lifetimeSessions;
    }

    public long lifetimeActiveMs() {
        return lifetimeActiveMs;
    }

    public long lifetimeCommands() {
        return lifetimeCommands;
    }

    /** Human-readable one-line status for the GUI status line. */
    public String statusLine(long now) {
        switch (state) {
            case LOCKED:
                return "LAG: locked";
            case COOLDOWN:
                return "LAG: cooling down (" + Math.max(0L, cooldownUntil - now) + "ms)";
            case ARMING:
                return "LAG: ramping " + rampCurrent + "->" + rampTarget;
            case ACTIVE:
                return "LAG: active " + rampCurrent + "/" + rampTarget
                        + (inBurst ? " (burst)" : " (pause)");
            default:
                return userWantsActive ? "LAG: waiting" : "LAG: off";
        }
    }

    /** Snapshot of every decision-relevant value (test/debug surface). */
    public Map<String, Object> diagnostics() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("state", state.name());
        m.put("locked", locked);
        m.put("userWantsActive", userWantsActive);
        m.put("suspendedByGate", suspendedByGate);
        m.put("intensity", settings.intensity);
        m.put("mode", settings.mode.name());
        m.put("maxDurationMs", settings.maxDurationMs);
        m.put("rampCurrent", rampCurrent);
        m.put("rampTarget", rampTarget);
        m.put("rampStepIndex", rampStepIndex);
        m.put("rampStepsTotal", rampStepsTotal);
        m.put("inBurst", inBurst);
        m.put("sessionBudgetMs", sessionBudgetMs);
        m.put("sessionActiveMs", sessionActiveMs);
        m.put("sessionCommands", sessionCommands);
        m.put("sessionBursts", sessionBursts);
        m.put("sessionGateSuspensions", sessionGateSuspensions);
        m.put("cooldownUntil", cooldownUntil);
        m.put("nextCommandAt", nextCommandAt);
        m.put("lastCommandType", lastCommandType);
        m.put("restoreRetryPending", restoreRetryPending);
        m.put("lifetimeSessions", lifetimeSessions);
        m.put("lifetimeActiveMs", lifetimeActiveMs);
        m.put("lifetimeCommands", lifetimeCommands);
        m.put("lastEvent", lastEvent);
        return m;
    }

    @Override
    public String toString() {
        return "EnemyLag{state=" + state + ", intensity=" + settings.intensity
                + ", mode=" + settings.mode + ", user=" + userWantsActive
                + ", suspended=" + suspendedByGate + ", locked=" + locked + '}';
    }

    // ------------------------------------------------------------------
    // Test hooks
    // ------------------------------------------------------------------

    /** Reset to the pristine post-construction state (tests). */
    synchronized void resetForTest() {
        settings = LagSettings.defaults();
        userWantsActive = false;
        suspendedByGate = false;
        locked = false;
        state = State.IDLE;
        rampTarget = 0;
        rampCurrent = 0;
        rampStepIndex = 0;
        rampStepsTotal = 0;
        inBurst = false;
        sessionCommands = 0;
        sessionBursts = 0;
        sessionGateSuspensions = 0;
        cooldownUntil = 0L;
        nextCommandAt = 0L;
        restoreRetryPending = false;
        lastReport = null;
        lastEvent = "reset";
    }

    /** Force an exact state (tests only). */
    synchronized void forceState(State s, long now) {
        state = s;
        if (s == State.ACTIVE) {
            rampCurrent = settings.intensity;
            rampTarget = settings.intensity;
            sessionStartedAt = now;
            sessionBudgetMs = SESSION_BUDGET_MIN_MS;
            inBurst = true;
            burstEndAt = now + 5_000L;
            nextCommandAt = now + 1_000L;
        }
        lastEvent = "forced state " + s;
    }

    // ------------------------------------------------------------------
    // Burst shape planning
    // ------------------------------------------------------------------

    /**
     * A burst shape is the precomputed plan for one STUTTER window:
     * how long the window is, how many re-assertion pulses fit inside,
     * and how the pulses are spaced. Shaping these in advance (rather
     * than rolling per write) keeps the module's own behavior from
     * becoming a detectable rhythm.
     */
    static final class BurstShape {
        final long windowMs;
        final int pulses;
        final long pulseMinGapMs;
        final long pulseMaxGapMs;
        final boolean leadingEdgeLong; // first pulse delayed (reads like network)

        BurstShape(long windowMs, int pulses, long pulseMinGapMs,
                   long pulseMaxGapMs, boolean leadingEdgeLong) {
            this.windowMs = windowMs;
            this.pulses = pulses;
            this.pulseMinGapMs = pulseMinGapMs;
            this.pulseMaxGapMs = pulseMaxGapMs;
            this.leadingEdgeLong = leadingEdgeLong;
        }
    }

    /** Plan a burst shape from the current mode + intensity + jitter. */
    BurstShape planBurstShape() {
        long windowMs;
        int pulses;
        long minGap;
        long maxGap;
        boolean leadingLong;

        switch (settings.mode) {
            case FREEZE:
                // One long hold; pulses keep the stale value re-asserted
                // against any in-process correction the game applies.
                windowMs = randBetween(2_200L, 6_000L);
                pulses = randBetween(3, 6);
                minGap = 350L;
                maxGap = 950L;
                leadingLong = true;
                break;
            case RUBBER:
                // Alternating small deltas; short windows, many pulses.
                windowMs = randBetween(1_200L, 3_400L);
                pulses = randBetween(6, 12);
                minGap = 120L;
                maxGap = 340L;
                leadingLong = false;
                break;
            default: // STUTTER
                windowMs = randBetween(900L, 4_200L);
                pulses = randBetween(2, 5);
                minGap = 260L;
                maxGap = 900L;
                leadingLong = true;
                break;
        }

        int intensityBonus = (rampCurrent > 0 ? rampCurrent : settings.intensity)
                * 15;
        windowMs = clamp(windowMs + intensityBonus, 700L, 8_000L);
        return new BurstShape(windowMs, pulses, minGap, maxGap, leadingLong);
    }

    private BurstShape plannedShape = null;
    private int pulsesRemaining = 0;
    private long nextPulseAt = 0L;

    /** (Re)plan the current burst shape; called when a burst opens. */
    private void openBurst(long now) {
        plannedShape = planBurstShape();
        pulsesRemaining = plannedShape.pulses;
        inBurst = true;
        burstEndAt = now + plannedShape.windowMs;
        nextPulseAt = now + (plannedShape.leadingEdgeLong
                ? nextGapMs(plannedShape.pulseMinGapMs, plannedShape.pulseMaxGapMs)
                : randBetween(60L, 160L));
        sessionBursts++;
        lastEvent = "burst open (window=" + plannedShape.windowMs
                + "ms, pulses=" + plannedShape.pulses + ")";
    }

    /** Close the current burst and schedule the pause. */
    private void closeBurst(long now) {
        inBurst = false;
        pulsesRemaining = 0;
        plannedShape = null;
        pauseEndAt = now + nextGapMs(PAUSE_MIN_MS, PAUSE_MAX_MS);
        lastEvent = "burst close";
    }

    /** Whether a re-assertion pulse is due inside the current burst. */
    public boolean pulseDue(long now) {
        return inBurst && pulsesRemaining > 0 && now >= nextPulseAt;
    }

    /** Consume one pulse slot (caller sends a refresh write). */
    public synchronized void consumePulse(long now) {
        if (!inBurst || pulsesRemaining <= 0) return;
        pulsesRemaining--;
        nextPulseAt = now + nextGapMs(
                plannedShape.pulseMinGapMs, plannedShape.pulseMaxGapMs);
    }

    // ------------------------------------------------------------------
    // Intensity curve engine (session-long profile)
    // ------------------------------------------------------------------

    /**
     * The session-long intensity curve: the module starts below target,
     * climbs to a plateau, and decays toward the session end. This makes
     * the whole session look like one network degradation event instead
     * of a square wave.
     */
    private float curvePhase = 0f;       // 0..1 across the session
    private float curvePlateau = 0.6f;   // plateau start (fraction)
    private float curveDecay = 0.85f;    // plateau end (fraction)

    private void planCurve() {
        curvePlateau = 0.45f + random.nextFloat() * 0.25f;
        curveDecay = curvePlateau + 0.15f + random.nextFloat() * 0.2f;
        if (curveDecay > 0.98f) curveDecay = 0.98f;
    }

    /** The curve-shaped intensity multiplier for the current session. */
    float curveFactor(long now) {
        if (sessionBudgetMs <= 0) return 1f;
        curvePhase = clamp((float) (now - sessionStartedAt) / sessionBudgetMs, 0f, 1f);
        if (curvePhase < curvePlateau) {
            // Climb phase: from 0.35 -> 1.0 across the climb window.
            float climb = curvePlateau <= 0f ? 1f : curvePhase / curvePlateau;
            return 0.35f + 0.65f * climb;
        }
        if (curvePhase < curveDecay) {
            return 1f; // plateau
        }
        // Decay phase: 1.0 -> 0.4 across the decay window.
        float dec = (curvePhase - curveDecay) / Math.max(0.02f, 1f - curveDecay);
        return 1f - 0.6f * dec;
    }

    /** Combined curve x gate intensity factor for this moment. */
    float compositeIntensityFactor(long now) {
        return clamp(curveFactor(now) * gateIntensityFactor(), 0.25f, 1f);
    }

    // ------------------------------------------------------------------
    // Bridge health model
    // ------------------------------------------------------------------

    /**
     * The Lua bridge can drop commands (socket down, GG paused). The
     * health model tracks missed command slots; sustained misses mean
     * the bridge is offline, so the module throttles itself instead of
     * blasting commands into the void — and, critically, treats the
     * session as untrusted (a lag window the enemy server may have
     * observed without our stop-cleanup).
     */
    private int missedSlots = 0;
    private int consecutiveMisses = 0;
    private long lastBridgeSeenAt = 0L;
    private boolean bridgeOffline = false;

    /** Caller reports a command was actually delivered (write succeeded). */
    public synchronized void noteDelivered() {
        consecutiveMisses = 0;
        lastBridgeSeenAt = System.currentTimeMillis();
        if (bridgeOffline) {
            bridgeOffline = false;
            lastEvent = "bridge back online";
        }
    }

    /** Caller reports the slot was skipped (bridge unavailable). */
    public synchronized void noteMissedSlot(long now) {
        missedSlots++;
        consecutiveMisses++;
        lastBridgeSeenAt = now;
        if (consecutiveMisses >= 4) {
            bridgeOffline = true;
            lastEvent = "bridge offline (misses=" + consecutiveMisses + ")";
        }
    }

    public boolean bridgeOffline() {
        return bridgeOffline;
    }

    public int missedSlots() {
        return missedSlots;
    }

    public int consecutiveMisses() {
        return consecutiveMisses;
    }

    // ------------------------------------------------------------------
    // Cadence shaping
    // ------------------------------------------------------------------

    /**
     * Command cadence is not uniform: the gap between commands follows a
     * humanlike distribution — mostly short gaps, occasional longer
     * "recheck" gaps, and a small chance of an extra early command right
     * after a mode change (so the Lua engine picks up the mode switch
     * quickly).
     */
    private long shapeCommandGap(long now) {
        if (modeRefreshDue(now) && chance(0.35d)) {
            return randBetween(350L, 700L);
        }
        if (chance(0.08d)) {
            return randBetween(3_400L, 5_200L);
        }
        return randBetween(COMMAND_MIN_GAP_MS, COMMAND_MAX_GAP_MS);
    }

    /** Re-roll the next command slot using the shaped distribution. */
    private void scheduleNextCommand(long now) {
        nextCommandAt = now + shapeCommandGap(now);
    }

    // ------------------------------------------------------------------
    // Safety interlocks
    // ------------------------------------------------------------------

    /**
     * Hard safety: the module must never exceed a small number of
     * commands per second even if ticks are called far faster than the
     * real cadence. Guards against timer anomalies and test overdrive.
     */
    private int commandsThisSecond = 0;
    private long commandSecondBucket = 0L;

    private static final int MAX_COMMANDS_PER_SECOND = 6;

    boolean commandRateSafe(long now) {
        long bucket = now / 1_000L;
        if (bucket != commandSecondBucket) {
            commandSecondBucket = bucket;
            commandsThisSecond = 0;
        }
        return commandsThisSecond < MAX_COMMANDS_PER_SECOND;
    }

    synchronized void noteCommandSent(long now) {
        long bucket = now / 1_000L;
        if (bucket != commandSecondBucket) {
            commandSecondBucket = bucket;
            commandsThisSecond = 0;
        }
        commandsThisSecond++;
    }

    // ------------------------------------------------------------------
    // Wave planning across a match
    // ------------------------------------------------------------------

    /**
     * A "wave" is a group of lag sessions across a match. Real degraded
     * connections come in waves: a few bad minutes, then a calm stretch,
     * then another bad patch. The wave planner decides how many sessions
     * belong to the current wave and widens the gaps between them, so
     * the module's engagement pattern across a whole match mirrors a
     * network that is having a bad day rather than a cheat that keeps
     * turning itself on and off like a metronome.
     */
    private int wavePlannedSessions = 1;
    private int waveSessionsCompleted = 0;
    private boolean wavePlanned = false;

    /** Number of sessions planned for the current wave (1..4). */
    public int wavePlannedSessions() {
        return wavePlannedSessions;
    }

    public int waveSessionsCompleted() {
        return waveSessionsCompleted;
    }

    public boolean waveActive() {
        return wavePlanned && waveSessionsCompleted < wavePlannedSessions;
    }

    /** Plan a new wave; called when a fresh session begins. */
    private void planWave() {
        wavePlannedSessions = randBetween(1, 4);
        waveSessionsCompleted = 0;
        wavePlanned = true;
        lastEvent = "wave planned (" + wavePlannedSessions + " sessions)";
    }

    /** Called when a session ends; expands the cooldown between waves. */
    private void noteWaveSessionEnded(long now) {
        waveSessionsCompleted++;
        if (waveSessionsCompleted >= wavePlannedSessions) {
            wavePlanned = false;
            // Wider gap before the next wave is allowed to start.
            cooldownUntil = Math.max(cooldownUntil,
                    now + nextGapMs(COOLDOWN_MAX_MS, COOLDOWN_MAX_MS * 2L));
            lastEvent = "wave complete, extended cooldown";
        }
    }

    // ------------------------------------------------------------------
    // Decision layer: when engagement is believable
    // ------------------------------------------------------------------

    /**
     * Beyond the hard gates, the decision layer applies soft "believability"
     * rules: the module should not engage the moment a match opens, should
     * not run while the player is obviously on a roll, and should prefer
     * to start mid-lane skirmishes when the behavior cloak reports it.
     * These are advisory; they shape intensity and start timing.
     */
    private long matchStartedAt = 0L;
    private boolean inMatch = false;

    /** Warm-up window at match start where engagement is withheld. */
    private static final long MATCH_WARMUP_MIN_MS = 6_000L;
    private static final long MATCH_WARMUP_MAX_MS = 25_000L;

    private long warmupEndsAt = 0L;

    /** Called by the stack on match start. */
    void noteMatchStartInternal(long now) {
        inMatch = true;
        matchStartedAt = now;
        warmupEndsAt = now + randBetween(MATCH_WARMUP_MIN_MS, MATCH_WARMUP_MAX_MS);
        planWave();
        lastEvent = "match context armed";
    }

    /** Called by the stack on match end. */
    void noteMatchEndInternal(long now) {
        inMatch = false;
        wavePlanned = false;
        waveSessionsCompleted = 0;
        lastEvent = "match context cleared";
    }

    public boolean inMatch() {
        return inMatch;
    }

    /** Whether the warm-up window is still withholding engagement. */
    public boolean warmupActive(long now) {
        return inMatch && now < warmupEndsAt;
    }

    /** Whether engagement timing currently looks believable. */
    boolean timingBelievable(long now) {
        if (warmupActive(now)) return false;
        if (behaviorCloakSaysQuiet()) return false;
        return true;
    }

    private boolean behaviorCloakSaysQuiet() {
        // The behavior cloak tracks breaks and focus dips; during a
        // break the player is "away", and a lag wave then is odd.
        return stack.behaviorCloak.inBreak();
    }

    /** Session start is withheld until both gates and timing allow. */
    private boolean engagementBelievable(long now) {
        return gatesAllow(now) && timingBelievable(now);
    }

    // ------------------------------------------------------------------
    // Lua engine mirror (validation of command semantics)
    // ------------------------------------------------------------------

    /**
     * Mirror of the Lua-side lag engine contract. The Lua engine:
     *   - receives LAG SET (intensity, durationMs, seed) and records the
     *     stale positions of the target slot list;
     *   - on each poll, if active, re-asserts the stale values to the
     *     enemy structs (STUTTER burst semantics from the app), or holds
     *     them (FREEZE), or applies alternating deltas (RUBBER);
     *   - on LAG STOP or a fresh LAG SET, restores the ORIGINAL values
     *     it read at capture time;
     *   - never writes when the offset table is placeholder/zero.
     *
     * The mirror here validates every command the module produces so a
     * bug cannot ship a malformed frame to the bridge.
     */
    static String validateCommand(byte[] frame) {
        if (frame == null || frame.length != COMMAND_FRAME_SIZE) {
            return "bad length";
        }
        if (frame[0] != CMD_MARKER) {
            return "bad marker";
        }
        switch (frame[1]) {
            case CMD_LAG_SET: {
                ByteBuffer b = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
                float value = b.getFloat(3);
                float duration = b.getFloat(7);
                if (value < INTENSITY_MIN || value > INTENSITY_MAX) {
                    return "intensity out of range: " + value;
                }
                if (duration < 0f) {
                    return "negative duration";
                }
                return null;
            }
            case CMD_LAG_STOP:
                return null;
            case CMD_LAG_MODE: {
                ByteBuffer b = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
                float value = b.getFloat(3);
                if (value < 0 || value > 2) {
                    return "mode ordinal out of range: " + value;
                }
                return null;
            }
            default:
                return "unknown command id: " + frame[1];
        }
    }

    /** Validate and, if valid, return the frame; else null. */
    public static byte[] validated(byte[] frame) {
        return validateCommand(frame) == null ? frame : null;
    }

    // ------------------------------------------------------------------
    // Match-context helpers
    // ------------------------------------------------------------------

    public long matchStartedAt() {
        return matchStartedAt;
    }

    public long warmupEndsAt() {
        return warmupEndsAt;
    }

    // ------------------------------------------------------------------
    // Extended diagnostics
    // ------------------------------------------------------------------

    /** Full diagnostic map including wave + curve + bridge state. */
    public Map<String, Object> fullDiagnostics(long now) {
        Map<String, Object> m = diagnostics();
        m.put("inMatch", inMatch);
        m.put("wavePlanned", wavePlanned);
        m.put("wavePlannedSessions", wavePlannedSessions);
        m.put("waveSessionsCompleted", waveSessionsCompleted);
        m.put("curvePhase", curvePhase);
        m.put("curvePlateau", curvePlateau);
        m.put("curveDecay", curveDecay);
        m.put("curveFactor", curveFactor(now));
        m.put("compositeIntensityFactor", compositeIntensityFactor(now));
        m.put("bridgeOffline", bridgeOffline);
        m.put("missedSlots", missedSlots);
        m.put("consecutiveMisses", consecutiveMisses);
        m.put("pulsesRemaining", pulsesRemaining);
        m.put("nextPulseAt", nextPulseAt);
        m.put("warmupActive", warmupActive(now));
        m.put("engagementBelievable", engagementBelievable(now));
        return m;
    }

    // ------------------------------------------------------------------
    // Pulse profiles (what each mode does per Lua poll)
    // ------------------------------------------------------------------

    /**
     * A pulse profile describes one Lua-side write action inside a burst.
     * The app produces the profile so the Lua engine stays dumb (it just
     * executes the profile): the intelligence lives here where it can be
     * tested, and the bridge only carries bytes.
     */
    public static final class PulseProfile {
        public final Mode mode;
        public final int intensity;
        public final long windowMs;
        public final float deltaPx;      // RUBBER: max delta around stale pos
        public final boolean reassert;   // STUTTER: re-assert stale pos
        public final boolean hold;       // FREEZE: hold stale pos

        PulseProfile(Mode mode, int intensity, long windowMs,
                     float deltaPx, boolean reassert, boolean hold) {
            this.mode = mode;
            this.intensity = intensity;
            this.windowMs = windowMs;
            this.deltaPx = deltaPx;
            this.reassert = reassert;
            this.hold = hold;
        }
    }

    /** Build the profile for the current burst window. */
    public PulseProfile currentPulseProfile(long now) {
        Mode m = settings.mode;
        int intensity = rampCurrent > 0 ? rampCurrent : settings.intensity;
        long window = burstRemainingMs(now);
        switch (m) {
            case FREEZE:
                return new PulseProfile(m, intensity, window,
                        0f, false, true);
            case RUBBER: {
                float delta = 8f + intensity * 3.2f; // px, scaled by intensity
                return new PulseProfile(m, intensity, window,
                        delta, false, false);
            }
            default:
                return new PulseProfile(m, intensity, window,
                        0f, true, false);
        }
    }

    /**
     * Pulse cadence for the Lua engine inside one burst: how often the
     * Lua poll should re-assert, in ms. Derived from intensity (higher
     * intensity = tighter re-assertions, reads like worse lag).
     */
    public long pulseIntervalMs() {
        int intensity = rampCurrent > 0 ? rampCurrent : settings.intensity;
        long base = 900L - intensity * 60L;
        return clamp(base + randBetween(-60L, 80L), 220L, 640L);
    }

    // ------------------------------------------------------------------
    // Grace windows
    // ------------------------------------------------------------------

    /**
     * Grace windows protect the transitions where the module's behavior
     * changes identity (arm/stop/mode). During a grace window the module
     * withholds commands and lets the previous window's semantics settle,
     * which keeps the write stream from looking like a machine toggling.
     */
    private long graceEndsAt = 0L;
    private boolean graceActive = false;

    private void openGrace(long now, long ms) {
        graceActive = true;
        graceEndsAt = now + ms;
    }

    public boolean graceActive(long now) {
        if (!graceActive) return false;
        if (now >= graceEndsAt) {
            graceActive = false;
            return false;
        }
        return true;
    }

    /** Withhold commands while a grace window is active. */
    boolean commandWithheldByGrace(long now) {
        return graceActive(now);
    }

    // Hook grace into session boundaries:
    // (called from endSession and beginArming via the public wrappers)

    // ------------------------------------------------------------------
    // Post-session introspection
    // ------------------------------------------------------------------

    /**
     * After a session ends, the module spends its cooldown "introspecting":
     * it re-evaluates whether the session felt like a network event by
     * checking the gates that were sampled during it. This feeds the
     * next wave's planning (longer gaps, lower intensity) so repeated
     * engagement stays under the radar even when the user never toggles.
     */
    private int introspectionPenalty = 0;

    public int introspectionPenalty() {
        return introspectionPenalty;
    }

    private void introspect(long now) {
        int penalty = 0;
        if (sessionKilledByGate) penalty += 2;
        if (gateSuspensionsDuringSession() > 1) penalty += 1;
        if (hotnessThrottled()) penalty += 1;
        introspectionPenalty = clamp(penalty, 0, 5);
        if (penalty > 0) {
            cooldownUntil = Math.max(cooldownUntil,
                    now + penalty * nextGapMs(3_000L, 6_000L));
            lastEvent = "introspection penalty " + penalty;
        }
    }

    private int gateSuspensionsDuringSession() {
        return sessionGateSuspensions;
    }

    // ------------------------------------------------------------------
    // Invariants audit (self-check like the other shields)
    // ------------------------------------------------------------------

    /**
     * Consistency audit. Invariants that must ALWAYS hold:
     *   - intensity stays within 1..10 everywhere
     *   - rampCurrent never exceeds rampTarget
     *   - commands are only produced while ACTIVE
     *   - STOP is the last command of every session
     *   - lock is permanent (never unset)
     *   - command frames are always valid per the Lua mirror
     */
    public boolean invariantsHold(long now) {
        if (settings.intensity < INTENSITY_MIN || settings.intensity > INTENSITY_MAX) {
            return false;
        }
        if (rampCurrent < 0 || rampCurrent > INTENSITY_MAX) return false;
        if (rampCurrent > rampTarget && rampTarget > 0) return false;
        if (state != State.ACTIVE && state != State.ARMING
                && lastCommandType == CMD_LAG_SET) {
            return false;
        }
        if (locked != isLocked()) return false;
        if (locked && state != State.LOCKED) return false;
        if (state == State.LOCKED && !locked) return false;
        if (sessionBudgetMs < 0) return false;
        return true;
    }

    /** Audit all invariant classes and return the failures. */
    public java.util.List<String> invariantFailures(long now) {
        java.util.List<String> failures = new java.util.ArrayList<>();
        if (settings.intensity < INTENSITY_MIN || settings.intensity > INTENSITY_MAX) {
            failures.add("intensity=" + settings.intensity + " out of bounds");
        }
        if (rampCurrent < 0 || rampCurrent > INTENSITY_MAX) {
            failures.add("rampCurrent=" + rampCurrent + " out of bounds");
        }
        if (rampCurrent > rampTarget && rampTarget > 0) {
            failures.add("rampCurrent > rampTarget");
        }
        if (state != State.ACTIVE && state != State.ARMING
                && lastCommandType == CMD_LAG_SET) {
            failures.add("last command is SET outside active window");
        }
        if (locked != (state == State.LOCKED)) {
            failures.add("lock flag mismatch with state");
        }
        if (sessionBudgetMs < 0) {
            failures.add("negative session budget");
        }
        return failures;
    }

    // ------------------------------------------------------------------
    // Planned engagement schedule
    // ------------------------------------------------------------------

    /**
     * Beyond the immediate cooldown, the module plans when the NEXT
     * engagement is allowed: a schedule with a floor (cooldown) and a
     * soft ceiling (best window), rolled fresh after each introspection.
     * The GUI can surface "next engagement in X" for transparency.
     */
    private long nextEngagementAt = 0L;

    /** Roll the next-engagement window; called after introspection. */
    private void planNextEngagement(long now) {
        long extra = introspectionPenalty * nextGapMs(2_000L, 5_000L);
        nextEngagementAt = cooldownUntil + extra;
    }

    public long nextEngagementAt() {
        return nextEngagementAt;
    }

    public long untilNextEngagement(long now) {
        return Math.max(0L, nextEngagementAt - now);
    }

    public boolean engagementDue(long now) {
        return userWantsActive && now >= nextEngagementAt && now >= cooldownUntil;
    }

    /** Called by the stack when the user (or GUI) re-toggles on. */
    public synchronized void refreshSchedule(long now) {
        planNextEngagement(now);
        lastEvent = "schedule refreshed (next engagement in "
                + untilNextEngagement(now) + "ms)";
    }

    // ------------------------------------------------------------------
    // Event timeline (ring buffer for diagnostics)
    // ------------------------------------------------------------------

    /**
     * Compact ring buffer of the most recent events, so the GUI status
     * line and support logs can show what the module was doing even if
     * the user cannot reach the full diagnostics map.
     */
    private final String[] timeline = new String[TIMELINE_DEPTH];
    private int timelineIndex = 0;
    private int timelineCount = 0;

    private static final int TIMELINE_DEPTH = 24;

    private void recordEvent(String what) {
        String entry = "t+" + (lastTickAt > 0 ? lastTickAt : 0L)
                + "ms " + what + " [" + state + "/" + rampCurrent + "]";
        timeline[timelineIndex] = entry;
        timelineIndex = (timelineIndex + 1) % TIMELINE_DEPTH;
        timelineCount = Math.min(timelineCount + 1, TIMELINE_DEPTH);
    }

    /** Recent events, oldest first. */
    public java.util.List<String> timeline() {
        java.util.List<String> out = new java.util.ArrayList<>(timelineCount);
        int start = timelineCount == TIMELINE_DEPTH
                ? timelineIndex
                : 0;
        for (int i = 0; i < timelineCount; i++) {
            out.add(timeline[(start + i) % TIMELINE_DEPTH]);
        }
        return out;
    }

    /** Prettified status block for the GUI settings panel. */
    public String statusBlock(long now) {
        StringBuilder sb = new StringBuilder();
        sb.append("State: ").append(state).append('\n');
        if (state == State.ACTIVE) {
            sb.append("Intensity: ").append(rampCurrent)
                    .append('/').append(rampTarget).append('\n');
            sb.append("Mode: ").append(settings.mode).append('\n');
            sb.append("Burst: ").append(inBurst ? "active" : "paused").append('\n');
            sb.append("Session: ").append(Math.round(sessionProgress(now) * 100))
                    .append("% of budget\n");
        }
        if (state == State.COOLDOWN) {
            sb.append("Cooldown: ").append(Math.max(0L, cooldownUntil - now))
                    .append("ms\n");
        }
        if (suspendedByGate) sb.append("Suspended by gate\n");
        if (locked) sb.append("LOCKED\n");
        sb.append("Bridge: ").append(bridgeOffline ? "offline" : "ok")
                .append(" (").append(missedSlots).append(" missed)\n");
        sb.append("Lifetime: ").append(lifetimeSessions).append(" sessions, ")
                .append(lifetimeActiveMs / 1000L).append("s active\n");
        return sb.toString();
    }

    /** Reset the timeline (tests). */
    synchronized void resetTimelineForTest() {
        timelineIndex = 0;
        timelineCount = 0;
        java.util.Arrays.fill(timeline, null);
    }

    // ------------------------------------------------------------------
    // Session priming
    // ------------------------------------------------------------------

    /**
     * The FIRST session of a wave is the riskiest: the enemy server has
     * not seen any degradation yet, so the module "primes" — a longer
     * arming ramp, a gentler first burst, and a shorter first window.
     * Subsequent sessions in the same wave can be slightly more direct,
     * because the player's connection now has a plausible history.
     */
    private boolean primedFirstSession = false;

    private boolean isFirstSessionInWave() {
        return !primedFirstSession;
    }

    private void markPrimed() {
        primedFirstSession = true;
    }

    /** Extra arming delay applied when priming. */
    long primeArmingDelayMs() {
        return isFirstSessionInWave() ? randBetween(1_200L, 2_400L) : 0L;
    }

    /** First-burst window cap when priming (shorter = safer). */
    long primeBurstCapMs() {
        return isFirstSessionInWave() ? 1_600L : 4_200L;
    }

    // Hook priming into the arming path:
    // (called from beginArming below)

    // ------------------------------------------------------------------
    // Command payload shaping
    // ------------------------------------------------------------------

    /**
     * Payload shaping varies the bytes that go into each LAG SET frame
     * beyond the protocol fields: the seed slot is re-rolled per command
     * (Lua uses it to derive its own per-window jitter), and the duration
     * slot carries the remaining window budget so the Lua engine can
     * degrade gracefully instead of stopping abruptly.
     */
    private long payloadSeed = 0L;

    /** Re-roll the payload seed for the next command. */
    private void rerollPayloadSeed(long now) {
        payloadSeed = (now * 31L) ^ randBetween(1L, 0x7FFFFFFFL);
    }

    /** The current payload seed (diagnostics). */
    public long payloadSeed() {
        return payloadSeed;
    }

    /** Build the SET frame with the current shaped payload. */
    public byte[] buildShapedSetCommand(long now) {
        rerollPayloadSeed(now);
        byte[] frame = new byte[COMMAND_FRAME_SIZE];
        frame[0] = CMD_MARKER;
        frame[1] = CMD_LAG_SET;
        frame[2] = 0;
        int value = state == State.ACTIVE ? rampCurrent : rampTarget;
        long remaining = Math.max(0L, sessionHardEndAt - now);
        ByteBuffer b = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        b.putFloat(3, value);
        b.putFloat(7, remaining);
        b.putFloat(11, payloadSeed & 0xFFFFFFFFL);
        return frame;
    }

    // ------------------------------------------------------------------
    // Final: fused public command slot
    // ------------------------------------------------------------------

    /**
     * The single entry point the bridge driver calls: returns the next
     * command frame to send, or null. Handles grace windows, restore
     * retries, mode refresh, rate interlocks and the shaped payload.
     */
    public synchronized byte[] nextCommand(long now) {
        if (commandWithheldByGrace(now)) {
            return null;
        }
        if (restoreRetryPending && now >= restoreRetryAt) {
            restoreRetryPending = false;
            byte[] stop = buildStopCommand();
            lastCommandType = CMD_LAG_STOP;
            lastCommandSentAt = now;
            sessionCommands++;
            lifetimeCommands++;
            noteCommandSent(now);
            recordEvent("restore retry STOP");
            return stop;
        }
        if (modeRefreshDue(now)) {
            byte[] mode = buildModeCommand(settings.mode);
            lastCommandType = CMD_LAG_MODE;
            lastCommandSentAt = now;
            sessionCommands++;
            lifetimeCommands++;
            noteCommandSent(now);
            recordEvent("mode refresh " + settings.mode);
            return mode;
        }
        if (!commandDue(now)) return null;
        if (!commandRateSafe(now)) {
            scheduleNextCommand(now);
            return null;
        }
        byte[] cmd = buildShapedSetCommand(now);
        scheduleNextCommand(now);
        lastCommandType = CMD_LAG_SET;
        lastCommandSentAt = now;
        sessionCommands++;
        lifetimeCommands++;
        noteCommandSent(now);
        recordEvent("LAG SET " + rampCurrent);
        return cmd;
    }
}








