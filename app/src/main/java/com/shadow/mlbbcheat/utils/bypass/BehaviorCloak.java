package com.shadow.mlbbcheat.utils.bypass;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * BehaviorCloak — human-behavior modeling layer.
 *
 * Anti-cheat systems increasingly look beyond the bytes: they model how a
 * human plays. Instant reactions, frame-perfect inputs, perfectly periodic
 * polling, 100% accuracy over long sessions, and zero fatigue all mark a
 * machine. BehaviorCloak makes the cheat's on-device behavior statistically
 * indistinguishable from a skilled human:
 *
 *   1. REACTION CURVES      — every automated response (auto-retri, aim,
 *      alerts) passes through a human reaction model: faster when the
 *      player is "focused", slower when "distracted", with reaction time
 *      drawn from a log-normal distribution instead of a flat random.
 *   2. FOCUS STATE MACHINE  — focus drifts over time (fatigue curve),
 *      spikes on events (kill, death), dips during lulls. This state
 *      drives reaction latencies and error rates.
 *   3. INPUT CADENCE        — simulated inputs (taps, drags) follow
 *      human tap cadence: 60-220ms between taps, occasional double-taps,
 *      drag velocities with acceleration/deceleration, never constant.
 *   4. SESSION MODEL        — session length follows a plausible play
 *      distribution (30min-3h), with breaks; the cheat's polling rate
 *      decays gently through a session (fatigue) and resets after breaks.
 *   5. ERROR INJECTION      — aim error, tap jitter, mistimed retri
 *      (occasionally "misses" the window by a few ms), skill choice
 *      hesitation — injected with probabilities that scale with fatigue.
 *   6. CONSISTENCY SCORING  — the engine tracks how consistent the
 *      player's own behavior is; if the cheat's timings become too
 *      regular (e.g. a bug producing fixed delays), the score degrades
 *      and the watchdog can pause features.
 *   7. CHURN MODEL          — features toggle occasionally (humans turn
 *      things off), settings drift subtly, and the overlay opacity
 *      changes between matches.
 *   8. ANTI-TILT MODEL      — after repeated deaths, a human plays worse:
 *      reaction slows, errors rise. The cheat mirrors this (reduces
 *      auto-aim quality slightly) so long losing streaks don't produce
 *      suspiciously clean play.
 */
public final class BehaviorCloak {

    private static final double BASE_REACTION_MS = 190d;
    private static final double REACTION_SIGMA = 0.42d;
    private static final double FOCUS_DECAY_PER_HOUR = 0.14d;
    private static final double FOCUS_RECOVERY_PER_MIN = 0.05d;
    private static final double FOCUS_EVENT_BOOST = 0.18d;
    private static final double FOCUS_EVENT_BOOST_MAX = 0.85d;
    private static final double FOCUS_LULL_DROP = 0.08d;
    private static final double FOCUS_LULL_MIN = 0.30d;
    private static final double FOCUS_CEILING = 1.0d;
    private static final double FATIGUE_ANCHOR = 0.60d;
    private static final long SESSION_MIN_MS = 25L * 60_000L;
    private static final long SESSION_MAX_MS = 180L * 60_000L;
    private static final long BREAK_MIN_MS = 2L * 60_000L;
    private static final long BREAK_MAX_MS = 30L * 60_000L;
    private static final long LULL_THRESHOLD_MS = 20_000L;
    private static final int TAP_MIN_GAP_MS = 55;
    private static final int TAP_MAX_GAP_MS = 240;
    private static final int CADENCE_HISTORY = 48;
    private static final double DOUBLE_TAP_PROB = 0.08d;
    private static final double MISTAKE_PROB_BASE = 0.012d;
    private static final double MISTAKE_PROB_MAX = 0.09d;
    private static final double TILT_THRESHOLD_DEATHS = 5;
    private static final double TILT_FACTOR = 0.25d;

    private final Random rng = new Random();
    private final AtomicLong sessionStartMs = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong sessionPlannedEndMs = new AtomicLong(
            System.currentTimeMillis() + SESSION_MIN_MS + 30_000L);
    private final AtomicLong lastEventMs = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong lastDeathMs = new AtomicLong(0L);
    private final AtomicLong focusState = new AtomicLong(focusToBits(0.72d));
    private final Deque<Long> tapCadence = new ArrayDeque<>();
    private final List<Double> recentFocusSamples = new ArrayList<>();
    private final AtomicLong consistencyScore = new AtomicLong(100L);
    private final AtomicLong deathCount = new AtomicLong(0L);
    private final AtomicLong breakUntilMs = new AtomicLong(0L);
    private final AtomicLong lastTapMs = new AtomicLong(0L);

