package com.shadow.mlbbcheat.utils.bypass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ScanShield — memory-scan resistance layer.
 *
 * MLBB's anti-cheat and server-side heuristics look for suspicious memory
 * access patterns: bursty identical reads, perfect periodic polling, exact
 * fixed offsets read at frame-perfect cadence, and instant reactions to
 * value changes. ScanShield breaks all of those signatures:
 *
 *   1. READ SCHEDULING  — reads fire on a jittered, human-uneven cadence;
 *      never two reads at the same millisecond, never a constant period.
 *   2. CHUNKING         — large scans are split into randomized-size pieces
 *      separated by fake pauses, so no single burst is visible.
 *   3. DUMMY READ POOL  — a rotating pool of decoy reads (real addresses,
 *      junk values) is interleaved with real reads, so the read stream
 *      looks like noise rather than a targeted structure walk.
 *   4. BURST GOVERNOR   — hard cap of reads per sliding window; excess
 *      work is deferred or dropped, never queued into a visible pile.
 *   5. SEQUENCE SHUFFLE — repeated scans of the same entity list use a
 *      different read order every cycle (shuffled permutation), so no two
 *      frames produce the same access signature.
 *   6. CANARY READS     — before trusting a value, a canary read at a
 *      decoy offset must return the expected sentinel; if the game plants
 *      honeypot values at the real offset, the canary mismatch triggers
 *      escalation (see HoneypotGuard).
 *   7. VALUE PLAUSIBILITY — returned values are range-checked against
 *      semantic bounds (HP 0..65535, coords within map, level 1..30).
 *      Implausible values are suppressed and counted.
 *   8. TEMPORAL JITTER  — read timestamps are spaced using a distribution
 *      that mimics interactive polling, with occasional long pauses
 *      (switching focus) and short double-taps (human bursts).
 */
public final class ScanShield {

    private static final long MIN_READ_GAP_MS = 12L;
    private static final long MAX_READ_GAP_MS = 140L;
    private static final long FOCUS_SWITCH_MS = 900L;
    private static final int MAX_READS_PER_WINDOW = 96;
    private static final long WINDOW_MS = 1500L;
    private static final int CHUNK_MIN = 3;
    private static final int CHUNK_MAX = 9;
    private static final int DUMMY_POOL_SIZE = 24;
    private static final int CANARY_POOL_SIZE = 8;
    private static final float DUMMY_RATIO = 0.30f;
    private static final int HISTORY_SIZE = 64;
    private static final int MAX_IMPLAUSIBLE = 12;
    private static final long MAX_SILENCE_MS = 6000L;
    private static final long MIN_SESSION_GAP_MS = 4000L;
    private static final double DOUBLE_TAP_PROB = 0.06d;
    private static final double FOCUS_SWITCH_PROB = 0.05d;

    private final Random rng = new Random();
    private final AtomicLong lastReadMs = new AtomicLong(0L);
    private final AtomicLong windowStartMs = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger windowCount = new AtomicInteger(0);
    private final AtomicInteger implausibleCount = new AtomicInteger(0);
    private final ConcurrentLinkedQueue<Long> history = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<Integer, Long> canaryTable = new ConcurrentHashMap<>();
    private final List<DummyEntry> dummyPool = new ArrayList<>();
    private final List<Integer> canaryOffsets = new ArrayList<>();
    private final AtomicLong lastSessionMs = new AtomicLong(System.currentTimeMillis());

    private volatile boolean degraded = false;
    private volatile boolean suspended = false;

    public ScanShield() {
        seedDummyPool();
        seedCanaries();
    }

    // ------------------------------------------------------------------
    // Scheduler
    // ------------------------------------------------------------------

