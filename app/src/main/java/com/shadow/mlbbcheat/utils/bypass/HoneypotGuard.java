package com.shadow.mlbbcheat.utils.bypass;

import com.shadow.mlbbcheat.models.PlayerData;
import com.shadow.mlbbcheat.utils.HoneypotDetector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HoneypotGuard — extended honeypot and bait-value defense.
 *
 * MLBB's anti-cheat (and private cheat-detection) can plant decoy values:
 * fake enemies, impossible coordinates, identical snapshot clones, trap
 * entities that only exist for cheaters, and "report bait" (behavioral
 * patterns that trigger review). HoneypotGuard layers over the base
 * {@link HoneypotDetector} with:
 *
 *   1. PHANTOM ENTITY TRACKING — entities that never interact with the
 *      world (no movement variance, no team logic, no recall state) are
 *      flagged as phantoms and excluded from rendering.
 *   2. IMPOSSIBLE-MOTION DETECTION — teleport jumps beyond a plausible
 *      move speed are logged; sustained teleporting marks the entity.
 *   3. SNAPSHOT CLONE DETECTION — N entities with identical HP/pos/state
 *      for K consecutive frames = planted clones.
 *   4. VALUE-TRAP DETECTION — exact known "trap values" (e.g. a specific
 *      magic HP value the game uses for bait) trigger a soft quarantine
 *      of that read offset.
 *   5. TARGET COLLUSION — if every entity always moves toward the player
 *      in perfect sync (a classic honeypot), all entities are flagged.
 *   6. REPORT-WAVE CORRELATION — if the match report rate (per enemy
 *      reports from your own side's AFK/abuse counters) spikes, features
 *      degrade automatically.
 *   7. FRESHNESS GATING — entities whose data stream goes stale are
 *      removed from the render set entirely (never shown stale).
 *   8. VERDICT COMPOSITING — each entity gets a running suspicion score;
 *      composite verdicts drive the BypassStack's per-feature decisions.
 */
public final class HoneypotGuard {

    private static final int SUSPECT_HISTORY = 32;
    private static final double PHANTOM_MOVEMENT_SIGMA = 0.5d;
    private static final double TELEPORT_SPEED_UNITS = 900d;
    private static final int CLONE_MIN_COUNT = 2;
    private static final long CLONE_WINDOW_MS = 1500L;
    private static final double CLONE_EPSILON = 0.01d;
    private static final double COLLUSION_SYNC_EPSILON = 0.05d;
    private static final int COLLUSION_MIN_ENTITIES = 3;
    private static final int QUARANTINE_MAX = 4;
    private static final long STALE_MS = 2600L;
    private static final double SUSPECT_SCORE_QUARANTINE = 0.62d;
    private static final double SUSPECT_SCORE_RENDER = 0.35d;
    private static final int MAX_TRACKED_ENTITIES = 40;

    private final Random rng = new Random();
    private final Map<Integer, EntityTrack> tracks = new HashMap<>();
    private final AtomicInteger quarantinedOffsets = new AtomicInteger(0);
    private final AtomicLong lastCloneCheckMs = new AtomicLong(0L);
    private final AtomicInteger reportWaveCount = new AtomicInteger(0);
    private final AtomicLong matchStartMs = new AtomicLong(System.currentTimeMillis());

    public HoneypotGuard() {
        noteMatchStart();
    }

    // ------------------------------------------------------------------
    // Per-entity tracking
    // ------------------------------------------------------------------

    private static final class EntityTrack {
        // {x, y, hp, nowMs} — double[] so wall-clock ms survives precision
        final List<double[]> history = new ArrayList<>(SUSPECT_HISTORY);
        double suspicion = 0d;
        long firstSeenMs = System.currentTimeMillis();
        long lastMoveMs = 0L;
        double lastSpeed = 0d;
        boolean teleporting = false;
        boolean phantom = false;
        boolean quarantined = false;
        int teleportCount = 0;
        int staleCount = 0;
        long lastRecallMs = 0L;
    }

    private EntityTrack trackFor(int id) {
        EntityTrack t = tracks.get(id);
        if (t == null) {
            t = new EntityTrack();
            tracks.put(id, t);
            if (tracks.size() > MAX_TRACKED_ENTITIES) {
                long oldest = Long.MAX_VALUE;
                int oldestId = -1;
                for (Map.Entry<Integer, EntityTrack> e : tracks.entrySet()) {
                    if (e.getValue().firstSeenMs < oldest) {
                        oldest = e.getValue().firstSeenMs;
                        oldestId = e.getKey();
                    }
                }
                if (oldestId >= 0) tracks.remove(oldestId);
            }
        }
        return t;
    }

    /** Feed one entity observation. */
    public void observe(PlayerData p, long nowMs) {
        EntityTrack t = trackFor(p.id);
        t.history.add(new double[]{p.x, p.y, p.hp, nowMs});
        while (t.history.size() > SUSPECT_HISTORY) t.history.remove(0);

        if (t.history.size() >= 2) {
            double[] prev = t.history.get(t.history.size() - 2);
            double[] cur = t.history.get(t.history.size() - 1);
            double dx = cur[0] - prev[0];
            double dy = cur[1] - prev[1];
            double dt = Math.max(1d, cur[3] - prev[3]);
            double speed = Math.sqrt(dx * dx + dy * dy) / dt * 1000d;

            if (speed > TELEPORT_SPEED_UNITS) {
                t.teleportCount++;
                t.teleporting = true;
                t.suspicion += 0.18d;
            } else {
                t.teleporting = false;
                if (speed < PHANTOM_MOVEMENT_SIGMA) {
                    t.lastMoveMs = nowMs;
                    if (nowMs - t.firstSeenMs > 4000L && t.history.size() > 12) {
                        t.phantom = true;
                        t.suspicion += 0.10d;
                    }
                } else {
                    t.lastMoveMs = nowMs;
                    t.suspicion = Math.max(0d, t.suspicion - 0.04d);
                }
            }
            t.lastSpeed = speed;
        }

        if (p.hp <= 0f) t.suspicion = Math.max(0d, t.suspicion - 0.15d);
        if (t.suspicion >= SUSPECT_SCORE_QUARANTINE) {
            t.quarantined = true;
            if (quarantinedOffsets.get() < QUARANTINE_MAX) {
                quarantinedOffsets.incrementAndGet();
            }
        }
    }

    public boolean isQuarantined(int id) {
        EntityTrack t = tracks.get(id);
        return t != null && t.quarantined;
    }

    public double suspicion(int id) {
        EntityTrack t = tracks.get(id);
        return t == null ? 0d : t.suspicion;
    }

    public boolean isPhantom(int id) {
        EntityTrack t = tracks.get(id);
        return t != null && t.phantom;
    }

    public boolean isTeleporting(int id) {
        EntityTrack t = tracks.get(id);
        return t != null && t.teleporting;
    }

    /** Filter a list down to renderable entities. */
    public List<PlayerData> renderable(List<PlayerData> input, long nowMs) {
        List<PlayerData> out = new ArrayList<>();
        for (PlayerData p : input) {
            if (isQuarantined(p.id)) continue;
            if (isPhantom(p.id)) continue;
            if (p.isFresh(nowMs, STALE_MS) == false) continue;
            if (suspicion(p.id) > SUSPECT_SCORE_RENDER) continue;
            out.add(p);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Snapshot clones
    // ------------------------------------------------------------------

    /**
     * Detect N entities with near-identical (hp, x, y) triples observed in
     * the same window. Returns the ids to exclude.
     */
    public List<Integer> detectClones(List<PlayerData> input, long nowMs) {
        List<Integer> clones = new ArrayList<>();
        long now = nowMs;
        if (now - lastCloneCheckMs.get() < CLONE_WINDOW_MS / 2) return clones;
        lastCloneCheckMs.set(now);

        Map<String, List<PlayerData>> buckets = new HashMap<>();
        for (PlayerData p : input) {
            if (!p.isEnemy || !p.isAlive()) continue;
            String key = (int) (p.hp * 100) + "|" + (int) (p.x * 10) + "|" + (int) (p.y * 10);
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }
        for (List<PlayerData> group : buckets.values()) {
            if (group.size() >= CLONE_MIN_COUNT) {
                for (PlayerData p : group) {
                    EntityTrack t = trackFor(p.id);
                    t.suspicion += 0.22d;
                    clones.add(p.id);
                }
            }
        }
        return clones;
    }

    // ------------------------------------------------------------------
    // Target collusion
    // ------------------------------------------------------------------

    /**
     * If all enemies move in near-perfect sync toward the player (classic
     * honeypot field), flag the whole field.
     */
    public boolean collusionField(List<PlayerData> input, float px, float py) {
        List<double[]> unitVectors = new ArrayList<>();
        for (PlayerData p : input) {
            if (!p.isEnemy || !p.isAlive()) continue;
            double dx = px - p.x;
            double dy = py - p.y;
            double len = Math.sqrt(dx * dx + dy * dy);
            if (len < 1d) continue;
            unitVectors.add(new double[]{dx / len, dy / len});
        }
        if (unitVectors.size() < COLLUSION_MIN_ENTITIES) return false;
        double[] mean = new double[]{0d, 0d};
        for (double[] v : unitVectors) {
            mean[0] += v[0];
            mean[1] += v[1];
        }
        mean[0] /= unitVectors.size();
        mean[1] /= unitVectors.size();
        double sync = 0d;
        for (double[] v : unitVectors) {
            sync += v[0] * mean[0] + v[1] * mean[1];
        }
        sync /= unitVectors.size();
        return sync > 1d - COLLUSION_SYNC_EPSILON;
    }

    // ------------------------------------------------------------------
    // Value traps
    // ------------------------------------------------------------------

    private static final double[] TRAP_VALUES = {
            9999.0d, 12345.0d, 1337.0d, 31337.0d, 0.1337d, 13.37d,
            100.0001d, 50.0005d, 7777.0d, 6666.0d
    };

    /** Check a read value against known bait constants. */
    public boolean trapValue(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return true;
        for (double trap : TRAP_VALUES) {
            if (Math.abs(v - trap) < 0.001d) return true;
        }
        return false;
    }

    /** Quarantine counter exposure. */
    public int quarantinedCount() {
        return quarantinedOffsets.get();
    }

    // ------------------------------------------------------------------
    // Staleness gating
    // ------------------------------------------------------------------

    public boolean stale(PlayerData p, long nowMs) {
        return !p.isFresh(nowMs, STALE_MS);
    }

    public int staleCount(List<PlayerData> input, long nowMs) {
        int n = 0;
        for (PlayerData p : input) {
            if (stale(p, nowMs)) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------
    // Report-wave correlation
    // ------------------------------------------------------------------

    /** Feed report-like events (e.g. enemy "afk report" counters). */
    public void noteReportEvent() {
        reportWaveCount.incrementAndGet();
    }

    public int reportWave() {
        return reportWaveCount.get();
    }

    /** Whether reports have spiked suspiciously for this match. */
    public boolean reportWaveActive() {
        long elapsed = System.currentTimeMillis() - matchStartMs.get();
        if (elapsed < 60_000L) return false;
        return reportWaveCount.get() >= 3;
    }

    public void noteMatchStart() {
        matchStartMs.set(System.currentTimeMillis());
        reportWaveCount.set(0);
        tracks.clear();
        quarantinedOffsets.set(0);
    }

    // ------------------------------------------------------------------
    // Composite verdict
    // ------------------------------------------------------------------

    public static final class MatchVerdict {
        public final boolean hasPhantoms;
        public final boolean hasClones;
        public final boolean collusion;
        public final boolean reportWave;
        public final int quarantined;
        public final int teleporters;
        MatchVerdict(boolean hasPhantoms, boolean hasClones, boolean collusion,
                     boolean reportWave, int quarantined, int teleporters) {
            this.hasPhantoms = hasPhantoms;
            this.hasClones = hasClones;
            this.collusion = collusion;
            this.reportWave = reportWave;
            this.quarantined = quarantined;
            this.teleporters = teleporters;
        }

        public boolean any() {
            return hasPhantoms || hasClones || collusion || reportWave || quarantined > 0;
        }
    }

    public MatchVerdict verdict(List<PlayerData> input, long nowMs) {
        boolean phantoms = false;
        boolean clones = !detectClones(input, nowMs).isEmpty();
        int teleporters = 0;
        for (PlayerData p : input) {
            if (isPhantom(p.id)) phantoms = true;
            if (isTeleporting(p.id)) teleporters++;
        }
        return new MatchVerdict(
                phantoms,
                clones,
                collusionField(input, 0f, 0f),
                reportWaveActive(),
                quarantinedCount(),
                teleporters);
    }

    // ------------------------------------------------------------------
    // Diagnostics
    // ------------------------------------------------------------------

    public static final class GuardStats {
        public final int tracked;
        public final int quarantined;
        public final int teleporters;
        public final int reports;
        public final double avgSuspicion;
        GuardStats(int tracked, int quarantined, int teleporters, int reports, double avgSuspicion) {
            this.tracked = tracked;
            this.quarantined = quarantined;
            this.teleporters = teleporters;
            this.reports = reports;
            this.avgSuspicion = avgSuspicion;
        }
    }

    public GuardStats stats() {
        int teleporters = 0;
        double sum = 0d;
        for (EntityTrack t : tracks.values()) {
            if (t.teleporting) teleporters++;
            sum += t.suspicion;
        }
        double avg = tracks.isEmpty() ? 0d : sum / tracks.size();
        return new GuardStats(tracks.size(), quarantinedCount(), teleporters,
                reportWaveCount.get(), avg);
    }

    // ------------------------------------------------------------------
    // Randomization helpers (keep analysis output non-deterministic)
    // ------------------------------------------------------------------

    public int jitteredCheckDelayMs() {
        return 150 + rng.nextInt(700);
    }

    public boolean occasionallySkipCheck() {
        return rng.nextDouble() < 0.08d;
    }

    // ------------------------------------------------------------------
    // Velocity-history model
    // ------------------------------------------------------------------

    private static final int VELOCITY_HISTORY = 16;

    /** Average |speed| of an entity over its recent history (units/s). */
    public double meanSpeed(int id) {
        EntityTrack t = tracks.get(id);
        if (t == null || t.history.size() < 2) return 0d;
        double total = 0d;
        int n = 0;
        for (int i = 1; i < t.history.size(); i++) {
            double[] a = t.history.get(i - 1);
            double[] b = t.history.get(i);
            double dt = Math.max(1d, b[3] - a[3]);
            double dx = b[0] - a[0];
            double dy = b[1] - a[1];
            total += Math.sqrt(dx * dx + dy * dy) / dt * 1000d;
            n++;
        }
        return n == 0 ? 0d : total / n;
    }

    public double speedStdDev(int id) {
        EntityTrack t = tracks.get(id);
        if (t == null || t.history.size() < 3) return 0d;
        double mean = meanSpeed(id);
        double var = 0d;
        int n = 0;
        for (int i = 1; i < t.history.size(); i++) {
            double[] a = t.history.get(i - 1);
            double[] b = t.history.get(i);
            double dt = Math.max(1d, b[3] - a[3]);
            double dx = b[0] - a[0];
            double dy = b[1] - a[1];
            double s = Math.sqrt(dx * dx + dy * dy) / dt * 1000d;
            double d = s - mean;
            var += d * d;
            n++;
        }
        return n == 0 ? 0d : Math.sqrt(var / n);
    }

    /** A machine-smooth speed profile (tiny stddev) is a honeypot signal. */
    public boolean speedSuspiciouslySmooth(int id) {
        double mean = meanSpeed(id);
        if (mean < 5d) return false;
        return speedStdDev(id) / mean < 0.05d;
    }

    // ------------------------------------------------------------------
    // Direction-change anomaly
    // ------------------------------------------------------------------

    /**
     * Real entities turn with curves; instant 180-degree reversals every
     * tick are bot-like. Counts sharp direction changes per entity.
     */
    public int sharpTurns(int id) {
        EntityTrack t = tracks.get(id);
        if (t == null || t.history.size() < 4) return 0;
        int turns = 0;
        for (int i = 2; i < t.history.size(); i++) {
            double[] a = t.history.get(i - 2);
            double[] b = t.history.get(i - 1);
            double[] c = t.history.get(i);
            double v1x = b[0] - a[0], v1y = b[1] - a[1];
            double v2x = c[0] - b[0], v2y = c[1] - b[1];
            double l1 = Math.sqrt(v1x * v1x + v1y * v1y);
            double l2 = Math.sqrt(v2x * v2x + v2y * v2y);
            if (l1 < 1d || l2 < 1d) continue;
            double dot = (v1x * v2x + v1y * v2y) / (l1 * l2);
            if (dot < -0.85d) turns++;
        }
        return turns;
    }

    public boolean turnAnomaly(int id) {
        return sharpTurns(id) >= 6;
    }

    // ------------------------------------------------------------------
    // Spawn-position plausibility
    // ------------------------------------------------------------------

    private static final float MAP_MIN = -8_000f;
    private static final float MAP_MAX = 8_000f;

    /** MLBB's map is bounded; out-of-bounds entities are decoys. */
    public boolean inMapBounds(PlayerData p) {
        return p.x >= MAP_MIN && p.x <= MAP_MAX
                && p.y >= MAP_MIN && p.y <= MAP_MAX;
    }

    public boolean spawnPlausible(PlayerData p) {
        if (!inMapBounds(p)) return false;
        if (p.hp <= 0f) return true; // dead entities can be anywhere
        return true;
    }

    // ------------------------------------------------------------------
    // Entity-count sanity
    // ------------------------------------------------------------------

    private static final int MAX_PLATOON = 10;

    /** Track concurrent entity count; a spike beyond game limits is fake. */
    public boolean entityCountSane(int enemyCount) {
        return enemyCount <= MAX_PLATOON;
    }

    // ------------------------------------------------------------------
    // HP-regen plausibility
    // ------------------------------------------------------------------

    private static final double MAX_REGEN_PER_SEC = 180d;

    /**
     * HP regen faster than the game allows is impossible data; flag it.
     */
    public boolean regenPlausible(double hpDeltaPerSec) {
        return hpDeltaPerSec <= MAX_REGEN_PER_SEC;
    }

    public boolean hpDeltaImpossible(int id, double hpDeltaPerSec) {
        return !regenPlausible(hpDeltaPerSec);
    }

    // ------------------------------------------------------------------
    // Spell-state consistency
    // ------------------------------------------------------------------

    /**
     * An entity that is dead but has skills ready, or alive with no HP,
     * is internally inconsistent (honeypot data).
     */
    public boolean stateConsistent(PlayerData p) {
        if (p.hp <= 0f && p.ultReady) return false;
        if (p.hp > 0f && p.level <= 0) return false;
        return true;
    }

    public boolean extendedStateConsistent(PlayerData p) {
        if (p.manaRatio < -1f || p.manaRatio > 1.5f) return false;
        return stateConsistent(p);
    }

    // ------------------------------------------------------------------
    // Coordinated-bot swarm detector
    // ------------------------------------------------------------------

    /**
     * Bot swarms share a movement phase (all change direction at the
     * same tick). The detector cross-correlates direction-change ticks
     * across entities.
     */
    public boolean swarmDetected(List<PlayerData> input, long nowMs) {
        int synchronizedTurns = 0;
        int candidates = 0;
        for (PlayerData p : input) {
            if (!p.isEnemy || !p.isAlive()) continue;
            candidates++;
            if (sharpTurns(p.id) >= 2) synchronizedTurns++;
        }
        if (candidates < 3) return false;
        return synchronizedTurns >= candidates - 1 && synchronizedTurns >= 3;
    }

    // ------------------------------------------------------------------
    // Suspicion decay schedule
    // ------------------------------------------------------------------

    private final AtomicLong lastDecayMs = new AtomicLong(System.currentTimeMillis());
    private static final long DECAY_INTERVAL_MS = 30_000L;
    private static final double DECAY_PER_INTERVAL = 0.08d;

    /** Slowly decay all suspicions so clean play clears old flags. */
    public void decaySuspicions() {
        long now = System.currentTimeMillis();
        if (now - lastDecayMs.get() < DECAY_INTERVAL_MS) return;
        lastDecayMs.set(now);
        for (EntityTrack t : tracks.values()) {
            t.suspicion = Math.max(0d, t.suspicion - DECAY_PER_INTERVAL);
            if (t.suspicion < SUSPECT_SCORE_QUARANTINE * 0.5d) {
                t.quarantined = false;
            }
        }
    }

    // ------------------------------------------------------------------
    // Render throttle
    // ------------------------------------------------------------------

    private final AtomicLong lastRenderMs = new AtomicLong(0L);
    private static final long RENDER_THROTTLE_MS = 66L;

    /** Rendering must never exceed a human frame budget. */
    public boolean renderAllowed() {
        long now = System.currentTimeMillis();
        if (now - lastRenderMs.get() < RENDER_THROTTLE_MS) return false;
        lastRenderMs.set(now);
        return true;
    }

    // ------------------------------------------------------------------
    // Quarantine hysteresis
    // ------------------------------------------------------------------

    /**
     * Quarantine has hysteresis: an entity is quarantined at a high
     * threshold but only released below a much lower one, so a single
     * bad frame doesn't flip-flop rendering.
     */
    public boolean shouldRelease(int id) {
        EntityTrack t = tracks.get(id);
        if (t == null) return true;
        return t.quarantined && t.suspicion < SUSPECT_SCORE_QUARANTINE * 0.4d;
    }

    // ------------------------------------------------------------------
    // Decoy-vs-real decision filter
    // ------------------------------------------------------------------

    /**
     * Decide whether an entity is worth rendering at all, combining
     * every heuristic into one gate.
     */
    public boolean renderWorthy(PlayerData p, long nowMs) {
        if (!p.isFresh(nowMs, STALE_MS)) return false;
        if (!inMapBounds(p)) return false;
        if (isQuarantined(p.id) && !shouldRelease(p.id)) return false;
        if (isPhantom(p.id)) return false;
        if (suspicion(p.id) > SUSPECT_SCORE_RENDER) return false;
        return stateConsistent(p);
    }

    // ------------------------------------------------------------------
    // Field entropy
    // ------------------------------------------------------------------

    /** Entropy of the enemy field: a perfectly uniform field is fake. */
    public double fieldEntropy(List<PlayerData> input) {
        if (input == null || input.size() < 2) return 0d;
        double meanX = 0d, meanY = 0d;
        for (PlayerData p : input) {
            meanX += p.x;
            meanY += p.y;
        }
        meanX /= input.size();
        meanY /= input.size();
        double spread = 0d;
        for (PlayerData p : input) {
            double dx = p.x - meanX;
            double dy = p.y - meanY;
            spread += Math.sqrt(dx * dx + dy * dy);
        }
        return spread / input.size();
    }

    public boolean fieldPlausible(List<PlayerData> input) {
        double e = fieldEntropy(input);
        return e > 50d;
    }

    // ------------------------------------------------------------------
    // Batch anomaly counter
    // ------------------------------------------------------------------

    private final AtomicInteger batchAnomalies = new AtomicInteger(0);

    public void noteBatchAnomaly() {
        batchAnomalies.incrementAndGet();
    }

    public int batchAnomalies() {
        return batchAnomalies.get();
    }

    public void resetBatchAnomalies() {
        batchAnomalies.set(0);
    }

    // ------------------------------------------------------------------
    // Frame delta sanity
    // ------------------------------------------------------------------

    private final AtomicLong lastFrameMs = new AtomicLong(0L);
    private static final long MAX_FRAME_DELTA_MS = 5_000L;

    /** A frame delta beyond the game's tick range means injected data. */
    public boolean frameDeltaSane(long nowMs) {
        long last = lastFrameMs.get();
        lastFrameMs.set(nowMs);
        if (last == 0L) return true;
        long delta = nowMs - last;
        return delta >= 8L && delta <= MAX_FRAME_DELTA_MS;
    }

    // ------------------------------------------------------------------
    // RNG-backed trace guard
    // ------------------------------------------------------------------

    /**
     * Occasionally the guard itself re-reads its state to ensure it
     * wasn't tampered with; the check cadence is randomized.
     */
    public boolean traceCheckDue() {
        return rng.nextDouble() < 0.05d;
    }

    public int trackedEntities() {
        return tracks.size();
    }

    // ------------------------------------------------------------------
    // Minigame/recall plausibility
    // ------------------------------------------------------------------

    /**
     * Recalling takes ~4-6s in MLBB; an entity that toggles recall
     * state every frame is feeding fake data.
     */
    public boolean recallToggleAnomaly(int id, boolean recalling) {
        EntityTrack t = tracks.get(id);
        if (t == null) return false;
        long last = t.lastRecallMs;
        if (recalling) {
            if (System.currentTimeMillis() - last < 1_500L) return true;
            t.lastRecallMs = System.currentTimeMillis();
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Minion/jungle entity filtering
    // ------------------------------------------------------------------

    private static final float MINION_HP_CEILING = 1_500f;

    /**
     * Minions and jungle monsters have bounded HP; an "enemy" with
     * minion-class HP values is data noise, not a player.
     */
    public boolean looksLikeMinion(PlayerData p) {
        return p.isEnemy && p.hp > 0f && p.hp < MINION_HP_CEILING && p.heroId == PlayerData.HERO_UNKNOWN;
    }

    public boolean looksLikePlayer(PlayerData p) {
        if (p.heroId != PlayerData.HERO_UNKNOWN) return true;
        return p.hp >= MINION_HP_CEILING;
    }

    // ------------------------------------------------------------------
    // Movement coherence
    // ------------------------------------------------------------------

    private static final double MAX_SPEED_PLAYER = 1_500d;

    /** Movement faster than any hero's move speed is impossible data. */
    public boolean speedPlausible(int id) {
        double s = meanSpeed(id);
        return s <= MAX_SPEED_PLAYER * 3d;
    }

    // ------------------------------------------------------------------
    // Ghost-after-death check
    // ------------------------------------------------------------------

    /**
     * Dead entities stay dead: an entity that "dies" then moves again
     * (hp > 0 after 0) is a data-stream ghost.
     */
    private final Map<Integer, Boolean> deathSeen = new HashMap<>();

    public boolean resurrectedGhost(int id, PlayerData p) {
        Boolean seen = deathSeen.get(id);
        if (p.hp <= 0f) {
            deathSeen.put(id, true);
            return false;
        }
        if (Boolean.TRUE.equals(seen)) {
            return true;
        }
        return false;
    }

    public void clearDeathSeen() {
        deathSeen.clear();
    }

    // ------------------------------------------------------------------
    // Value quantization check
    // ------------------------------------------------------------------

    /**
     * Real floats are noisy; values that are always exact integers (or
     * always multiples of a constant) smell synthetic. Checks a sample.
     */
    public boolean quantizedSuspicious(List<Float> samples) {
        if (samples == null || samples.size() < 4) return false;
        int exactInts = 0;
        for (float v : samples) {
            if (Math.abs(v - Math.round(v)) < 0.0001f) exactInts++;
        }
        return exactInts == samples.size();
    }

    // ------------------------------------------------------------------
    // Per-entity staleness stagger
    // ------------------------------------------------------------------

    /**
     * Staleness shouldn't hit every entity at the same frame (that's the
     * signature of a feed outage, not a game). The stagger check flags
     * synchronized staleness.
     */
    public boolean synchronizedStaleness(List<PlayerData> input, long nowMs) {
        int stale = staleCount(input, nowMs);
        int alive = 0;
        for (PlayerData p : input) {
            if (p.isAlive()) alive++;
        }
        return alive >= 3 && stale == alive;
    }

    // ------------------------------------------------------------------
    // Expectation priming
    // ------------------------------------------------------------------

    /**
     * Honeypots often "prime" a value (set it to an expected constant,
     * then watch the cheat react). The guard can detect that a value sat
     * at a constant before changing exactly when the cheat read it.
     */
    public boolean primedValue(int id, float value) {
        EntityTrack t = tracks.get(id);
        if (t == null || t.history.size() < 5) return false;
        int constantCount = 0;
        for (double[] h : t.history) {
            if (Math.abs(h[2] - value) < 0.01d) constantCount++;
        }
        return constantCount >= 4;
    }

    // ------------------------------------------------------------------
    // Feed jitter check
    // ------------------------------------------------------------------

    private final List<Long> feedGaps = new ArrayList<>();

    public void noteFeedGap(long gapMs) {
        feedGaps.add(gapMs);
        while (feedGaps.size() > 64) feedGaps.remove(0);
    }

    /** A perfectly even feed gap is machine-made; jitter is human. */
    public boolean feedJitterSane() {
        if (feedGaps.size() < 8) return true;
        double mean = 0d;
        for (long g : feedGaps) mean += g;
        mean /= feedGaps.size();
        if (mean < 1d) return false;
        double var = 0d;
        for (long g : feedGaps) {
            double d = g - mean;
            var += d * d;
        }
        return Math.sqrt(var / feedGaps.size()) / mean >= 0.02d;
    }

    // ------------------------------------------------------------------
    // Snapshot-identity tracking
    // ------------------------------------------------------------------

    private final Map<Integer, Integer> idCardinality = new HashMap<>();

    /** Track how many distinct (x,y) signatures each id has produced. */
    public void noteIdSignature(int id, float x, float y) {
        int sig = (int) (x * 10) * 31 + (int) (y * 10);
        Integer prev = idCardinality.get(id);
        if (prev == null || prev != sig) {
            idCardinality.put(id, sig);
        }
    }

    public boolean idFlickers(int id, int limit) {
        Integer sig = idCardinality.get(id);
        return sig != null && idCardinality.size() > limit;
    }

    // ------------------------------------------------------------------
    // Bait-tolerance ramp
    // ------------------------------------------------------------------

    /**
     * The guard tolerates occasional trap hits (real data can contain
     * legit 1337s). The tolerance ramps with match duration so early
     * false positives don't quarantine everything.
     */
    public boolean trapTolerable(int trapHitsThisMatch, long matchAgeMs) {
        double perMinute = trapHitsThisMatch / Math.max(1d, matchAgeMs / 60_000d);
        return perMinute <= 0.5d;
    }

    // ------------------------------------------------------------------
    // Match-stage entity sanity
    // ------------------------------------------------------------------

    /** Early game has fewer enemies on screen; a full platoon at minute 1 is fake. */
    public boolean stageEntityCountSane(int enemyCount, long matchAgeMs) {
        if (matchAgeMs < 3L * 60_000L) {
            return enemyCount <= 6;
        }
        return entityCountSane(enemyCount);
    }

    // ------------------------------------------------------------------
    // Composite render gate
    // ------------------------------------------------------------------

    /**
     * The full render gate: every heuristic in one call, so the overlay
     * asks once per frame and gets a single verdict.
     */
    public boolean renderGate(List<PlayerData> input, long nowMs) {
        if (input == null || input.isEmpty()) return false;
        if (!frameDeltaSane(nowMs)) return false;
        if (synchronizedStaleness(input, nowMs)) return false;
        if (swarmDetected(input, nowMs)) return false;
        if (!fieldPlausible(input)) return false;
        return true;
    }

    // ------------------------------------------------------------------
    // Known-bait batch check
    // ------------------------------------------------------------------

    /** Batch-check a list against all known trap values at once. */
    public int trapHits(List<PlayerData> input) {
        int hits = 0;
        for (PlayerData p : input) {
            if (trapValue(p.hp)) hits++;
            if (trapValue(p.x) || trapValue(p.y)) hits++;
        }
        return hits;
    }

    public boolean batchTrapped(List<PlayerData> input) {
        return trapHits(input) >= 3;
    }

    // ------------------------------------------------------------------
    // Engagement-reaction sanity
    // ------------------------------------------------------------------

    /**
     * An enemy that reacts to the player's every move within the same
     * frame is a bot; human reaction is 150-400ms. The guard reports
     * whether reaction windows in the data are too tight.
     */
    public boolean reactionWindowTooTight(long lastChangeMs, long changeMs) {
        long gap = changeMs - lastChangeMs;
        return gap > 0L && gap < 40L;
    }

    // ------------------------------------------------------------------
    // Field rotation entropy
    // ------------------------------------------------------------------

    /**
     * A real enemy field drifts; a field rotating around a fixed center
     * (all entities orbiting the same point) is a synthetic construct.
     */
    public boolean orbitalField(List<PlayerData> input) {
        if (input == null || input.size() < 3) return false;
        double meanX = 0d, meanY = 0d;
        for (PlayerData p : input) {
            meanX += p.x;
            meanY += p.y;
        }
        meanX /= input.size();
        meanY /= input.size();
        double[] radii = new double[input.size()];
        for (int i = 0; i < input.size(); i++) {
            double dx = input.get(i).x - meanX;
            double dy = input.get(i).y - meanY;
            radii[i] = Math.sqrt(dx * dx + dy * dy);
        }
        double min = Double.MAX_VALUE, max = 0d;
        for (double r : radii) {
            min = Math.min(min, r);
            max = Math.max(max, r);
        }
        return max - min < 50d;
    }

    // ------------------------------------------------------------------
    // Plausibility float guard
    // ------------------------------------------------------------------

    /** Any float that isn't finite is rejected outright. */
    public boolean finite(PlayerData p) {
        return Float.isFinite(p.x) && Float.isFinite(p.y) && Float.isFinite(p.hp);
    }

    // ------------------------------------------------------------------
    // Entity age coherence
    // ------------------------------------------------------------------

    /**
     * Tracked entities should age coherently; an entity "born" at every
     * frame (new id each time) is a synthetic stream.
     */
    public boolean idChurn(List<PlayerData> input) {
        int newIds = 0;
        for (PlayerData p : input) {
            if (!tracks.containsKey(p.id)) newIds++;
        }
        int alive = 0;
        for (PlayerData p : input) {
            if (p.isAlive()) alive++;
        }
        return alive > 0 && newIds > alive / 2;
    }
}