    // ------------------------------------------------------------------
    // Reaction model
    // ------------------------------------------------------------------

    /** Human reaction delay for the current focus state. */
    public long reactionDelayMs() {
        double focus = currentFocus();
        double sigma = REACTION_SIGMA * (1.4d - focus * 0.4d);
        double gauss = rng.nextGaussian() * sigma;
        double delay = BASE_REACTION_MS * (1.6d - focus) + gauss * 120d;
        delay = Math.max(45d, Math.min(950d, delay));
        long d = (long) delay;
        if (rng.nextDouble() < 0.03d) d += 300 + rng.nextInt(700); // distraction
        return d;
    }

    /** Reaction delay for an urgent alert (faster than normal). */
    public long urgentReactionMs() {
        long base = reactionDelayMs();
        return Math.max(40L, base * 3 / 5);
    }

    /** Reaction delay when the player is tilted (slower). */
    public long tiltedReactionMs() {
        long base = reactionDelayMs();
        return base + (long) (base * tiltFactor() * 0.5d);
    }

    private double tiltFactor() {
        long deaths = deathCount.get();
        if (deaths < TILT_THRESHOLD_DEATHS) return 0d;
        return Math.min(TILT_FACTOR, (deaths - TILT_THRESHOLD_DEATHS) * 0.03d);
    }

    // ------------------------------------------------------------------
    // Focus state
    // ------------------------------------------------------------------

    private static long focusToBits(double f) {
        return Double.doubleToLongBits(f);
    }

    private static double bitsToFocus(long bits) {
        double v = Double.longBitsToDouble(bits);
        return Math.max(0.05d, Math.min(1.0d, v));
    }

    public double currentFocus() {
        double f = bitsToFocus(focusState.get());
        long now = System.currentTimeMillis();
        long elapsed = now - sessionStartMs.get();
        double fatigue = (elapsed / 3_600_000d) * FOCUS_DECAY_PER_HOUR;
        f -= fatigue;
        if (now - lastEventMs.get() > LULL_THRESHOLD_MS) {
            f -= FOCUS_LULL_DROP * ((now - lastEventMs.get()) / LULL_THRESHOLD_MS);
        }
        if (f < FATIGUE_ANCHOR) {
            f = FATIGUE_ANCHOR + (f - FATIGUE_ANCHOR) * 0.5d;
        }
        return Math.max(FOCUS_LULL_MIN, Math.min(FOCUS_CEILING, f));
    }

    /** Call when something happens in-game (kill, fight start). */
    public void noteEvent() {
        long now = System.currentTimeMillis();
        double f = bitsToFocus(focusState.get());
        f = Math.min(FOCUS_EVENT_BOOST_MAX, f + FOCUS_EVENT_BOOST);
        focusState.set(focusToBits(f));
        lastEventMs.set(now);
    }

    /** Call on player death (tilts the model). */
    public void noteDeath() {
        deathCount.incrementAndGet();
        lastDeathMs.set(System.currentTimeMillis());
        long now = System.currentTimeMillis();
        double f = bitsToFocus(focusState.get());
        f = Math.max(FOCUS_LULL_MIN, f - 0.12d);
        focusState.set(focusToBits(f));
        lastEventMs.set(now);
    }

    /** A quiet period recovers focus slowly. */
    public void noteIdle(long idleMs) {
        if (idleMs <= 0) return;
        double recover = (idleMs / 60_000d) * FOCUS_RECOVERY_PER_MIN;
        double f = bitsToFocus(focusState.get());
        focusState.set(focusToBits(Math.min(FOCUS_CEILING, f + recover)));
    }

    /** Roll current focus into history (for consistency scoring). */
    public void sampleFocus() {
        recentFocusSamples.add(currentFocus());
        if (recentFocusSamples.size() > 60) recentFocusSamples.remove(0);
    }

    // ------------------------------------------------------------------
    // Session model
    // ------------------------------------------------------------------

    /** Call at cheat start. Plans a plausible session window + break. */
    public void beginSession() {
        long now = System.currentTimeMillis();
        sessionStartMs.set(now);
        long plan = SESSION_MIN_MS + (long) (rng.nextDouble() * (SESSION_MAX_MS - SESSION_MIN_MS));
        sessionPlannedEndMs.set(now + plan);
        breakUntilMs.set(0L);
        deathCount.set(0L);
        consistencyScore.set(100L);
        focusState.set(focusToBits(0.65d + rng.nextDouble() * 0.25d));
        sampleFocus();
    }