    /**
     * Whether a read may proceed right now. Called before every memory read
     * (real or dummy). Applies the burst governor and cadence jitter.
     */
    public boolean gateRead() {
        if (suspended) return false;
        long now = System.currentTimeMillis();

        long windowElapsed = now - windowStartMs.get();
        if (windowElapsed >= WINDOW_MS) {
            windowStartMs.set(now);
            windowCount.set(0);
        }
        if (windowCount.get() >= MAX_READS_PER_WINDOW) {
            return false;
        }

        long last = lastReadMs.get();
        long gap = now - last;
        if (gap < MIN_READ_GAP_MS) {
            return false;
        }
        if (gap > MAX_SILENCE_MS) {
            long sessionGap = now - lastSessionMs.get();
            if (sessionGap < MIN_SESSION_GAP_MS) return false;
            lastSessionMs.set(now);
        }

        if (rng.nextDouble() < DOUBLE_TAP_PROB) {
            windowCount.incrementAndGet();
            history.add(now);
            trimHistory();
            lastReadMs.set(now);
            return true;
        }

        if (rng.nextDouble() < FOCUS_SWITCH_PROB) {
            return false;
        }

        windowCount.incrementAndGet();
        history.add(now);
        trimHistory();
        lastReadMs.set(now);
        return true;
    }

    /** Millis to wait before the next permitted read. */
    public long waitMillis() {
        long last = lastReadMs.get();
        long gap = Math.max(0L, MIN_READ_GAP_MS - (System.currentTimeMillis() - last));
        long jitter = Math.abs(rng.nextLong()) % (MAX_READ_GAP_MS - MIN_READ_GAP_MS);
        return gap + jitter;
    }

    /** Should the caller pause entirely for a while (focus switch)? */
    public boolean focusPause() {
        return rng.nextDouble() < FOCUS_SWITCH_PROB;
    }

    // ------------------------------------------------------------------
    // Chunking
    // ------------------------------------------------------------------

    /**
     * Split a desired read count into randomized chunks. Each chunk should
     * be executed with at least one gateRead()+sleep between chunks.
     */
    public List<Integer> chunkSizes(int total) {
        List<Integer> chunks = new ArrayList<>();
        int remaining = total;
        while (remaining > 0) {
            int size = CHUNK_MIN + rng.nextInt(CHUNK_MAX - CHUNK_MIN + 1);
            if (size > remaining) size = remaining;
            chunks.add(size);
            remaining -= size;
        }
        return chunks;
    }

    // ------------------------------------------------------------------
    // Dummy reads
    // ------------------------------------------------------------------

    private static final class DummyEntry {
        final long address;
        final int value;
        final int weight;
        DummyEntry(long address, int value, int weight) {
            this.address = address;
            this.value = value;
            this.weight = weight;
        }
    }

    private void seedDummyPool() {
        dummyPool.clear();
        long base = 0x10000000L;
        for (int i = 0; i < DUMMY_POOL_SIZE; i++) {
            long addr = base + ((long) rng.nextInt(0x7FFFFF) * 4L);
            int val = 0x1000 + rng.nextInt(0xFFFF);
            int weight = 1 + rng.nextInt(8);
            dummyPool.add(new DummyEntry(addr, val, weight));
        }
    }

    /** A decoy read request (address + expected value) to interleave. */
    public DummyRequest nextDummy() {
        DummyEntry e = dummyPool.get(rng.nextInt(dummyPool.size()));
        return new DummyRequest(e.address, e.value);
    }

    public static final class DummyRequest {
        public final long address;
        public final int expectedValue;
        DummyRequest(long address, int expectedValue) {
            this.address = address;
            this.expectedValue = expectedValue;
        }
    }

    /** Whether the caller should add a dummy read before the next real one. */
    public boolean shouldDummy() {
        return rng.nextDouble() < DUMMY_RATIO;
    }

    // ------------------------------------------------------------------
    // Canaries
    // ------------------------------------------------------------------

    private void seedCanaries() {
        canaryOffsets.clear();
        int base = 0x20000000;
        for (int i = 0; i < CANARY_POOL_SIZE; i++) {
            canaryOffsets.add(base + rng.nextInt(0x3FFFFF) * 4);
        }
    }

    /** Register a canary read at a decoy offset with the expected sentinel. */
    public void plantCanary(int offset, long expected) {
        canaryTable.put(offset, expected);
    }

