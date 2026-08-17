package com.shadow.mlbbcheat.utils.bypass;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * NetworkShield — traffic obfuscation and heartbeat discipline.
 *
 * The control-channel traffic (heartbeat, key validation) is a persistent
 * signature an analyst (or MLBB's network heuristics) could correlate with
 * the cheat. NetworkShield makes the app's network behavior look like any
 * mundane app: irregular heartbeats, padded payloads, bursty reconnects,
 * and defensive defaults against traffic inspection:
 *
 *   1. HEARTBEAT DISCIPLINE  — heartbeats fire on a jittered schedule
 *      (base 15-45s plus random drift), never on a fixed period. Backoff
 *      on failure is randomized and capped so retry storms never appear.
 *   2. PAYLOAD PADDING       — every request is padded to a random size
 *      class (filler bytes) so packet lengths don't fingerprint the API.
 *      Padding is removed after decryption on the server.
 *   3. BURST SMOOTHING       — key validation + heartbeat + config fetch
 *      are never sent back-to-back; minimum inter-request gaps enforced
 *      with randomized spacing.
 *   4. TIMESTAMP ANTI-PATTERN — client-supplied timestamps are jittered
 *      so the server cannot derive a perfectly regular client clock.
 *   5. TRAFFIC SHAPING       — the request stream follows a human-ish
 *      cadence: small bursts after UI actions, long silences otherwise.
 *   6. DNS-PIN HINTS         — records resolved hostnames so subsequent
 *      requests reuse them (no repeated DNS lookups = visible pattern).
 *   7. HEADER NORMALIZATION  — synthetic Accept-Language / User-Agent
 *      strings so the traffic doesn't carry the default Java/OkHttp
 *      fingerprint.
 *   8. TLS-FRIENDLY SIZING   — payloads are sized to common TLS record
 *      boundaries when possible (avoiding distinctive partial records).
 *   9. KILL-SWITCH REACTIVITY — the app never acknowledges a kill-switch
 *      synchronously; it drains the current session for a randomized
 *      window so a sniffed "instant disconnect on kill" pattern is
 *      avoided.
 *  10. SESSION TOKEN ROTATION — heartbeat session ids rotate so long
 *      term correlations across sessions are harder.
 */
public final class NetworkShield {

    private static final long HEARTBEAT_BASE_MS = 20_000L;
    private static final long HEARTBEAT_JITTER_MS = 25_000L;
    private static final long MIN_REQUEST_GAP_MS = 700L;
    private static final long MAX_REQUEST_GAP_MS = 4200L;
    private static final long BACKOFF_MIN_MS = 4000L;
    private static final long BACKOFF_MAX_MS = 90_000L;
    private static final long KILL_DRAIN_MIN_MS = 1500L;
    private static final long KILL_DRAIN_MAX_MS = 9000L;
    private static final int PAD_CLASSES = 5;
    private static final int PAD_BASE = 64;
    private static final int TLS_RECORD = 16384;
    private static final int HISTORY_MAX = 48;
    private static final int MAX_BURST = 4;

    private final Random rng = new Random();
    private final AtomicLong nextHeartbeatMs = new AtomicLong(0L);
    private final AtomicLong lastRequestMs = new AtomicLong(0L);
    private final AtomicLong lastFailureMs = new AtomicLong(0L);
    private final AtomicInteger backoffAttempts = new AtomicInteger(0);
    private final AtomicLong sessionSeed = new AtomicLong(newSessionSeed());
    private final ConcurrentLinkedQueue<Long> requestHistory = new ConcurrentLinkedQueue<>();
    private final List<String> dnsCache = new ArrayList<>();
    private final AtomicInteger burstCount = new AtomicInteger(0);
    private final AtomicLong burstResetMs = new AtomicLong(System.currentTimeMillis());

    private volatile boolean killDraining = false;
    private volatile long killDrainUntilMs = 0L;

    private static long newSessionSeed() {
        return System.nanoTime() ^ (System.currentTimeMillis() << 20);
    }

    // ------------------------------------------------------------------
    // Heartbeat schedule
    // ------------------------------------------------------------------

    /** Whether a heartbeat may fire right now. */
    public boolean heartbeatDue() {
        long now = System.currentTimeMillis();
        if (killDraining) return false;
        if (now < nextHeartbeatMs.get()) return false;
        scheduleNextHeartbeat();
        return true;
    }

    public void scheduleNextHeartbeat() {
        long now = System.currentTimeMillis();
        long jitter = (long) (rng.nextDouble() * HEARTBEAT_JITTER_MS);
        nextHeartbeatMs.set(now + HEARTBEAT_BASE_MS + jitter);
    }

    /** Millis until the next permitted heartbeat. */
    public long untilNextHeartbeatMs() {
        return Math.max(0L, nextHeartbeatMs.get() - System.currentTimeMillis());
    }

    // ------------------------------------------------------------------
    // Inter-request discipline
    // ------------------------------------------------------------------

    /** Whether a new request may start now (burst smoothing). */
    public boolean requestAllowed() {
        long now = System.currentTimeMillis();
        if (now - burstResetMs.get() > 10_000L) {
            burstResetMs.set(now);
            burstCount.set(0);
        }
        if (burstCount.get() >= MAX_BURST) return false;
        long gap = now - lastRequestMs.get();
        if (gap < MIN_REQUEST_GAP_MS) return false;
        burstCount.incrementAndGet();
        lastRequestMs.set(now);
        requestHistory.add(now);
        while (requestHistory.size() > HISTORY_MAX) requestHistory.poll();
        return true;
    }

    /** Wait time to enforce minimum spacing before the next request. */
    public long requestWaitMs() {
        long now = System.currentTimeMillis();
        long gap = now - lastRequestMs.get();
        if (gap >= MIN_REQUEST_GAP_MS) return 0L;
        return MIN_REQUEST_GAP_MS - gap;
    }

    /** Randomized wait the caller should insert (human-ish pacing). */
    public long pacingWaitMs() {
        return MIN_REQUEST_GAP_MS + rng.nextInt((int) (MAX_REQUEST_GAP_MS - MIN_REQUEST_GAP_MS));
    }

    // ------------------------------------------------------------------
    // Backoff
    // ------------------------------------------------------------------

    /** Wait after a failed request, with randomized exponential backoff. */
    public long backoffWaitMs() {
        int attempt = backoffAttempts.incrementAndGet();
        long base = Math.min(BACKOFF_MAX_MS, BACKOFF_MIN_MS * (1L << Math.min(4, attempt - 1)));
        long jitter = (long) (rng.nextDouble() * base * 0.4d);
        long wait = base + jitter;
        lastFailureMs.set(System.currentTimeMillis());
        return wait;
    }

    public void noteSuccess() {
        backoffAttempts.set(0);
    }

    public int backoffAttempts() {
        return backoffAttempts.get();
    }

    public boolean inBackoff() {
        return System.currentTimeMillis() - lastFailureMs.get() < backoffWaitMs();
    }

    // ------------------------------------------------------------------
    // Padding
    // ------------------------------------------------------------------

    /**
     * Pad a plaintext body so its ciphertext lands on a plausible size
     * class. Padding is appended to the JSON as filler whitespace-safe
     * field? No — appended as a base64 "pad" field the server ignores.
     */
    public byte[] padPayload(byte[] plain) {
        int target = padTargetSize(plain.length);
        int padLen = Math.max(0, target - plain.length);
        if (padLen <= 0) return plain;
        byte[] padded = new byte[plain.length + padLen];
        System.arraycopy(plain, 0, padded, 0, plain.length);
        for (int i = plain.length; i < padded.length; i++) {
            padded[i] = (byte) ('0' + rng.nextInt(10));
        }
        return padded;
    }

    /** Choose a target size class close to the actual size. */
    public int padTargetSize(int size) {
        int target = PAD_BASE;
        while (target < size) target <<= 1;
        int choice = rng.nextInt(PAD_CLASSES);
        int classes = target / PAD_CLASSES;
        int adj = target - classes * rng.nextInt(PAD_CLASSES);
        return Math.max(size, Math.min(adj + classes * choice, target));
    }

    /** TLS-record alignment hint (informational for socket config). */
    public int tlsRecordAligned(int size) {
        int full = (size / TLS_RECORD) * TLS_RECORD;
        int remainder = size % TLS_RECORD;
        if (remainder > TLS_RECORD - 64) return full + TLS_RECORD;
        return size;
    }

    // ------------------------------------------------------------------
    // Kill-switch drain
    // ------------------------------------------------------------------

    /** Call when a kill-switch signal arrives. */
    public void enterKillDrain() {
        long drain = KILL_DRAIN_MIN_MS + (long) (rng.nextDouble()
                * (KILL_DRAIN_MAX_MS - KILL_DRAIN_MIN_MS));
        killDrainUntilMs = System.currentTimeMillis() + drain;
        killDraining = true;
    }

    public boolean killDrainComplete() {
        if (!killDraining) return false;
        if (System.currentTimeMillis() >= killDrainUntilMs) {
            killDraining = false;
            return true;
        }
        return false;
    }

    public long remainingKillDrainMs() {
        return Math.max(0L, killDrainUntilMs - System.currentTimeMillis());
    }

    // ------------------------------------------------------------------
    // Timestamp anti-pattern
    // ------------------------------------------------------------------

    /** Jittered client timestamp (never two identical deltas). */
    public long jitteredTimestamp(long realNow) {
        return realNow - (long) (rng.nextDouble() * 800d);
    }

    // ------------------------------------------------------------------
    // Traffic shaping
    // ------------------------------------------------------------------

    /** Suggested number of network operations for this window. */
    public int shapedOpsThisWindow() {
        long now = System.currentTimeMillis();
        if (now - burstResetMs.get() > 10_000L) {
            burstResetMs.set(now);
            burstCount.set(0);
        }
        if (burstCount.get() >= MAX_BURST) return 0;
        return MAX_BURST - burstCount.get();
    }

    public boolean shouldSilence() {
        long now = System.currentTimeMillis();
        int phase = (int) (now / 1000L) % 11;
        return phase == 7;
    }

    // ------------------------------------------------------------------
    // DNS pin hints
    // ------------------------------------------------------------------

    public void noteResolved(String host) {
        if (dnsCache.contains(host)) return;
        if (dnsCache.size() >= 8) dnsCache.remove(0);
        dnsCache.add(host);
    }

    public List<String> pinnedHosts() {
        return new ArrayList<>(dnsCache);
    }

    // ------------------------------------------------------------------
    // Header normalization
    // ------------------------------------------------------------------

    private static final String[] LANG_HINTS = {
            "en-US,en;q=0.9", "en-GB,en;q=0.8", "id-ID,id;q=0.9,en;q=0.8",
            "th-TH,th;q=0.9,en;q=0.8", "vi-VN,vi;q=0.9,en;q=0.8",
            "ms-MY,ms;q=0.9,en;q=0.8", "fil-PH,fil;q=0.9,en;q=0.8"
    };

    private static final String[] UA_HINTS = {
            "Mozilla/5.0 (Linux; Android 13; SM-A536E Build/TP1A.220624.014; wv) AppleWebKit/537.36",
            "Mozilla/5.0 (Linux; Android 12; V2134 Build/SP1A.210812.003; wv) AppleWebKit/537.36",
            "Mozilla/5.0 (Linux; Android 14; 23127PN0CC Build/UKQ1.230917.001; wv) AppleWebKit/537.36",
            "Mozilla/5.0 (Linux; Android 13; RMX3562 Build/TP1A.220905.001; wv) AppleWebKit/537.36"
    };

    public String syntheticAcceptLanguage() {
        return LANG_HINTS[rng.nextInt(LANG_HINTS.length)];
    }

    public String syntheticUserAgent() {
        return UA_HINTS[rng.nextInt(UA_HINTS.length)];
    }

    // ------------------------------------------------------------------
    // Session rotation
    // ------------------------------------------------------------------

    public String sessionToken() {
        long seed = sessionSeed.get();
        return Long.toHexString(seed) + "-" + Long.toHexString(seed >> 16);
    }

    public void rotateSession() {
        sessionSeed.set(newSessionSeed());
    }

    // ------------------------------------------------------------------
    // Diagnostics
    // ------------------------------------------------------------------

    public static final class ShieldStats {
        public final long untilNextHeartbeatMs;
        public final int backoffAttempts;
        public final int recentRequests;
        public final boolean killDraining;
        public final long killDrainMs;
        public final String session;
        ShieldStats(long untilNextHeartbeatMs, int backoffAttempts, int recentRequests,
                    boolean killDraining, long killDrainMs, String session) {
            this.untilNextHeartbeatMs = untilNextHeartbeatMs;
            this.backoffAttempts = backoffAttempts;
            this.recentRequests = recentRequests;
            this.killDraining = killDraining;
            this.killDrainMs = killDrainMs;
            this.session = session;
        }
    }

    public ShieldStats stats() {
        long now = System.currentTimeMillis();
        int recent = 0;
        for (Long t : requestHistory) {
            if (now - t < 60_000L) recent++;
        }
        return new ShieldStats(
                untilNextHeartbeatMs(),
                backoffAttempts(),
                recent,
                killDraining,
                remainingKillDrainMs(),
                sessionToken());
    }

    // ------------------------------------------------------------------
    // Encrypted payload hygiene
    // ------------------------------------------------------------------

    /** Ensure the envelope length never leaks the plaintext size exactly. */
    public int envelopePadding(int encryptedLen) {
        int target = padTargetSize(encryptedLen);
        return Math.max(0, target - encryptedLen);
    }

    public byte[] appendEnvelopePad(byte[] envelope, int pad) {
        if (pad <= 0) return envelope;
        ByteArrayOutputStream out = new ByteArrayOutputStream(envelope.length + pad);
        out.write(envelope, 0, envelope.length);
        byte[] filler = new byte[pad];
        rng.nextBytes(filler);
        out.write(filler, 0, pad);
        return out.toByteArray();
    }

    // ------------------------------------------------------------------
    // Connection hygiene
    // ------------------------------------------------------------------

    private final AtomicLong lastConnectMs = new AtomicLong(0L);

    public boolean connectAllowed() {
        long now = System.currentTimeMillis();
        long gap = now - lastConnectMs.get();
        if (gap < 500L) return false;
        lastConnectMs.set(now);
        return true;
    }

    public long connectWaitMs() {
        long gap = System.currentTimeMillis() - lastConnectMs.get();
        return Math.max(0L, 500L - gap);
    }

    // ------------------------------------------------------------------
    // Per-endpoint pacing tables
    // ------------------------------------------------------------------

    private static final long ENDPOINT_BASE_GAP_MS = 14_000L;
    private final Map<String, Long> endpointLastCall = new HashMap<>();
    private final Map<String, Integer> endpointBackoff = new HashMap<>();

    /** Pace requests per endpoint: each endpoint has its own cadence. */
    public boolean endpointAllowed(String endpoint) {
        long now = System.currentTimeMillis();
        Long last = endpointLastCall.get(endpoint);
        if (last == null) {
            endpointLastCall.put(endpoint, now);
            return true;
        }
        int backoff = endpointBackoff.getOrDefault(endpoint, 0);
        long minGap = ENDPOINT_BASE_GAP_MS + backoff * 6_000L + rng.nextInt(4_000);
        if (now - last < minGap) return false;
        endpointLastCall.put(endpoint, now);
        return true;
    }

    public void noteEndpointFailure(String endpoint) {
        int b = endpointBackoff.getOrDefault(endpoint, 0);
        endpointBackoff.put(endpoint, Math.min(6, b + 1));
    }

    public void noteEndpointSuccess(String endpoint) {
        endpointBackoff.put(endpoint, 0);
    }

    public int endpointBackoffLevel(String endpoint) {
        return endpointBackoff.getOrDefault(endpoint, 0);
    }

    // ------------------------------------------------------------------
    // Retry ladder with capped jitter
    // ------------------------------------------------------------------

    private static final long[] RETRY_LADDER_MS = {1_000L, 2_500L, 5_000L, 10_000L, 20_000L};

    /** Retry delay for attempt n, jittered but never above the cap. */
    public long retryDelayMs(int attempt) {
        int idx = Math.min(RETRY_LADDER_MS.length - 1, Math.max(0, attempt));
        long base = RETRY_LADDER_MS[idx];
        long jitter = rng.nextInt(1 + (int) (base / 3L));
        return base + jitter;
    }

    public boolean retryExhausted(int attempt) {
        return attempt >= RETRY_LADDER_MS.length * 2;
    }

    // ------------------------------------------------------------------
    // Request envelope obfuscation
    // ------------------------------------------------------------------

    private static final int ENVELOPE_NOISE_BYTES = 16;

    /** Prepend a random noise block so envelopes are never byte-identical. */
    public byte[] obfuscateEnvelope(byte[] envelope) {
        if (envelope == null || envelope.length == 0) return envelope;
        ByteArrayOutputStream out = new ByteArrayOutputStream(envelope.length + ENVELOPE_NOISE_BYTES);
        byte[] noise = new byte[ENVELOPE_NOISE_BYTES];
        rng.nextBytes(noise);
        out.write(noise, 0, noise.length);
        out.write(envelope, 0, envelope.length);
        return out.toByteArray();
    }

    public int noiseBlockLen() {
        return ENVELOPE_NOISE_BYTES;
    }

    // ------------------------------------------------------------------
    // Request interleave planner
    // ------------------------------------------------------------------

    /**
     * Interleave request types (heartbeat, config, ping) so the same
     * endpoint isn't hit in a steady rhythm. Returns the next request
     * type given the previous ones.
     */
    public int nextRequestType(int lastType) {
        double r = rng.nextDouble();
        if (r < 0.45d) return 0; // heartbeat
        if (r < 0.75d) return 1; // config
        return 2;                 // ping/other
    }

    public boolean typeVaried(int a, int b, int c) {
        return a != b || b != c;
    }

    // ------------------------------------------------------------------
    // Quiet-window scheduler
    // ------------------------------------------------------------------

    private static final int QUIET_HOUR_START = 2;
    private static final int QUIET_HOUR_END = 5;

    /** Deep-night hours get a much slower heartbeat cadence. */
    public boolean quietWindow() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
        return hour >= QUIET_HOUR_START && hour < QUIET_HOUR_END;
    }

    public long quietWindowHeartbeatMs() {
        return 120_000L + rng.nextInt(90_000);
    }

    public long heartbeatForNow() {
        if (quietWindow()) return quietWindowHeartbeatMs();
        return 30_000L + rng.nextInt(35_000);
    }

    // ------------------------------------------------------------------
    // Packet-size histogram shaping
    // ------------------------------------------------------------------

    private final List<Integer> packetSizes = new ArrayList<>();

    /** Record a packet size; the shield keeps a bounded histogram. */
    public void notePacketSize(int size) {
        packetSizes.add(size);
        while (packetSizes.size() > 512) packetSizes.remove(0);
    }

    public double meanPacketSize() {
        if (packetSizes.isEmpty()) return 0d;
        long sum = 0L;
        for (int s : packetSizes) sum += s;
        return sum / (double) packetSizes.size();
    }

    public boolean sizesVaried() {
        if (packetSizes.size() < 8) return true;
        int distinct = new HashSet<>(packetSizes).size();
        return distinct >= 3;
    }

    // ------------------------------------------------------------------
    // Connection reuse budget
    // ------------------------------------------------------------------

    private final AtomicInteger connsThisWindow = new AtomicInteger(0);
    private final AtomicLong connWindowMs = new AtomicLong(System.currentTimeMillis());
    private static final int MAX_CONNS_PER_WINDOW = 4;
    private static final long CONN_WINDOW_MS = 60_000L;

    public boolean connBudgetAvailable() {
        long now = System.currentTimeMillis();
        if (now - connWindowMs.get() > CONN_WINDOW_MS) {
            connWindowMs.set(now);
            connsThisWindow.set(0);
        }
        return connsThisWindow.get() < MAX_CONNS_PER_WINDOW;
    }

    public void noteConn() {
        connsThisWindow.incrementAndGet();
    }

    public int connsThisWindow() {
        return connsThisWindow.get();
    }

    // ------------------------------------------------------------------
    // Header rotation pools
    // ------------------------------------------------------------------

    private static final String[] ACCEPT_LANGS = {
            "en-US,en;q=0.9", "en-GB,en;q=0.8,es;q=0.5", "en-US,en;q=0.8,fil;q=0.6",
            "en;q=0.9,id;q=0.7", "en-US,en;q=0.9,vi;q=0.6", "en;q=0.8,ms;q=0.7"
    };

    private int headerCursor = rng.nextInt(ACCEPT_LANGS.length);

    /** Rotate Accept-Language so the request profile isn't static. */
    public String rotatedAcceptLanguage() {
        headerCursor = (headerCursor + 1) % ACCEPT_LANGS.length;
        return ACCEPT_LANGS[headerCursor];
    }

    public String rotatedUserAgentVariant() {
        int variant = rng.nextInt(3);
        String base = syntheticUserAgent();
        if (variant == 0) return base + "; Mobile";
        if (variant == 1) return base;
        return base + "; Mobile Safari";
    }

    // ------------------------------------------------------------------
    // Latency self-test
    // ------------------------------------------------------------------

    /**
     * Self-test of round-trip variance: a client with machine-perfect
     * latency is a fingerprint. The model reports whether measured
     * latency is plausibly human (variable, not flat).
     */
    public double latencyVariance(List<Long> samples) {
        if (samples == null || samples.size() < 2) return 0d;
        double mean = 0d;
        for (long s : samples) mean += s;
        mean /= samples.size();
        double var = 0d;
        for (long s : samples) {
            double d = s - mean;
            var += d * d;
        }
        return Math.sqrt(var / (samples.size() - 1));
    }

    public boolean latencyPlausible(List<Long> samples) {
        return latencyVariance(samples) >= 2d;
    }

    // ------------------------------------------------------------------
    // Keep-alive stagger
    // ------------------------------------------------------------------

    /**
     * Keep-alives should stagger around the idle timeout, never at exact
     * multiples of it. Returns the next keep-alive delay.
     */
    public long nextKeepAliveMs() {
        return 25_000L + rng.nextInt(20_000);
    }

    public boolean keepAliveDue(long lastKeepAliveMs) {
        return System.currentTimeMillis() - lastKeepAliveMs >= nextKeepAliveMs();
    }

    // ------------------------------------------------------------------
    // DNS-cache-mimic resolver notes
    // ------------------------------------------------------------------

    private final Set<String> pinnedDns = new HashSet<>();

    /**
     * A real client caches DNS; the shield tracks which hosts have been
     * "resolved" so lookups happen once per session, not every request.
     */
    public boolean shouldResolve(String host) {
        if (pinnedDns.contains(host)) return false;
        pinnedDns.add(host);
        return true;
    }

    public int pinnedCount() {
        return pinnedDns.size();
    }

    // ------------------------------------------------------------------
    // Heartbeat drift model
    // ------------------------------------------------------------------

    /**
     * Heartbeats drift over a session: slightly longer after long idle,
     * slightly shorter right after activity. Models the drift so the
     * inter-heartbeat gap sequence looks alive.
     */
    public long driftedHeartbeatGap(long sinceLast) {
        if (sinceLast > 5L * 60_000L) {
            return 45_000L + rng.nextInt(40_000);
        }
        return heartbeatForNow() + rng.nextInt(12_000);
    }

    // ------------------------------------------------------------------
    // Session metadata rotation
    // ------------------------------------------------------------------

    private final AtomicLong lastMetaRotationMs = new AtomicLong(0L);

    /** Session metadata should rotate occasionally, not per request. */
    public boolean metadataRotateDue() {
        long now = System.currentTimeMillis();
        if (now - lastMetaRotationMs.get() < 10L * 60_000L) return false;
        lastMetaRotationMs.set(now);
        return true;
    }

    public void rotateMetadata() {
        rotateSession();
    }

    // ------------------------------------------------------------------
    // Failure-recovery pacing
    // ------------------------------------------------------------------

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    public void noteFailure() {
        lastFailureMs.set(System.currentTimeMillis());
        consecutiveFailures.incrementAndGet();
    }

    public void noteAnySuccess() {
        consecutiveFailures.set(0);
    }

    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }

    public boolean failureStorm() {
        return consecutiveFailures.get() >= 5;
    }

    public long failureStormHoldMs() {
        return 60_000L + rng.nextInt(60_000);
    }

    // ------------------------------------------------------------------
    // Request size guards
    // ------------------------------------------------------------------

    private static final int MIN_PAYLOAD_BYTES = 64;
    private static final int MAX_PAYLOAD_BYTES = 16 * 1024;

    /** A payload outside the sane envelope range is a red flag; reject it. */
    public boolean payloadSizeSane(int size) {
        return size >= MIN_PAYLOAD_BYTES && size <= MAX_PAYLOAD_BYTES;
    }

    public int clampPayload(int size) {
        return Math.max(MIN_PAYLOAD_BYTES, Math.min(MAX_PAYLOAD_BYTES, size));
    }

    // ------------------------------------------------------------------
    // Time-source coherence
    // ------------------------------------------------------------------

    /**
     * All timestamps should derive from one jittered source so a server
     * clock-skew check sees a coherent client. Reports the last applied
     * jitter so callers stay consistent.
     */
    private long lastJitterUsed = 0L;

    public long coherentTimestamp(long realNow) {
        lastJitterUsed = jitteredTimestamp(realNow);
        return lastJitterUsed;
    }

    public long lastJitterUsedMs() {
        return lastJitterUsed;
    }

    // ------------------------------------------------------------------
    // Quiet-after-activity shaping
    // ------------------------------------------------------------------

    private final AtomicLong lastActivityMs = new AtomicLong(0L);

    public void noteActivity() {
        lastActivityMs.set(System.currentTimeMillis());
    }

    /** Right after user-visible activity, traffic pauses briefly. */
    public boolean postActivityQuiet() {
        long quiet = System.currentTimeMillis() - lastActivityMs.get();
        return quiet > 0L && quiet < 8_000L;
    }

    public long postActivityDelayMs() {
        return 1_500L + rng.nextInt(5_000);
    }

    // ------------------------------------------------------------------
    // Request-sequence de-dup
    // ------------------------------------------------------------------

    private long lastRequestSignature = 0L;

    /** Two identical requests back-to-back look scripted; block them. */
    public boolean requestSignatureUnique(long signature) {
        if (signature == lastRequestSignature) return false;
        lastRequestSignature = signature;
        return true;
    }

    // ------------------------------------------------------------------
    // Session-length model
    // ------------------------------------------------------------------

    private final AtomicLong sessionStartMs = new AtomicLong(System.currentTimeMillis());

    public void noteSessionStartNow() {
        sessionStartMs.set(System.currentTimeMillis());
    }

    public long sessionAgeMs() {
        return System.currentTimeMillis() - sessionStartMs.get();
    }

    public boolean sessionLongRunning() {
        return sessionAgeMs() > 12L * 3600_000L;
    }

    // ------------------------------------------------------------------
    // Cadence anomaly tracker
    // ------------------------------------------------------------------

    private final List<Long> cadenceGaps = new ArrayList<>();

    /** Record the last heartbeat gap for cadence analysis. */
    public void noteCadenceGap(long gapMs) {
        cadenceGaps.add(gapMs);
        while (cadenceGaps.size() > 96) cadenceGaps.remove(0);
    }

    public double meanCadenceGapMs() {
        if (cadenceGaps.isEmpty()) return 0d;
        long sum = 0L;
        for (long g : cadenceGaps) sum += g;
        return sum / (double) cadenceGaps.size();
    }

    /** Perfectly periodic cadence (zero variance) is a fingerprint. */
    public boolean cadenceFlat() {
        if (cadenceGaps.size() < 6) return false;
        double mean = meanCadenceGapMs();
        if (mean < 1d) return false;
        double var = 0d;
        for (long g : cadenceGaps) {
            double d = g - mean;
            var += d * d;
        }
        return Math.sqrt(var / cadenceGaps.size()) / mean < 0.05d;
    }

    // ------------------------------------------------------------------
    // Peak-hour envelope
    // ------------------------------------------------------------------

    /**
     * Evening = busy hours: more traffic is natural. The envelope scales
     * heartbeat rate by time of day, matching a real user's usage.
     */
    public double hourEnvelope() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
        if (hour >= 19 && hour < 23) return 1.0d;
        if (hour >= 12 && hour < 14) return 0.85d;
        if (hour >= 6 && hour < 12) return 0.7d;
        if (hour >= 23 || hour < 2) return 0.55d;
        return 0.4d; // deep night
    }

    public long envelopedHeartbeatMs() {
        return (long) (heartbeatForNow() / Math.max(0.35d, hourEnvelope()));
    }

    // ------------------------------------------------------------------
    // Burst avoidance
    // ------------------------------------------------------------------

    private final AtomicInteger recentOps = new AtomicInteger(0);
    private final AtomicLong opsResetMs = new AtomicLong(System.currentTimeMillis());

    public boolean opAllowed() {
        long now = System.currentTimeMillis();
        if (now - opsResetMs.get() > 15_000L) {
            opsResetMs.set(now);
            recentOps.set(0);
        }
        if (recentOps.get() >= 3) return false;
        recentOps.incrementAndGet();
        return true;
    }

    public int opsInWindow() {
        long now = System.currentTimeMillis();
        if (now - opsResetMs.get() > 15_000L) {
            opsResetMs.set(now);
            recentOps.set(0);
        }
        return recentOps.get();
    }

    // ------------------------------------------------------------------
    // Self-check invariants
    // ------------------------------------------------------------------

    /** Structural sanity of the shield state. */
    public boolean invariantsHold() {
        if (requestHistory == null || pinnedHosts() == null) return false;
        if (sessionToken() == null || sessionToken().isEmpty()) return false;
        return true;
    }

    // ------------------------------------------------------------------
    // Header variability checker
    // ------------------------------------------------------------------

    /**
     * Repeated requests with byte-identical headers are a fingerprint.
     * The checker tracks header hashes and reports when the stream is
     * too uniform.
     */
    private final List<Integer> headerHashes = new ArrayList<>();

    public void noteHeaderHash(int hash) {
        headerHashes.add(hash);
        while (headerHashes.size() > 48) headerHashes.remove(0);
    }

    public boolean headersVaried() {
        if (headerHashes.size() < 6) return true;
        return new HashSet<>(headerHashes).size() >= 3;
    }

    // ------------------------------------------------------------------
    // Slow-start pacing
    // ------------------------------------------------------------------

    /**
     * Real clients ramp up after a reconnect (slow start). The shield
     * suggests throttled pacing right after any failure/reconnect.
     */
    public boolean slowStartActive() {
        return consecutiveFailures() > 0;
    }

    public long slowStartGapMs() {
        int f = consecutiveFailures();
        return 40_000L + f * 15_000L + rng.nextInt(20_000);
    }

    // ------------------------------------------------------------------
    // Off-hour drain
    // ------------------------------------------------------------------

    /** When the device is likely idle, drain the network quietly. */
    public boolean idleDrainDue() {
        return hourEnvelope() < 0.5d && rng.nextDouble() < 0.3d;
    }

    // ------------------------------------------------------------------
    // Consecutive-same-type guard
    // ------------------------------------------------------------------

    private int lastRequestType = -1;

    /** The same request type three times in a row looks scripted. */
    public boolean typeRepeatAllowed(int type) {
        if (type == lastRequestType) return false;
        lastRequestType = type;
        return true;
    }

    public void resetTypeGuard() {
        lastRequestType = -1;
    }

    // ------------------------------------------------------------------
    // Heartbeat signature mixing
    // ------------------------------------------------------------------

    /**
     * Heartbeats should carry varying payload bits so server-side
     * dedup can't flag "same payload every N minutes".
     */
    public byte[] mixHeartbeatSalt(byte[] payload) {
        if (payload == null || payload.length < 4) return payload;
        byte[] copy = payload.clone();
        int salt = rng.nextInt();
        copy[0] ^= (byte) (salt & 0xFF);
        copy[1] ^= (byte) ((salt >>> 8) & 0xFF);
        return copy;
    }

    // ------------------------------------------------------------------
    // Delayed-ack model
    // ------------------------------------------------------------------

    private final AtomicLong lastAckMs = new AtomicLong(0L);

    public void noteAck() {
        lastAckMs.set(System.currentTimeMillis());
    }

    public boolean ackDue() {
        return System.currentTimeMillis() - lastAckMs.get() > 45_000L;
    }

    public long ackJitterMs() {
        return 200L + rng.nextInt(800);
    }

    // ------------------------------------------------------------------
    // Session-op budget
    // ------------------------------------------------------------------

    /**
     * Each session gets a finite network-op budget so the traffic curve
     * decays over long sessions instead of staying constant (a constant
     * rate over 8 hours is unnatural).
     */
    private static final int SESSION_OP_BUDGET = 600;
    private final AtomicInteger sessionOps = new AtomicInteger(0);

    public boolean sessionOpAllowed() {
        return sessionOps.get() < SESSION_OP_BUDGET;
    }

    public void noteSessionOp() {
        sessionOps.incrementAndGet();
    }

    public int sessionOpsUsed() {
        return sessionOps.get();
    }

    public int sessionOpsLeft() {
        return Math.max(0, SESSION_OP_BUDGET - sessionOps.get());
    }

    // ------------------------------------------------------------------
    // Echo-delay model
    // ------------------------------------------------------------------

    /** Round-trip echo delay with natural variance (for pings). */
    public long echoDelayMs() {
        return 40L + rng.nextInt(180);
    }

    public boolean echoDelayed(List<Long> rttSamples) {
        return latencyVariance(rttSamples) >= 2d;
    }

    // ------------------------------------------------------------------
    // Trailing-noise markers
    // ------------------------------------------------------------------

    /**
     * End-of-request trailing noise (junk bytes) varies per request so
     * length fingerprinting can't pin down the protocol exactly.
     */
    public int trailingNoiseLen(int base) {
        return base + rng.nextInt(32);
    }

    public byte[] appendTrailingNoise(byte[] envelope, int len) {
        if (len <= 0) return envelope;
        ByteArrayOutputStream out = new ByteArrayOutputStream(envelope.length + len);
        out.write(envelope, 0, envelope.length);
        byte[] noise = new byte[len];
        rng.nextBytes(noise);
        out.write(noise, 0, len);
        return out.toByteArray();
    }
}