    /** Whether the model thinks the player should be taking a break. */
    public boolean breakDue() {
        long now = System.currentTimeMillis();
        if (now < breakUntilMs.get()) return true;
        if (now > sessionPlannedEndMs.get()) {
            long brk = BREAK_MIN_MS + (long) (rng.nextDouble() * (BREAK_MAX_MS - BREAK_MIN_MS));
            breakUntilMs.set(now + brk);
            sessionPlannedEndMs.set(now + brk + SESSION_MIN_MS
                    + (long) (rng.nextDouble() * (SESSION_MAX_MS - SESSION_MIN_MS)));
            return true;
        }
        return false;
    }

    public long timeUntilBreakMs() {
        long remaining = sessionPlannedEndMs.get() - System.currentTimeMillis();
        return Math.max(0L, remaining);
    }

    public boolean inBreak() {
        return System.currentTimeMillis() < breakUntilMs.get();
    }

    // ------------------------------------------------------------------
    // Input cadence
    // ------------------------------------------------------------------

    /** Millis to wait before the next simulated input tap. */
    public long nextTapGapMs() {
        long base = TAP_MIN_GAP_MS + rng.nextInt(TAP_MAX_GAP_MS - TAP_MIN_GAP_MS);
        if (rng.nextDouble() < DOUBLE_TAP_PROB) {
            base = base * 2 / 3; // rapid double-tap
        }
        double fatigueBoost = (1d - currentFocus()) * 0.35d;
        long jitter = (long) (base * fatigueBoost * rng.nextDouble());
        return base + jitter;
    }

    /** Register an executed tap for cadence tracking. */
    public void noteTap() {
        long now = System.currentTimeMillis();
        long last = lastTapMs.getAndSet(now);
        if (last > 0 && now - last < 5000L) {
            tapCadence.addLast(now - last);
            while (tapCadence.size() > CADENCE_HISTORY) tapCadence.pollFirst();
        }
    }

    /** Mean tap gap over the recent history (for scoring). */
    public double meanTapGapMs() {
        if (tapCadence.isEmpty()) return 0d;
        long sum = 0L;
        for (Long g : tapCadence) sum += g;
        return sum / (double) tapCadence.size();
    }

    public double tapGapStdDevMs() {
        if (tapCadence.size() < 3) return 0d;
        double mean = meanTapGapMs();
        double var = 0d;
        for (Long g : tapCadence) {
            double d = g - mean;
            var += d * d;
        }
        return Math.sqrt(var / (tapCadence.size() - 1));
    }

    // ------------------------------------------------------------------
    // Error injection
    // ------------------------------------------------------------------

    /** Probability this automated action should be "mistimed" this time. */
    public double mistakeProbability() {
        double fatigue = 1d - currentFocus();
        double tilt = tiltFactor();
        return Math.min(MISTAKE_PROB_MAX, MISTAKE_PROB_BASE + fatigue * 0.05d + tilt * 0.06d);
    }

    public boolean shouldMistime() {
        return rng.nextDouble() < mistakeProbability();
    }

    /** Occasionally drag to a slightly wrong spot (aim quality decay). */
    public float aimErrorFactor() {
        double fatigue = 1d - currentFocus();
        double tilt = tiltFactor();
        return 1f + (float) (fatigue * 0.25d + tilt * 0.30d);
    }

    /** Retri "misses" by a few ms occasionally. */
    public long retriErrorMs() {
        if (!shouldMistime()) return 0L;
        return 6L + rng.nextInt(25);
    }

    public boolean hesitates() {
        return rng.nextDouble() < 0.05d + (1d - currentFocus()) * 0.04d;
    }

    public long hesitationMs() {
        return 120L + rng.nextInt(400);
    }

    // ------------------------------------------------------------------
    // Consistency scoring
    // ------------------------------------------------------------------

    /**
     * Human input is never perfectly regular. Score drops when tap gaps
     * become too constant (stddev too low) or polling too periodic.
     * 0 = perfect robot; 100 = human-like.
     */
    public double consistencyIndex() {
        double score = 100d;
        double stddev = tapGapStdDevMs();
        double mean = meanTapGapMs();
        if (mean > 0d && stddev < 4d) score -= 45d;
        if (mean > 0d && stddev < 8d) score -= 20d;
        if (recentFocusSamples.size() > 5) {
            double min = 1d, max = 0d;
            for (double f : recentFocusSamples) {
                min = Math.min(min, f);
                max = Math.max(max, f);
            }
            if (max - min < 0.02d) score -= 25d; // frozen focus = machine
        }
        if (deathCount.get() > TILT_THRESHOLD_DEATHS && score > 60d) {
            score = 60d + (score - 60d) * 0.5d;
        }
        return Math.max(0d, Math.min(100d, score));
    }