    /**
     * Verify a canary. Returns true if the value matches or no canary was
     * planted for this offset. A mismatch means the game (or a honeypot)
     * is writing to decoy offsets — escalate.
     */
    public boolean verifyCanary(int offset, long actual) {
        Long expected = canaryTable.get(offset);
        if (expected == null) return true;
        if (expected != actual) {
            degraded = true;
            implausibleCount.incrementAndGet();
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Value plausibility
    // ------------------------------------------------------------------

    public static boolean plausibleHp(float hp) {
        return hp >= -0.5f && hp <= 65535f;
    }

    public static boolean plausibleLevel(int level) {
        return level >= 0 && level <= 30;
    }

    public static boolean plausibleCoordinate(float v, float bound) {
        return Math.abs(v) <= bound;
    }

    public static boolean plausibleCd(float cd) {
        return cd >= -1f && cd <= 300f;
    }

    /**
     * Check a semantic bound. Implausible values increment the anomaly
     * counter; too many anomalies degrades the whole shield.
     */
    public boolean checkPlausible(String what, boolean ok) {
        if (!ok) {
            int n = implausibleCount.incrementAndGet();
            if (n >= MAX_IMPLAUSIBLE) degraded = true;
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Sequencing
    // ------------------------------------------------------------------

    /**
     * A shuffled read order for a batch of ids — every call yields a
     * different permutation so repeated scans never share a signature.
     */
    public List<Integer> shuffledOrder(List<Integer> ids) {
        List<Integer> copy = new ArrayList<>(ids);
        Collections.shuffle(copy, rng);
        if (copy.size() > 1 && rng.nextBoolean()) {
            Collections.reverse(copy);
        }
        return copy;
    }

    /** Random subset (never the full list every time). */
    public List<Integer> subsample(List<Integer> ids, float keepRatio) {
        List<Integer> copy = new ArrayList<>(ids);
        Collections.shuffle(copy, rng);
        int keep = Math.max(1, (int) (copy.size() * keepRatio));
        return new ArrayList<>(copy.subList(0, Math.min(keep, copy.size())));
    }

    // ------------------------------------------------------------------
    // History & anomaly feedback
    // ------------------------------------------------------------------

    private void trimHistory() {
        while (history.size() > HISTORY_SIZE) history.poll();
    }

    public int recentReadCount() {
        long cutoff = System.currentTimeMillis() - WINDOW_MS;
        int n = 0;
        for (Long t : history) {
            if (t >= cutoff) n++;
        }
        return n;
    }

    public boolean isDegraded() {
        return degraded;
    }

    public boolean isSuspended() {
        return suspended;
    }

    public void suspend() {
        suspended = true;
    }

    public void resume() {
        suspended = false;
        windowStartMs.set(System.currentTimeMillis());
        windowCount.set(0);
    }

    public void resetAnomalies() {
        implausibleCount.set(0);
        degraded = false;
    }

    // ------------------------------------------------------------------
    // Diagnostics
    // ------------------------------------------------------------------

    public static final class ShieldStats {
        public final int recentReads;
        public final boolean degraded;
        public final boolean suspended;
        public final int implausible;
        public final int dummyPoolSize;
        public final int canaryCount;
        public final long lastReadGapMs;
        ShieldStats(int recentReads, boolean degraded, boolean suspended,
                    int implausible, int dummyPoolSize, int canaryCount,
                    long lastReadGapMs) {
            this.recentReads = recentReads;
            this.degraded = degraded;
            this.suspended = suspended;
            this.implausible = implausible;
            this.dummyPoolSize = dummyPoolSize;
            this.canaryCount = canaryCount;
            this.lastReadGapMs = lastReadGapMs;
        }
    }

    public ShieldStats stats() {
        return new ShieldStats(
                recentReadCount(),
                degraded,
                suspended,
                implausibleCount.get(),
                dummyPool.size(),
                canaryTable.size(),
                Math.max(0L, System.currentTimeMillis() - lastReadMs.get()));
    }

    // ------------------------------------------------------------------
    // Pressure model
    // ------------------------------------------------------------------

    /**
     * Model current "pressure": how much read activity the environment is
     * seeing. High pressure → the shield automatically tightens (longer
     * gaps, more dummies, smaller chunks) without any external signal.
     */
    public int pressureLevel() {
        int recent = recentReadCount();
        if (recent > MAX_READS_PER_WINDOW * 2 / 3) return 3;
        if (recent > MAX_READS_PER_WINDOW / 2) return 2;
        if (recent > MAX_READS_PER_WINDOW / 3) return 1;
        return 0;
    }

    public long tightenedGap() {
        int p = pressureLevel();
        long base = MIN_READ_GAP_MS;
        switch (p) {
            case 3: return base * 6 + rng.nextInt(80);
            case 2: return base * 3 + rng.nextInt(40);
            case 1: return base * 2 + rng.nextInt(20);
            default: return base + rng.nextInt(24);
        }
    }

    public float tightenedDummyRatio() {
        return Math.min(0.7f, DUMMY_RATIO + pressureLevel() * 0.1f);
    }

    // ------------------------------------------------------------------
    // Access fingerprint
    // ------------------------------------------------------------------

    /**
     * A compact fingerprint of the last reads; used by the watchdog to
     * confirm the shield is actually varying (a constant fingerprint means
     * the randomization failed — itself a red flag).
     */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (Long t : history) {
            sb.append((t % 97) % 10);
            if (++i >= 12) break;
        }
        return sb.toString();
    }

    public boolean fingerprintVaries() {
        return history.size() >= 4;
    }

    // ------------------------------------------------------------------
    // Session model
    // ------------------------------------------------------------------

    private final AtomicLong sessionStartMs = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger sessionReads = new AtomicInteger(0);

    public void noteSessionStart() {
        sessionStartMs.set(System.currentTimeMillis());
        sessionReads.set(0);
    }

    public void noteSessionRead() {
        sessionReads.incrementAndGet();
    }

    public double readsPerSecondThisSession() {
        long elapsed = Math.max(1L, System.currentTimeMillis() - sessionStartMs.get());
        return sessionReads.get() * 1000.0d / elapsed;
    }

    /** Human-like reading: ~2-14 reads per second, drifting over time. */
    public boolean sessionPaceHuman() {
        double rps = readsPerSecondThisSession();
        return rps >= 1.0d && rps <= 18.0d;
    }

    // ------------------------------------------------------------------
    // Read-budget governor
    // ------------------------------------------------------------------

    private static final int BUDGET_PER_MINUTE = 240;
    private static final int BUDGET_CRITICAL = 60;
    private final AtomicInteger minuteBudget = new AtomicInteger(BUDGET_PER_MINUTE);
    private final AtomicLong budgetResetMs = new AtomicLong(System.currentTimeMillis());

    /** Consume budget for one real read; false means wait for the next minute. */
    public boolean consumeBudget() {
        long now = System.currentTimeMillis();
        if (now - budgetResetMs.get() >= 60_000L) {
            budgetResetMs.set(now);
            minuteBudget.set(BUDGET_PER_MINUTE);
        }
        if (minuteBudget.get() <= 0) return false;
        minuteBudget.decrementAndGet();
        return true;
    }

    public int budgetLeft() {
        long now = System.currentTimeMillis();
        if (now - budgetResetMs.get() >= 60_000L) {
            budgetResetMs.set(now);
            minuteBudget.set(BUDGET_PER_MINUTE);
        }
        return minuteBudget.get();
    }

    /** Critical when the minute budget is nearly exhausted. */
    public boolean budgetCritical() {
        return budgetLeft() < BUDGET_CRITICAL;
    }

    /** Reads-per-minute estimate from the last 60s of history. */
    public int readsPerMinute() {
        long cutoff = System.currentTimeMillis() - 60_000L;
        int n = 0;
        for (Long t : history) {
            if (t >= cutoff) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------
    // Cadence tables
    // ------------------------------------------------------------------

    private static final long[] CADENCE_POOL_MS = {
            28L, 31L, 36L, 44L, 47L, 52L, 58L, 64L, 71L, 79L, 88L, 97L,
            105L, 118L, 133L, 149L, 162L, 181L, 203L, 227L, 254L, 289L,
            322L, 361L, 407L, 451L, 508L, 574L, 631L, 712L
    };

    /** Pick the next inter-read gap from a weighted cadence table. */
    public long nextCadenceGapMs() {
        double r = rng.nextDouble();
        if (r < 0.35d) return CADENCE_POOL_MS[rng.nextInt(14)];
        if (r < 0.75d) return CADENCE_POOL_MS[14 + rng.nextInt(10)];
        if (r < 0.95d) return CADENCE_POOL_MS[24 + rng.nextInt(4)];
        return 900L + rng.nextInt(1400);
    }

    /** Multi-step cadence for a chunked read (returns per-chunk gaps). */
    public List<Long> chunkCadence(int chunks) {
        List<Long> out = new ArrayList<>(chunks);
        for (int i = 0; i < chunks; i++) {
            long base = nextCadenceGapMs();
            if (i == 0) base += 40 + rng.nextInt(120);
            out.add(base);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Chunk interleave planner
    // ------------------------------------------------------------------

    /**
     * Build a read plan: chunk sizes plus per-chunk delays, interleaved
     * with dummy addresses, so the executed stream never shows a clean
     * contiguous pattern.
     */
    public List<Long> interleavePlan(int totalReads) {
        List<Integer> chunks = chunkSizes(totalReads);
        List<Long> plan = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            plan.add(-1L); // dummy slot
            plan.add(-2L); // pacing slot
            plan.add(-3L); // dummy slot
            for (int r = 0; r < chunks.get(i); r++) {
                plan.add(0L); // real read
            }
        }
        return plan;
    }

    public long planStepDelay(long slot) {
        if (slot == -1L || slot == -3L) return 12L + rng.nextInt(38);
        if (slot == -2L) return nextCadenceGapMs();
        return 8L + rng.nextInt(22);
    }

    // ------------------------------------------------------------------
    // Canary mesh
    // ------------------------------------------------------------------

    private final Map<Integer, Long> canaryMesh = new HashMap<>();

    /**
     * A canary mesh plants N related canaries that must stay coherent
     * (e.g. base pointer + offsets derived from it). A single mismatch
     * is noise; a coherent shift across the mesh means the world moved.
     */
    public void plantCanaryMesh(int base, long expected) {
        canaryMesh.put(base, expected);
        for (int d = 1; d <= 4; d++) {
            canaryMesh.put(base + d * 0x40, expected + d);
        }
    }

    public boolean verifyCanaryMesh(int base, long actual) {
        Long exp = canaryMesh.get(base);
        if (exp == null) return false;
        boolean ok = true;
        for (Map.Entry<Integer, Long> e : canaryMesh.entrySet()) {
            int dist = (e.getKey() - base) / 0x40;
            if (actual + dist != e.getValue()) {
                ok = false;
                break;
            }
        }
        return ok;
    }

    public int canaryMeshSize() {
        return canaryMesh.size();
    }

    // ------------------------------------------------------------------
    // Anomaly escalation ladder
    // ------------------------------------------------------------------

    private static final int[] LADDER_THRESHOLDS = {2, 5, 9, 14};
    private final AtomicInteger ladderStep = new AtomicInteger(0);
    private final AtomicLong lastLadderMs = new AtomicLong(0L);

    /**
     * Feed an anomaly event. Each threshold steps the ladder up; the
     * ladder controls how aggressively reads are throttled. Steps decay
     * naturally over time.
     */
    public int feedAnomaly() {
        int current = ladderStep.get();
        for (int i = 0; i < LADDER_THRESHOLDS.length; i++) {
            if (current >= LADDER_THRESHOLDS[i]) continue;
            if (anomalySince(i) > 0) {
                ladderStep.set(current + 1);
            }
        }
        lastLadderMs.set(System.currentTimeMillis());
        return ladderStep.get();
    }

    private long anomalySince(int ignored) {
        long now = System.currentTimeMillis();
        return now - lastLadderMs.get();
    }

    public int ladderStep() {
        long now = System.currentTimeMillis();
        long idle = now - lastLadderMs.get();
        if (idle > 90_000L && ladderStep.get() > 0) {
            ladderStep.decrementAndGet();
            lastLadderMs.set(now);
        }
        return ladderStep.get();
    }

    public long ladderGapMs() {
        int step = ladderStep();
        if (step <= 0) return nextCadenceGapMs();
        if (step == 1) return nextCadenceGapMs() * 2 + 200;
        if (step == 2) return nextCadenceGapMs() * 4 + 500;
        if (step == 3) return nextCadenceGapMs() * 8 + 1200;
        return nextCadenceGapMs() * 16 + 3000;
    }

    // ------------------------------------------------------------------
    // Read-mask interleave
    // ------------------------------------------------------------------

    /**
     * Interleave bit masks so consecutive reads touch different byte
     * ranges (never a clean stride that pattern-matching can detect).
     */
    public int interleaveMask(int readIndex) {
        int phase = readIndex % 7;
        switch (phase) {
            case 0: return 0x0F;
            case 1: return 0xF0;
            case 2: return 0x3C;
            case 3: return 0xC3;
            case 4: return 0xAA;
            case 5: return 0x55;
            default: return 0xFF;
        }
    }

    public int maskedRead(int value, int readIndex) {
        return value & interleaveMask(readIndex);
    }

    // ------------------------------------------------------------------
    // Session fatigue model
    // ------------------------------------------------------------------

    private final AtomicLong sessionFatigueMs = new AtomicLong(0L);

    /** Fatigue grows with reads per session; after long sessions reads get slower. */
    public double fatigueFactor() {
        double reads = readsPerSecondThisSession();
        long sessionAgeMs = System.currentTimeMillis() - sessionStartMs.get();
        double hours = sessionAgeMs / 3_600_000d;
        double fatigue = Math.min(0.45d, reads / 60d + hours * 0.03d);
        return 1d - fatigue;
    }

    public long fatiguedGap() {
        return (long) (nextCadenceGapMs() / Math.max(0.55d, fatigueFactor()));
    }

    public boolean fatigued() {
        return sessionAgeMs() > 45L * 60_000L;
    }

    private long sessionAgeMs() {
        return System.currentTimeMillis() - sessionStartMs.get();
    }

    // ------------------------------------------------------------------
    // Randomized recheck scheduler
    // ------------------------------------------------------------------

    private final AtomicLong lastRecheckMs = new AtomicLong(0L);

    /**
     * When to run the next canary/fingerprint recheck. Jittered so the
     * recheck cadence itself isn't periodic.
     */
    public long nextRecheckDelayMs() {
        return 18_000L + rng.nextInt(42_000);
    }

    public boolean recheckDue() {
        return System.currentTimeMillis() - lastRecheckMs.get() >= nextRecheckDelayMs();
    }

    public void markRecheckDone() {
        lastRecheckMs.set(System.currentTimeMillis());
    }

    // ------------------------------------------------------------------
    // Timing profile
    // ------------------------------------------------------------------

    private final List<Long> timingSamples = new ArrayList<>();
    private long timingSum = 0L;

    /** Record one read duration; keeps a bounded sliding window. */
    public void noteReadDuration(long ns) {
        timingSamples.add(ns);
        timingSum += ns;
        if (timingSamples.size() > 128) {
            timingSum -= timingSamples.remove(0);
        }
    }

    public double meanReadDurationNs() {
        if (timingSamples.isEmpty()) return 0d;
        return timingSum / (double) timingSamples.size();
    }

    public double readDurationStdDevNs() {
        if (timingSamples.size() < 2) return 0d;
        double mean = meanReadDurationNs();
        double var = 0d;
        for (long s : timingSamples) {
            double d = s - mean;
            var += d * d;
        }
        return Math.sqrt(var / (timingSamples.size() - 1));
    }

    /** Flat timing (stddev ~ 0) is a tracing signature; flag it. */
    public boolean timingSuspiciouslyFlat() {
        if (timingSamples.size() < 8) return false;
        double mean = meanReadDurationNs();
        if (mean < 1d) return false;
        return readDurationStdDevNs() / mean < 0.02d;
    }

    // ------------------------------------------------------------------
    // Watchdog handshake
    // ------------------------------------------------------------------

    private final AtomicLong handshakeNonce = new AtomicLong(0L);

    /** The watchdog challenges the shield with a nonce; the shield signs it. */
    public long handshakeChallenge() {
        long nonce = System.nanoTime() ^ rng.nextLong();
        handshakeNonce.set(nonce);
        return nonce;
    }

    public long handshakeResponse() {
        long n = handshakeNonce.get();
        return (n ^ 0x5DEECE66DL) + (n >>> 17);
    }

    public boolean handshakeValid(long response) {
        return response == handshakeResponse();
    }

    // ------------------------------------------------------------------
    // Read kind tracking
    // ------------------------------------------------------------------

    private final AtomicInteger realReads = new AtomicInteger(0);
    private final AtomicInteger dummyReads = new AtomicInteger(0);

    public void noteRealRead() {
        realReads.incrementAndGet();
    }

    public void noteDummyRead() {
        dummyReads.incrementAndGet();
    }

    public double dummyRatio() {
        int total = realReads.get() + dummyReads.get();
        return total == 0 ? 0d : dummyReads.get() / (double) total;
    }

    public boolean dummyRatioHuman() {
        return dummyRatio() <= 0.75d;
    }

    // ------------------------------------------------------------------
    // Burst history decorrelation
    // ------------------------------------------------------------------

    private final ConcurrentLinkedQueue<Long> burstHistory = new ConcurrentLinkedQueue<>();

    /**
     * Record a burst (N reads in quick succession). Keeps a sliding window
     * so the shield can verify burst shapes stay human-like.
     */
    public void noteBurst(int size) {
        burstHistory.add(System.currentTimeMillis());
        while (burstHistory.size() > 64) burstHistory.poll();
    }

    public int burstsInWindowMs(long windowMs) {
        long cutoff = System.currentTimeMillis() - windowMs;
        int n = 0;
        for (Long t : burstHistory) {
            if (t >= cutoff) n++;
        }
        return n;
    }

    public boolean burstShapeHuman() {
        return burstsInWindowMs(60_000L) <= 40;
    }

    // ------------------------------------------------------------------
    // Pause shaping
    // ------------------------------------------------------------------

    /**
     * Human focus drifts: after a long quiet period, the first read back
     * should come with a "noticing" delay, not instantly. Pause shaping
     * produces that recovery curve.
     */
    public long recoveryDelayMs() {
        long quiet = System.currentTimeMillis() - lastReadMs.get();
        if (quiet > 30_000L) return 250L + rng.nextInt(700);
        if (quiet > 8_000L) return 120L + rng.nextInt(240);
        return 0L;
    }

    public boolean shouldPauseLong() {
        return rng.nextDouble() < 0.06d;
    }

    public long longPauseMs() {
        return 3_000L + rng.nextInt(12_000);
    }

    // ------------------------------------------------------------------
    // Dummy pool evolution
    // ------------------------------------------------------------------

    /**
     * Occasionally rotate the dummy pool so the decoy addresses drift over
     * time (a static dummy set is itself a fingerprint).
     */
    public void maybeRotateDummyPool() {
        if (rng.nextDouble() < 0.15d) {
            seedDummyPool();
        }
    }

    // ------------------------------------------------------------------
    // Read-depth model
    // ------------------------------------------------------------------

    /**
     * Model read "depth": shallow reads (few offsets per entity) look like
     * casual inspection; deep reads look like a full struct walk. The shield
     * suggests a depth and verifies the caller stays plausible.
     */
    public int suggestedDepth() {
        int p = pressureLevel();
        if (p >= 2) return 1 + rng.nextInt(2);
        return 2 + rng.nextInt(3);
    }

    public boolean depthPlausible(int depth) {
        return depth >= 1 && depth <= 6;
    }

    // ------------------------------------------------------------------
    // Idle entropy pacing
    // ------------------------------------------------------------------

    private final AtomicLong lastIdleTickMs = new AtomicLong(0L);

    /**
     * Even when nothing needs reading, the shield spends entropy on
     * harmless housekeeping so the process activity curve never goes
     * completely flat (flat = suspicious to CPU-profile analysis).
     */
    public boolean idleWorkDue() {
        long now = System.currentTimeMillis();
        if (now - lastIdleTickMs.get() < 4_000L) return false;
        lastIdleTickMs.set(now);
        return rng.nextDouble() < 0.8d;
    }

    public boolean idleWorkHeavy() {
        return rng.nextDouble() < 0.1d;
    }

    // ------------------------------------------------------------------
    // Consecutive-skip guard
    // ------------------------------------------------------------------

    private final AtomicInteger consecutiveSkips = new AtomicInteger(0);

    /** Track consecutive gate denials; too many = the caller should yield. */
    public void noteSkip() {
        consecutiveSkips.incrementAndGet();
    }

    public void noteAllowed() {
        consecutiveSkips.set(0);
    }

    public boolean skipSpiral() {
        return consecutiveSkips.get() >= 6;
    }

    public long skipSpiralYieldMs() {
        return 400L + rng.nextInt(900);
    }

    // ------------------------------------------------------------------
    // Round-trip verifier
    // ------------------------------------------------------------------

    /**
     * Verify that a value read from memory actually changes over time.
     * A value frozen forever (or changing in lockstep with the clock)
     * is a red flag for a honeypot or a stale mapping.
     */
    private final Map<Integer, long[]> valueHistory = new HashMap<>();

    public void noteValue(int offset, long value) {
        long[] h = valueHistory.get(offset);
        if (h == null) {
            h = new long[4];
            valueHistory.put(offset, h);
        }
        h[3] = h[2];
        h[2] = h[1];
        h[1] = h[0];
        h[0] = value;
    }

    public boolean valueStable(int offset) {
        long[] h = valueHistory.get(offset);
        if (h == null) return true;
        return h[0] == h[1] && h[1] == h[2] && h[2] == h[3];
    }

    public boolean valueOscillates(int offset) {
        long[] h = valueHistory.get(offset);
        if (h == null) return false;
        return h[0] != h[1] && h[1] != h[2] && h[2] != h[3];
    }

    // ------------------------------------------------------------------
    // Scan-width governor
    // ------------------------------------------------------------------

    private static final int WIDTH_NARROW = 4;
    private static final int WIDTH_WIDE = 64;

    /**
     * Suggest a scan width (entities or offsets per pass). Wide scans are
     * the most visible pattern; the governor narrows them under pressure.
     */
    public int suggestedWidth() {
        int p = pressureLevel();
        if (p >= 3) return WIDTH_NARROW;
        if (p == 2) return WIDTH_NARROW + rng.nextInt(8);
        if (p == 1) return 12 + rng.nextInt(20);
        return WIDTH_WIDE - rng.nextInt(24);
    }

    public boolean widthPlausible(int width) {
        return width >= WIDTH_NARROW && width <= WIDTH_WIDE;
    }

    // ------------------------------------------------------------------
    // Time-of-day envelope
    // ------------------------------------------------------------------

    /**
     * Device usage follows a daily envelope (busy evening, quiet dawn).
     * The shield slows reads inside the quiet envelope so the device
     * activity profile matches the hour.
     */
    public double envelopeFactor() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
        if (hour >= 23 || hour < 6) return 0.45d;
        if (hour < 9) return 0.75d;
        if (hour >= 22) return 0.6d;
        return 1.0d;
    }

    public long envelopedGap() {
        return (long) (nextCadenceGapMs() / Math.max(0.35d, envelopeFactor()));
    }

    // ------------------------------------------------------------------
    // Self-check assertion
    // ------------------------------------------------------------------

    /**
     * Structural self-check: all internal invariants must hold. The
     * watchdog calls this periodically; a failure means the shield state
     * is corrupted (possibly by tampering).
     */
    public boolean invariantsHold() {
        if (canaryTable == null || dummyPool == null) return false;
        if (history == null) return false;
        if (MIN_READ_GAP_MS > MAX_READ_GAP_MS) return false;
        if (WINDOW_MS <= 0L) return false;
        return budgetLeft() >= 0;
    }

    // ------------------------------------------------------------------
    // Entropy sink
    // ------------------------------------------------------------------

    private final AtomicLong entropyPool = new AtomicLong(System.nanoTime());

    /** Refill the entropy pool from wall clock + counter (never pure rng). */
    public void stirEntropy() {
        long mix = System.nanoTime() ^ (System.currentTimeMillis() << 13)
                ^ rng.nextLong();
        entropyPool.set(entropyPool.get() * 31L + mix);
    }

    public long entropySample() {
        return entropyPool.get();
    }

    // ------------------------------------------------------------------
    // Result classification
    // ------------------------------------------------------------------

    /**
     * Classify a read result so the shield can adjust: expected values,
     * garbage, frozen data, or trap values each get different handling.
     */
    public int classifyResult(long expected, long actual) {
        if (actual == expected) return 0;
        if ((actual & 0xFFFFFFFFL) == (expected & 0xFFFFFFFFL)) return 1;
        if (actual == 0L) return 2;
        if (Math.abs(actual - expected) > 0x10000000L) return 3;
        return 4;
    }

    public boolean resultPlausible(long expected, long actual) {
        return classifyResult(expected, actual) <= 1;
    }
}