    public long consistencyScoreLong() {
        return (long) consistencyIndex();
    }

    public void updateConsistency() {
        consistencyScore.set((long) consistencyIndex());
    }

    // ------------------------------------------------------------------
    // Feature modulation
    // ------------------------------------------------------------------

    /** Suggested multiplier for feature aggressiveness right now. */
    public float featureIntensity() {
        return (float) (0.55d + currentFocus() * 0.45d);
    }

    /** Suggest the number of active features (humans don't run all). */
    public int suggestedActiveFeatures(int maxFeatures) {
        double focus = currentFocus();
        int n = 1 + (int) (focus * (maxFeatures - 1));
        if (rng.nextDouble() < 0.04d) n = Math.max(1, n - 1); // toggles off
        return Math.min(maxFeatures, Math.max(1, n));
    }

    // ------------------------------------------------------------------
    // Long-horizon drift
    // ------------------------------------------------------------------

    private final AtomicLong driftSeedMs = new AtomicLong(System.currentTimeMillis());

    /** Subtle long-horizon drift so repeated sessions aren't identical. */
    public double driftOffset() {
        long now = System.currentTimeMillis();
        long hours = (now - driftSeedMs.get()) / 3_600_000L;
        return (hours % 7) / 7d * 0.04d;
    }

    // ------------------------------------------------------------------
    // Diagnostics
    // ------------------------------------------------------------------

    public static final class CloakStats {
        public final double focus;
        public final long reactionMs;
        public final double consistency;
        public final long deaths;
        public final boolean inBreak;
        public final long breakInMs;
        public final double meanTapGap;
        public final double tapStdDev;
        CloakStats(double focus, long reactionMs, double consistency, long deaths,
                   boolean inBreak, long breakInMs, double meanTapGap, double tapStdDev) {
            this.focus = focus;
            this.reactionMs = reactionMs;
            this.consistency = consistency;
            this.deaths = deaths;
            this.inBreak = inBreak;
            this.breakInMs = breakInMs;
            this.meanTapGap = meanTapGap;
            this.tapStdDev = tapStdDev;
        }
    }

    public CloakStats stats() {
        return new CloakStats(
                currentFocus(),
                reactionDelayMs(),
                consistencyIndex(),
                deathCount.get(),
                inBreak(),
                timeUntilBreakMs(),
                meanTapGapMs(),
                tapGapStdDevMs());
    }

    // ------------------------------------------------------------------
    // Fatigue curve helpers
    // ------------------------------------------------------------------

    /**
     * Fatigue as a 0..1 fraction of session elapsed. The cheat's polling
     * should slow by this much over the session.
     */
    public double fatigueFraction() {
        long elapsed = System.currentTimeMillis() - sessionStartMs.get();
        double plan = Math.max(1L, sessionPlannedEndMs.get() - sessionStartMs.get());
        double f = Math.min(1d, elapsed / plan);
        return f * FOCUS_DECAY_PER_HOUR * 3d;
    }

    public double fatigueBounded() {
        return Math.min(0.35d, fatigueFraction());
    }

    // ------------------------------------------------------------------
    // Break-aware scheduling
    // ------------------------------------------------------------------

    /** Long gap to return when a break is due (minutes-scale). */
    public long breakGapMs() {
        long now = System.currentTimeMillis();
        long until = breakUntilMs.get();
        if (now >= until) return 0L;
        return until - now;
    }

    public void resumeFromBreak() {
        breakUntilMs.set(0L);
        focusState.set(focusToBits(0.9d));
        sampleFocus();
    }

    // ------------------------------------------------------------------
    // Per-ability reaction profiles
    // ------------------------------------------------------------------

    /**
     * Different abilities get different reaction envelopes: ultimates are
     * "considered" (slower), escapes are fast, farm skills are casual.
     * Returns the reaction delay for the given ability class.
     */
    public long abilityReactionMs(int abilityClass) {
        switch (abilityClass) {
            case 0: // ult — deliberate
                return 240L + rng.nextInt(480) + (long) (currentFocus() * 120d);
            case 1: // escape — quick
                return 90L + rng.nextInt(180);
            case 2: // farm — casual
                return 160L + rng.nextInt(400);
            case 3: // retri/objective — timed
                return 60L + rng.nextInt(160);
            default:
                return reactionDelayMs();
        }
    }

    public long ultDecideMs() {
        return 300L + rng.nextInt(700);
    }

    public boolean ultConsidered() {
        return rng.nextDouble() < 0.8d;
    }

    // ------------------------------------------------------------------
    // Micro-mistake injector
    // ------------------------------------------------------------------

    /**
     * Humans make small mistakes: they flick to the wrong target, overcast
     * a skill, walk out of range, misjudge the timing. The injector models
     * which mistakes happen per match so the account's error profile is
     * real.
     */
    public boolean wrongTargetFlick() {
        return rng.nextDouble() < 0.035d;
    }

    public boolean overcast() {
        return rng.nextDouble() < 0.02d;
    }

    public boolean walkOffRange() {
        return rng.nextDouble() < 0.012d;
    }

    public boolean misjudgeTiming() {
        return rng.nextDouble() < 0.04d;
    }

    public long mistakeRecoveryMs() {
        return 300L + rng.nextInt(600);
    }

    // ------------------------------------------------------------------
    // Engagement / discard model
    // ------------------------------------------------------------------

    private final AtomicLong lastEngageMs = new AtomicLong(0L);
    private final AtomicInteger engagesThisMatch = new AtomicInteger(0);
    private final AtomicInteger disengages = new AtomicInteger(0);

    public void noteEngage() {
        lastEngageMs.set(System.currentTimeMillis());
        engagesThisMatch.incrementAndGet();
    }

    public void noteDisengage() {
        disengages.incrementAndGet();
    }

    /** After N engages, humans stop taking fights (deaths/fear). */
    public boolean shouldAvoidEngage() {
        return engagesThisMatch.get() > 18 || (disengages.get() > 4 && rng.nextDouble() < 0.5d);
    }

    public int engagesThisMatch() {
        return engagesThisMatch.get();
    }

    public long sinceEngageMs() {
        long last = lastEngageMs.get();
        return last == 0L ? Long.MAX_VALUE : System.currentTimeMillis() - last;
    }

    // ------------------------------------------------------------------
    // Attention budget scheduler
    // ------------------------------------------------------------------

    /**
     * Attention is finite: the player watches the map, checks items, looks
     * at the shop, types to teammates. The scheduler burns attention on
     * these "distractions" so the cheat never looks hyper-focused.
     */
    public double attentionRemaining() {
        long since = sinceEngageMs();
        double focus = currentFocus();
        if (since > 60_000L) return 1d;
        return Math.max(0.2d, focus);
    }

    public boolean distractedNow() {
        return attentionRemaining() < 0.45d && rng.nextDouble() < 0.3d;
    }

    public long distractionDelayMs() {
        return 220L + rng.nextInt(600);
    }

    public boolean mapCheckDue(long lastMapCheckMs) {
        return System.currentTimeMillis() - lastMapCheckMs > 4_000L + rng.nextInt(9_000);
    }

    public boolean shopCheckDue(long lastShopMs) {
        return System.currentTimeMillis() - lastShopMs > 25_000L + rng.nextInt(60_000);
    }

    // ------------------------------------------------------------------
    // Skill-order cadence
    // ------------------------------------------------------------------

    /** Cadence for activating skills: humans cast in clumps with gaps. */
    public long nextSkillCastGapMs() {
        double r = rng.nextDouble();
        if (r < 0.5d) return 700L + rng.nextInt(900);
        if (r < 0.85d) return 1_800L + rng.nextInt(2_200);
        return 4_000L + rng.nextInt(6_000);
    }

    public boolean comboMode() {
        return rng.nextDouble() < 0.35d;
    }

    public int comboSize() {
        return 1 + rng.nextInt(3);
    }

    // ------------------------------------------------------------------
    // Pause-and-think heuristic
    // ------------------------------------------------------------------

    /**
     * Before a decisive action (ult, retri, rotation), humans pause to
     * think. The heuristic decides when a think-pause is due and how
     * long it lasts.
     */
    public boolean thinkPauseDue() {
        return rng.nextDouble() < 0.12d;
    }

    public long thinkPauseMs() {
        return 500L + rng.nextInt(1_200);
    }

    public boolean decisiveActionPaused() {
        return thinkPauseDue() && currentFocus() < 0.7d;
    }

    // ------------------------------------------------------------------
    // Map-ping cadence
    // ------------------------------------------------------------------

    /**
     * Humans ping the map to communicate. The cadence model produces
     * ping-like events at a human rate so the interaction profile (if
     * ever observed) looks alive.
     */
    public boolean pingDue() {
        return rng.nextDouble() < 0.08d;
    }

    public long nextPingGapMs() {
        return 3_000L + rng.nextInt(12_000);
    }

    public boolean retreatPingAfter(int deaths) {
        return deaths >= 2 && rng.nextDouble() < 0.5d;
    }

    // ------------------------------------------------------------------
    // Lane-rotation pattern model
    // ------------------------------------------------------------------

    /**
     * Rotation decisions follow the game flow (laning → objectives →
     * grouping). The model suggests when a "normal" player would move
     * to another lane so movement looks organic.
     */
    public boolean rotationDue(long lastRotationMs) {
        long elapsed = System.currentTimeMillis() - lastRotationMs;
        return elapsed > 60_000L && rng.nextDouble() < 0.3d;
    }

    public long rotationTravelMs() {
        return 6_000L + rng.nextInt(10_000);
    }

    // ------------------------------------------------------------------
    // Death-tilt escalation
    // ------------------------------------------------------------------

    private final AtomicInteger deathsThisMatch = new AtomicInteger(0);

    public void noteDeathTilt() {
        deathsThisMatch.incrementAndGet();
    }

    public boolean tilted() {
        return deathsThisMatch.get() >= 5 && rng.nextDouble() < 0.6d;
    }

    public long tiltedReactionExtraMs() {
        return tilted() ? 120L + rng.nextInt(300) : 0L;
    }

    public void resetMatchPattern() {
        engagesThisMatch.set(0);
        disengages.set(0);
        deathsThisMatch.set(0);
    }

    // ------------------------------------------------------------------
    // Item-purchase cadence
    // ------------------------------------------------------------------

    public boolean itemBuyDue(long lastBuyMs) {
        long elapsed = System.currentTimeMillis() - lastBuyMs;
        return elapsed > 20_000L && rng.nextDouble() < 0.25d;
    }

    public long itemBuyDelayMs() {
        return 500L + rng.nextInt(1_000);
    }

    // ------------------------------------------------------------------
    // Emote/social noise
    // ------------------------------------------------------------------

    /** Occasional emote/spray usage keeps the session profile social. */
    public boolean emoteDue() {
        return rng.nextDouble() < 0.06d;
    }

    public long nextEmoteGapMs() {
        return 30_000L + rng.nextInt(120_000);
    }

    // ------------------------------------------------------------------
    // Input pressure decay
    // ------------------------------------------------------------------

    private final AtomicLong lastInputMs = new AtomicLong(0L);

    public void noteInput() {
        lastInputMs.set(System.currentTimeMillis());
    }

    public long inputQuietMs() {
        long last = lastInputMs.get();
        return last == 0L ? 0L : System.currentTimeMillis() - last;
    }

    public boolean inputStale() {
        return inputQuietMs() > 120_000L;
    }

    // ------------------------------------------------------------------
    // Match-flow phase model
    // ------------------------------------------------------------------

    /**
     * Match flow has phases: laning (0-4m), mid-game (4-9m), late-game
     * (9m+). Behavior intensity differs per phase; the model reports the
     * current phase and its intensity multiplier.
     */
    public int flowPhase(long matchStartMs) {
        long elapsed = System.currentTimeMillis() - matchStartMs;
        if (elapsed < 4L * 60_000L) return 0;
        if (elapsed < 9L * 60_000L) return 1;
        return 2;
    }

    public float phaseIntensity(int phase) {
        switch (phase) {
            case 0: return 0.8f;
            case 1: return 1.0f;
            default: return 0.9f;
        }
    }

    public float flowIntensity(long matchStartMs) {
        return phaseIntensity(flowPhase(matchStartMs));
    }

    // ------------------------------------------------------------------
    // Stutter-jitter micro-delay
    // ------------------------------------------------------------------

    /**
     * Add a tiny random micro-delay to any automated action so even the
     * fast path never repeats with machine precision.
     */
    public long microStutterMs() {
        return rng.nextInt(12);
    }

    public boolean occasionallyStutter() {
        return rng.nextDouble() < 0.4d;
    }

    // ------------------------------------------------------------------
    // Decision entropy
    // ------------------------------------------------------------------

    private final Random decisionRng = new Random(System.nanoTime());

    /** An uncorrelated entropy source for decision points. */
    public double decisionEntropy() {
        return decisionRng.nextDouble();
    }

    public boolean decision(double probability) {
        return decisionRng.nextDouble() < probability;
    }

    // ------------------------------------------------------------------
    // Predictive-pause model
    // ------------------------------------------------------------------

    /**
     * Humans pre-position before fights (walk, pause, then act). The
     * model suggests pre-action pauses so automation isn't instant.
     */
    public boolean preActionPauseDue() {
        return rng.nextDouble() < 0.3d;
    }

    public long preActionPauseMs() {
        return 100L + rng.nextInt(350);
    }

    // ------------------------------------------------------------------
    // Response-chaining model
    // ------------------------------------------------------------------

    /**
     * Humans chain responses: cast → observe → decide → cast again. The
     * chain model inserts observe gaps between automated responses so a
     * sequence never looks scripted.
     */
    public long observeGapMs() {
        return 150L + rng.nextInt(450);
    }

    public int chainDepth() {
        double r = rng.nextDouble();
        if (r < 0.5d) return 2;
        if (r < 0.8d) return 3;
        return 1;
    }

    // ------------------------------------------------------------------
    // Hesitation-decision map
    // ------------------------------------------------------------------

    /**
     * Human hesitation depends on stakes: farming a creep = no hesitation;
     * flashing into 3 enemies = long pause. Maps action class → hesitation.
     */
    public long hesitationForAction(int actionClass) {
        switch (actionClass) {
            case 0: return 0L;
            case 1: return 40L + rng.nextInt(120);
            case 2: return 120L + rng.nextInt(300);
            case 3: return 250L + rng.nextInt(700);
            default: return 60L + rng.nextInt(200);
        }
    }

    public boolean willHesitate(int actionClass) {
        return actionClass >= 2 && rng.nextDouble() < 0.4d;
    }

    // ------------------------------------------------------------------
    // Session break-point planner
    // ------------------------------------------------------------------

    /**
     * Real sessions end for human reasons (hunger, battery, teammate
     * left). The planner suggests session end points and the "exit
     * ritual" cadence (check stats, back out, close app).
     */
    public boolean sessionEndDue() {
        double f = fatigueFraction();
        return f > 0.85d && rng.nextDouble() < 0.25d;
    }

    public boolean exitRitualStep() {
        return rng.nextDouble() < 0.6d;
    }

    public long exitRitualGapMs() {
        return 800L + rng.nextInt(2_500);
    }

    // ------------------------------------------------------------------
    // Idle-bounce model
    // ------------------------------------------------------------------

    /**
     * Idle players bounce their hero slightly. If the account ever shows
     * idle movement, it should be present: the model says whether an
     * idle-bounce is expected right now.
     */
    public boolean idleBounceExpected() {
        return inputQuietMs() > 5_000L && rng.nextDouble() < 0.5d;
    }

    public long idleBounceMs() {
        return 300L + rng.nextInt(700);
    }

    // ------------------------------------------------------------------
    // Response-recheck guard
    // ------------------------------------------------------------------

    private final AtomicLong lastAutoActMs = new AtomicLong(0L);

    /**
     * The same automated action shouldn't fire twice within a human
     * window. Returns true when an action is allowed to fire now.
     */
    public boolean autoActAllowed() {
        long now = System.currentTimeMillis();
        if (now - lastAutoActMs.get() < 350L) return false;
        lastAutoActMs.set(now);
        return true;
    }

    // ------------------------------------------------------------------
    // Warm-up model
    // ------------------------------------------------------------------

    /**
     * Sessions start slower (cold hands) and warm up over the first few
     * minutes. Returns a multiplier for the session's early phase.
     */
    public double warmUpFactor() {
        long elapsed = System.currentTimeMillis() - sessionStartMs.get();
        double minutes = elapsed / 60_000d;
        return Math.min(1d, 0.75d + minutes * 0.06d);
    }

    // ------------------------------------------------------------------
    // Consistency drift window
    // ------------------------------------------------------------------

    /**
     * Consistency is measured over a sliding window; drift means the
     * player is getting better OR tired. The model returns a drift
     * direction so analysis can't see a perfectly flat consistency.
     */
    public double consistencyDrift() {
        long elapsed = System.currentTimeMillis() - sessionStartMs.get();
        double hours = elapsed / 3_600_000d;
        double fatigue = Math.min(0.5d, hours * 0.07d);
        double warm = warmUpFactor();
        return warm - fatigue;
    }

    public boolean driftPlausible() {
        double d = consistencyDrift();
        return d >= -0.5d && d <= 0.5d;
    }

    // ------------------------------------------------------------------
    // Late-session slowdown
    // ------------------------------------------------------------------

    public long lateSessionSlowdownMs() {
        double f = fatigueBounded();
        if (f < 0.1d) return 0L;
        return (long) (f * 800d) + rng.nextInt(400);
    }

    public boolean lateSession() {
        return sessionAgeMs() > 50L * 60_000L;
    }

    private long sessionAgeMs() {
        return System.currentTimeMillis() - sessionStartMs.get();
    }

    // ------------------------------------------------------------------
    // Pattern breakers
    // ------------------------------------------------------------------

    /**
     * After a run of similar actions, humans do something unrelated
     * (recall, check map, change target). The breaker inserts that
     * variability into automated loops.
     */
    public boolean patternBreakDue(int similarActions) {
        return similarActions >= 5 && rng.nextDouble() < 0.7d;
    }

    public long patternBreakMs() {
        return 400L + rng.nextInt(900);
    }

    // ------------------------------------------------------------------
    // Quick-cast threshold
    // ------------------------------------------------------------------

    /**
     * Humans quick-cast only when the target is dead-simple (face to
     * face, standing still). The model decides when quick-cast is
     * believable.
     */
    public boolean quickCastBelievable(double targetSpeed, double targetDistance) {
        if (targetSpeed > 3d) return false;
        return targetDistance < 400d && rng.nextDouble() < 0.5d;
    }

    public long quickCastMs() {
        return 40L + rng.nextInt(80);
    }

    // ------------------------------------------------------------------
    // Verify-only placeholder guard
    // ------------------------------------------------------------------

    /** Structural sanity: core state must be initialized. */
    public boolean invariantsHold() {
        if (sessionStartMs == null || focusState == null) return false;
        if (meanTapGapMs() < 0d || tapGapStdDevMs() < 0d) return false;
        return true;
    }

    // ------------------------------------------------------------------
    // Burst-cast damping
    // ------------------------------------------------------------------

    /**
     * Humans can't spam-cast forever; burst casting decays into slower
     * casting. The model tracks a burst budget per window and damps the
     * rate after it is spent.
     */
    private static final int BURST_CAST_BUDGET = 6;
    private final AtomicLong burstWindowMs = new AtomicLong(System.currentTimeMillis());
    private final java.util.concurrent.atomic.AtomicInteger burstCasts = new java.util.concurrent.atomic.AtomicInteger(0);

    public boolean burstCastAllowed() {
        long now = System.currentTimeMillis();
        if (now - burstWindowMs.get() > 3_000L) {
            burstWindowMs.set(now);
            burstCasts.set(0);
        }
        if (burstCasts.get() >= BURST_CAST_BUDGET) return false;
        burstCasts.incrementAndGet();
        return true;
    }

    public long burstRecoveryMs() {
        return 2_500L + rng.nextInt(3_500);
    }

    // ------------------------------------------------------------------
    // Action history coherence
    // ------------------------------------------------------------------

    private final Deque<Long> actionHistory = new ArrayDeque<>();

    public void noteAction() {
        actionHistory.addLast(System.currentTimeMillis());
        while (actionHistory.size() > 96) actionHistory.removeFirst();
    }

    public int actionsLastMinute() {
        long cutoff = System.currentTimeMillis() - 60_000L;
        int n = 0;
        for (Long t : actionHistory) {
            if (t >= cutoff) n++;
        }
        return n;
    }

    public boolean actionRatePlausible() {
        int n = actionsLastMinute();
        return n <= 240;
    }

    // ------------------------------------------------------------------
    // Reaction-tier ladder
    // ------------------------------------------------------------------

    /**
     * Reactions belong to tiers (instant / fast / normal / slow). A real
     * player's distribution is mostly normal with occasional fast and
     * rare instant. The ladder samples a tier accordingly.
     */
    public int reactionTier() {
        double r = rng.nextDouble();
        if (r < 0.04d) return 0; // instant
        if (r < 0.22d) return 1; // fast
        if (r < 0.92d) return 2; // normal
        return 3;                 // slow
    }

    public long tierReactionMs(int tier) {
        switch (tier) {
            case 0: return 50L + rng.nextInt(90);
            case 1: return 140L + rng.nextInt(120);
            case 2: return 260L + rng.nextInt(320);
            default: return 580L + rng.nextInt(700);
        }
    }

    // ------------------------------------------------------------------
    // First-blood / early-game caution
    // ------------------------------------------------------------------

    /**
     * Early game, humans are cautious: fewer engagements, slower casts.
     * The model damps automated intensity for the first minutes.
     */
    public float earlyGameFactor(long matchStartMs) {
        long elapsed = System.currentTimeMillis() - matchStartMs;
        if (elapsed < 90_000L) return 0.7f;
        if (elapsed < 240_000L) return 0.85f;
        return 1f;
    }

    // ------------------------------------------------------------------
    // Post-death reset behavior
    // ------------------------------------------------------------------

    /** After dying, humans take a breath before re-engaging. */
    public long postDeathPauseMs() {
        return 2_000L + rng.nextInt(3_000);
    }

    public boolean postDeathResetDue() {
        return rng.nextDouble() < 0.7d;
    }

    // ------------------------------------------------------------------
    // Focus flicker
    // ------------------------------------------------------------------

    /**
     * Focus isn't a smooth curve; it flickers moment to moment. The
     * flicker model adds that granularity to any focus-derived pacing.
     */
    public double focusFlicker() {
        return currentFocus() * (0.9d + rng.nextDouble() * 0.2d);
    }

    public boolean focusDipNow() {
        return rng.nextDouble() < 0.08d;
    }
}