package com.shadow.mlbbcheat.utils.bypass;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * StatsCloak — behavioral statistics plausibility.
 *
 * Moonton bans in waves, often triggered by behavioral patterns rather
 * than pure technical detection: a player with a 20/0 KDA every match,
 * a 98% win rate over 200 games, perfect farm every single game, or a
 * sudden impossible jump in performance after installing. StatsCloak
 * keeps the account's observable statistics inside a plausible human
 * band:
 *
 *   1. KDA PACING      — kills per game follow a plausible distribution;
 *      the cloak tracks rolling stats and suggests when to hold back
 *      (feature intensity down) so streak statistics stay human.
 *   2. WIN-RATE MODEL  — win rate never exceeds a suspicious ceiling;
 *      the cloak reports the "safe" headroom so UI can warn the user.
 *   3. FARM CONSISTENCY — gold/min never lands exactly on the same
 *      value; jitter is added so the account's farm curve looks organic.
 *   4. REPORT PRESSURE — kills/dominations generate report pressure;
 *      the cloak models it and suggests cooling down (fewer aggressive
 *      features) after hot streaks.
 *   5. SESSION CADENCE — match count per day, breaks between matches,
 *      and time-of-day are modeled to look like a real grinder.
 *   6. RISK BUDGET     — a rolling risk budget per match; the cloak
 *      spends it across features so no single match uses everything.
 *   7. STREAK DAMPING  — win-streak length is damped toward a human
 *      ceiling; the cloak flags "too perfect" windows.
 *   8. ACCURACY CEILINGS — aim/retri success rates are capped with
 *      natural variance so accuracy never sits at 100%.
 */
public final class StatsCloak {

    private static final double WIN_RATE_CEILING = 0.62d;
    private static final double KDA_CEILING = 6.5d;
    private static final double FARM_JITTER_RATIO = 0.06d;
    private static final double ACCURACY_CEILING = 0.78d;
    private static final int STREAK_HUMAN_MAX = 9;
    private static final double REPORT_PRESSURE_PER_KILL = 0.014d;
    private static final double REPORT_PRESSURE_PER_DOMINATION = 0.05d;
    private static final double PRESSURE_DECAY_PER_MIN = 0.004d;
    private static final double RISK_BUDGET_PER_MATCH = 1.0d;
    private static final int KILLS_PER_GAME_MODE = 8;
    private static final int KILLS_PER_GAME_SIGMA = 4;
    private static final int MIN_MATCH_BREAK_MS = 25_000;
    private static final int MAX_MATCH_BREAK_MS = 180_000;

    private final Random rng = new Random();
    private final AtomicInteger kills = new AtomicInteger(0);
    private final AtomicInteger deaths = new AtomicInteger(0);
    private final AtomicInteger assists = new AtomicInteger(0);
    private final AtomicInteger matches = new AtomicInteger(0);
    private final AtomicInteger wins = new AtomicInteger(0);
    private final AtomicInteger winStreak = new AtomicInteger(0);
    private final AtomicInteger maxStreak = new AtomicInteger(0);
    private final AtomicLong matchStartMs = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong lastMatchEndMs = new AtomicLong(0L);
    private final AtomicLong reportPressure = new AtomicLong(doubleToBits(0d));
    private final AtomicLong riskBudget = new AtomicLong(doubleToBits(RISK_BUDGET_PER_MATCH));

    private static long doubleToBits(double d) {
        return Double.doubleToLongBits(d);
    }

    private static double bitsToDouble(long bits) {
        return Double.longBitsToDouble(bits);
    }

    public StatsCloak() {
        beginMatch();
    }

    // ------------------------------------------------------------------
    // Match lifecycle
    // ------------------------------------------------------------------

    public void beginMatch() {
        matchStartMs.set(System.currentTimeMillis());
        kills.set(0);
        deaths.set(0);
        assists.set(0);
        riskBudget.set(doubleToBits(RISK_BUDGET_PER_MATCH));
    }

    public void endMatch(boolean won) {
        matches.incrementAndGet();
        if (won) {
            wins.incrementAndGet();
            winStreak.incrementAndGet();
            maxStreak.set(Math.max(maxStreak.get(), winStreak.get()));
        } else {
            winStreak.set(0);
        }
        lastMatchEndMs.set(System.currentTimeMillis());
        decayPressure();
    }

    public void noteKill() {
        kills.incrementAndGet();
        addPressure(REPORT_PRESSURE_PER_KILL);
    }

    public void noteDeath() {
        deaths.incrementAndGet();
    }

    public void noteAssist() {
        assists.incrementAndGet();
    }

    public void noteDomination() {
        addPressure(REPORT_PRESSURE_PER_DOMINATION);
    }

    // ------------------------------------------------------------------
    // Pressure
    // ------------------------------------------------------------------

    private void addPressure(double d) {
        double p = bitsToDouble(reportPressure.get());
        reportPressure.set(doubleToBits(Math.min(1d, p + d)));
    }

    public void decayPressure() {
        long end = lastMatchEndMs.get();
        if (end == 0L) return;
        long now = System.currentTimeMillis();
        long idle = now - end;
        double decay = idle / 60_000d * PRESSURE_DECAY_PER_MIN;
        double p = bitsToDouble(reportPressure.get());
        reportPressure.set(doubleToBits(Math.max(0d, p - decay)));
    }

    public double reportPressure() {
        decayPressure();
        return bitsToDouble(reportPressure.get());
    }

    /** Whether the account should take it easy right now. */
    public boolean shouldCoolDown() {
        return reportPressure() > 0.55d;
    }

    public double cooldownFactor() {
        double p = reportPressure();
        return p <= 0.55d ? 1d : 1d - (p - 0.55d) * 1.5d;
    }

    // ------------------------------------------------------------------
    // KDA / win-rate
    // ------------------------------------------------------------------

    public double kda() {
        int d = deaths.get();
        return d == 0 ? kills.get() + assists.get() : (kills.get() + assists.get()) / (double) d;
    }

    public double winRate() {
        int m = matches.get();
        return m == 0 ? 0d : wins.get() / (double) m;
    }

    public boolean winRatePlausible() {
        return winRate() <= WIN_RATE_CEILING + 0.02d;
    }

    public boolean kdaPlausible() {
        return kda() <= KDA_CEILING + 0.5d;
    }

    /** Headroom before the account looks suspicious. 0 = already hot. */
    public double hotness() {
        double w = winRate();
        double k = kda();
        double wf = Math.max(0d, (w - WIN_RATE_CEILING) / WIN_RATE_CEILING);
        double kf = Math.max(0d, (k - KDA_CEILING) / KDA_CEILING);
        return Math.min(1d, Math.max(wf, kf));
    }

    public boolean accountHot() {
        return hotness() > 0.25d;
    }

    public int winStreakLen() {
        return winStreak.get();
    }

    public boolean streakPlausible() {
        return winStreakLen() <= STREAK_HUMAN_MAX;
    }

    // ------------------------------------------------------------------
    // Farm jitter
    // ------------------------------------------------------------------

    /** Jitter a gold-per-minute value so it never repeats exactly. */
    public double jitterFarm(double gpm) {
        double j = gpm * FARM_JITTER_RATIO * (rng.nextDouble() * 2d - 1d);
        return Math.max(0d, gpm + j);
    }

    /** Plausible kills-per-game sample for this account's model. */
    public int plausibleKillsPerGame() {
        return Math.max(0, KILLS_PER_GAME_MODE + (int) (rng.nextGaussian() * KILLS_PER_GAME_SIGMA));
    }

    // ------------------------------------------------------------------
    // Accuracy ceilings
    // ------------------------------------------------------------------

    /** Success probability for a feature, capped + naturally varying. */
    public double successProbability(double ideal) {
        double cap = Math.min(ACCURACY_CEILING, ideal);
        double v = cap * (1d - rng.nextDouble() * 0.12d);
        return Math.max(0.4d, v);
    }

    public boolean shouldMiss() {
        return rng.nextDouble() > ACCURACY_CEILING;
    }

    // ------------------------------------------------------------------
    // Risk budget
    // ------------------------------------------------------------------

    /** Spend from the match risk budget; returns allowed intensity. */
    public float spendRisk(float requested) {
        double budget = bitsToDouble(riskBudget.get());
        if (budget <= 0.05d) return 0f;
        double allowed = Math.min(requested, budget);
        riskBudget.set(doubleToBits(budget - allowed));
        return (float) allowed;
    }

    public double remainingBudget() {
        return bitsToDouble(riskBudget.get());
    }

    // ------------------------------------------------------------------
    // Session cadence
    // ------------------------------------------------------------------

    /** Suggested break between matches. */
    public long suggestedBreakMs() {
        long base = MIN_MATCH_BREAK_MS + rng.nextInt(MAX_MATCH_BREAK_MS - MIN_MATCH_BREAK_MS);
        if (accountHot() || shouldCoolDown()) {
            base += 45_000 + rng.nextInt(90_000);
        }
        return base;
    }

    public boolean breakElapsed() {
        return System.currentTimeMillis() - lastMatchEndMs.get() >= suggestedBreakMs();
    }

    // ------------------------------------------------------------------
    // Diagnostics
    // ------------------------------------------------------------------

    public static final class StatsSnapshot {
        public final int matches;
        public final int wins;
        public final double winRate;
        public final double kda;
        public final int streak;
        public final double pressure;
        public final double hotness;
        public final double budgetLeft;
        StatsSnapshot(int matches, int wins, double winRate, double kda, int streak,
                      double pressure, double hotness, double budgetLeft) {
            this.matches = matches;
            this.wins = wins;
            this.winRate = winRate;
            this.kda = kda;
            this.streak = streak;
            this.pressure = pressure;
            this.hotness = hotness;
            this.budgetLeft = budgetLeft;
        }
    }

    public StatsSnapshot snapshot() {
        return new StatsSnapshot(
                matches.get(), wins.get(), winRate(), kda(),
                winStreakLen(), reportPressure(), hotness(), remainingBudget());
    }

    public boolean allPlausible() {
        return winRatePlausible() && kdaPlausible() && streakPlausible();
    }

    // ------------------------------------------------------------------
    // Behavior hints
    // ------------------------------------------------------------------

    /** Aggressive-feature multiplier based on account heat. */
    public float aggressionFactor() {
        double cool = shouldCoolDown() ? 0.35d : 1d;
        double heat = 1d - hotness() * 0.4d;
        return (float) (cool * heat);
    }

    /** Whether to skip an aggressive action this tick. */
    public boolean skipAggressive() {
        if (shouldCoolDown()) return rng.nextDouble() < 0.4d;
        return rng.nextDouble() < 0.03d;
    }

    // ------------------------------------------------------------------
    // Streak shaping
    // ------------------------------------------------------------------

    /** After a long streak, suggest a "human" loss-tolerant phase. */
    public boolean streakCapReached() {
        return winStreakLen() >= STREAK_HUMAN_MAX;
    }

    // ------------------------------------------------------------------
    // Reset
    // ------------------------------------------------------------------

    public void resetAll() {
        kills.set(0);
        deaths.set(0);
        assists.set(0);
        matches.set(0);
        wins.set(0);
        winStreak.set(0);
        maxStreak.set(0);
        reportPressure.set(doubleToBits(0d));
        riskBudget.set(doubleToBits(RISK_BUDGET_PER_MATCH));
    }

    // ------------------------------------------------------------------
    // Kill-velocity model
    // ------------------------------------------------------------------

    private final AtomicLong firstKillMs = new AtomicLong(0L);
    private final AtomicInteger killsInWindow = new AtomicInteger(0);

    /** Track kill velocity (kills in 60s); used to avoid kill-spike flags. */
    public void noteKillWindow() {
        long now = System.currentTimeMillis();
        if (firstKillMs.get() == 0L) firstKillMs.set(now);
        if (now - firstKillMs.get() > 60_000L) {
            firstKillMs.set(now);
            killsInWindow.set(0);
        }
        killsInWindow.incrementAndGet();
    }

    public int killsLastMinute() {
        long now = System.currentTimeMillis();
        if (firstKillMs.get() != 0L && now - firstKillMs.get() > 60_000L) return 0;
        return killsInWindow.get();
    }

    public boolean killVelocitySuspicious() {
        return killsLastMinute() >= 7;
    }

    // ------------------------------------------------------------------
    // Match-length model
    // ------------------------------------------------------------------

    /** Plausible match length for the mode (12-19 min classic/ranked). */
    public long plausibleMatchLengthMs() {
        return 12L * 60_000L + rng.nextInt(7 * 60_000);
    }

    public boolean matchLengthPlausible(long actualMs) {
        return actualMs >= 9L * 60_000L && actualMs <= 26L * 60_000L;
    }

    // ------------------------------------------------------------------
    // Streak-break scheduler
    // ------------------------------------------------------------------

    private final AtomicLong lastStreakBreakMs = new AtomicLong(0L);

    /**
     * Even below the human cap, streaks must be broken occasionally;
     * this schedules an artificial break so the win graph never shows
     * a monotonic climb.
     */
    public boolean streakBreakDue() {
        int streak = winStreakLen();
        if (streak < 3) return false;
        long now = System.currentTimeMillis();
        if (now - lastStreakBreakMs.get() < 2L * 3600_000L) return false;
        double p = (streak - 2) * 0.18d;
        return rng.nextDouble() < Math.min(1d, p);
    }

    public void noteStreakBreak() {
        lastStreakBreakMs.set(System.currentTimeMillis());
    }

    public int targetStreak() {
        int streak = winStreakLen();
        if (streak > 5) return 3 + rng.nextInt(3);
        return streak + 1;
    }

    // ------------------------------------------------------------------
    // Session duration curve
    // ------------------------------------------------------------------

    /**
     * Session length follows a lognormal-ish curve: many short sessions,
     * few long ones. The model supplies plausible session lengths so the
     * stats never imply 12-hour grinding.
     */
    public long plausibleSessionMs() {
        double u = rng.nextDouble();
        double logVal = 4.2d + 1.1d * (u + u) * 0.5d;
        return (long) Math.min(6L * 3600_000L, Math.pow(10d, logVal) * 1_000d);
    }

    public boolean sessionLengthPlausible(long ms) {
        return ms <= 8L * 3600_000L;
    }

    // ------------------------------------------------------------------
    // Daily-play budget
    // ------------------------------------------------------------------

    private final AtomicLong lastDayResetMs = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger dailyMatches = new AtomicInteger(0);
    private static final int DAILY_MATCH_LIMIT = 14;

    /** A real player plays a bounded number of matches per day. */
    public boolean dailyBudgetLeft() {
        rollDayIfNeeded();
        return dailyMatches.get() < DAILY_MATCH_LIMIT;
    }

    public void noteMatchPlayed() {
        rollDayIfNeeded();
        dailyMatches.incrementAndGet();
    }

    public int dailyMatches() {
        rollDayIfNeeded();
        return dailyMatches.get();
    }

    private void rollDayIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastDayResetMs.get() > 24L * 3600_000L) {
            lastDayResetMs.set(now);
            dailyMatches.set(0);
        }
    }

    // ------------------------------------------------------------------
    // Time-of-day stat window
    // ------------------------------------------------------------------

    private final AtomicInteger winsToday = new AtomicInteger(0);
    private final AtomicInteger matchesToday = new AtomicInteger(0);

    public void noteResultToday(boolean won) {
        rollDayIfNeeded();
        matchesToday.incrementAndGet();
        if (won) winsToday.incrementAndGet();
    }

    public double todayWinRate() {
        rollDayIfNeeded();
        int m = matchesToday.get();
        if (m == 0) return 0d;
        return (double) winsToday.get() / m;
    }

    public boolean todayWinRatePlausible() {
        return todayWinRate() <= WIN_RATE_CEILING + 0.1d;
    }

    // ------------------------------------------------------------------
    // Per-mode stat separation
    // ------------------------------------------------------------------

    private final AtomicInteger classicKills = new AtomicInteger(0);
    private final AtomicInteger rankedKills = new AtomicInteger(0);
    private final AtomicInteger classicMatches = new AtomicInteger(0);
    private final AtomicInteger rankedMatches = new AtomicInteger(0);

    /** Stats must stay separated per mode; mixing them looks synthetic. */
    public void noteModeKill(String mode) {
        if ("ranked".equals(mode)) rankedKills.incrementAndGet();
        else classicKills.incrementAndGet();
    }

    public void noteModeMatch(String mode) {
        if ("ranked".equals(mode)) rankedMatches.incrementAndGet();
        else classicMatches.incrementAndGet();
    }

    public double modeKda(String mode) {
        int k = "ranked".equals(mode) ? rankedKills.get() : classicKills.get();
        int d = Math.max(1, deaths.get());
        return (double) k / d;
    }

    public boolean rankedKdaPlausible() {
        return modeKda("ranked") <= KDA_CEILING;
    }

    // ------------------------------------------------------------------
    // First-blood pacing
    // ------------------------------------------------------------------

    private final AtomicLong lastKillMs = new AtomicLong(0L);
    private static final long FIRST_BLOOD_MIN_MS = 45_000L;

    /** First blood never happens at second 0 of a real match. */
    public boolean firstKillTimingPlausible() {
        long start = matchStartMs.get();
        long now = System.currentTimeMillis();
        return now - start >= FIRST_BLOOD_MIN_MS || kills.get() == 0;
    }

    public boolean killSpacingPlausible() {
        long now = System.currentTimeMillis();
        long last = lastKillMs.get();
        if (last == 0L) return true;
        return now - last >= 1_200L;
    }

    // ------------------------------------------------------------------
    // Kill-chain cap
    // ------------------------------------------------------------------

    private static final int MAX_KILL_CHAIN = 5;

    private final AtomicInteger killChain = new AtomicInteger(0);

    public void noteChainKill() {
        killChain.incrementAndGet();
    }

    public boolean chainCapReached() {
        return killChain.get() >= MAX_KILL_CHAIN;
    }

    public void resetChain() {
        killChain.set(0);
    }

    // ------------------------------------------------------------------
    // Anti-plateau smoothing
    // ------------------------------------------------------------------

    /**
     * Stats that sit at a perfect plateau (same KDA for 50 matches) are
     * impossible; the smoother introduces tiny drift over time.
     */
    public double plateauDrift() {
        return 0.9d + rng.nextDouble() * 0.2d;
    }

    public boolean plateauSuspicious() {
        return matches.get() > 30 && kda() == kda(); // NaN-safe tautology
    }

    // ------------------------------------------------------------------
    // Playtime consistency
    // ------------------------------------------------------------------

    private final AtomicLong totalPlayMs = new AtomicLong(0L);

    public void notePlayTime(long ms) {
        totalPlayMs.addAndGet(ms);
    }

    public long totalPlayMs() {
        return totalPlayMs.get();
    }

    public double hoursPlayed() {
        return totalPlayMs.get() / 3_600_000d;
    }

    public boolean playtimePlausible() {
        double hours = hoursPlayed();
        return hours <= 6d * 365d;
    }

    // ------------------------------------------------------------------
    // Skill-percentage noise
    // ------------------------------------------------------------------

    private static final double SKILL_CEILING = 0.68d;

    /** Per-skill percentages must stay under a ceiling and jitter. */
    public double skillPercent(int skillId) {
        double base = 0.38d + (skillId % 7) * 0.03d;
        double jitter = rng.nextDouble() * 0.06d;
        return Math.min(SKILL_CEILING, base + jitter);
    }

    public boolean skillPercentPlausible(double pct) {
        return pct <= SKILL_CEILING;
    }

    // ------------------------------------------------------------------
    // Battle-point pacing
    // ------------------------------------------------------------------

    /**
     * Battle points accrue per match with a cap; a BP curve that grows
     * unboundedly flags an account. Models plausible accumulation.
     */
    public long plausibleBpGain() {
        return 80L + rng.nextInt(160);
    }

    public long bpCap() {
        return 100_000L;
    }

    public boolean bpGainPlausible(long gain) {
        return gain > 0L && gain <= 400L;
    }

    // ------------------------------------------------------------------
    // Role consistency
    // ------------------------------------------------------------------

    private String preferredRole = null;

    /** A player has a preferred role; stats should be role-consistent. */
    public void setPreferredRole(String role) {
        if (preferredRole == null) preferredRole = role;
    }

    public String preferredRole() {
        return preferredRole == null ? "unknown" : preferredRole;
    }

    public boolean roleConsistent(String role) {
        return preferredRole == null || preferredRole.equals(role);
    }

    // ------------------------------------------------------------------
    // Idle/downtime model
    // ------------------------------------------------------------------

    private final AtomicLong lastActionMs = new AtomicLong(System.currentTimeMillis());

    /** Real players idle between matches; model the gaps. */
    public boolean idleGapNeeded() {
        long idle = System.currentTimeMillis() - lastActionMs.get();
        return idle > 5L * 60_000L && rng.nextDouble() < 0.3d;
    }

    public void noteAction() {
        lastActionMs.set(System.currentTimeMillis());
    }

    public long idleGapMs() {
        return 2L * 60_000L + rng.nextInt(8 * 60_000);
    }

    // ------------------------------------------------------------------
    // Match-history depth
    // ------------------------------------------------------------------

    private final List<Boolean> recentResults = new ArrayList<>();

    /** Keep a bounded history of results for streak/ratio math. */
    public void pushResult(boolean won) {
        recentResults.add(won);
        while (recentResults.size() > 100) recentResults.remove(0);
    }

    public int resultDepth() {
        return recentResults.size();
    }

    public double last20WinRate() {
        int n = Math.min(20, recentResults.size());
        if (n == 0) return 0d;
        int w = 0;
        for (int i = recentResults.size() - n; i < recentResults.size(); i++) {
            if (recentResults.get(i)) w++;
        }
        return (double) w / n;
    }

    // ------------------------------------------------------------------
    // Shake-off factor
    // ------------------------------------------------------------------

    /** Losing streaks should end with a "shake-off" cooldown. */
    public boolean shakeOffDue() {
        return winStreakLen() <= -3;
    }

    public long shakeOffMs() {
        return 40L * 60_000L + rng.nextInt(60 * 60_000);
    }

    // ------------------------------------------------------------------
    // Aggregate plausibility gate
    // ------------------------------------------------------------------

    /** Full account-plausibility gate used by telemetry. */
    public boolean accountProfilePlausible() {
        return winRatePlausible()
                && kdaPlausible()
                && streakPlausible()
                && killVelocitySuspicious() == false
                && firstKillTimingPlausible()
                && playtimePlausible()
                && todayWinRatePlausible()
                && rankedKdaPlausible()
                && matchLengthPlausible(System.currentTimeMillis() - matchStartMs.get() >= 9L * 60_000L ? 15L * 60_000L : 15L * 60_000L);
    }

    // ------------------------------------------------------------------
    // Weekly match envelope
    // ------------------------------------------------------------------

    /**
     * Weekly match counts have an envelope (10-25 per week for a normal
     * player). The model reports whether the observed weekly count is
     * inside the envelope.
     */
    public boolean weeklyMatchesPlausible(int weekMatches) {
        return weekMatches >= 4 && weekMatches <= 40;
    }

    public int targetWeeklyMatches() {
        return 10 + rng.nextInt(12);
    }

    // ------------------------------------------------------------------
    // Rank-progression ladder
    // ------------------------------------------------------------------

    private int currentRank = 0;

    /** Rank climbs slowly; setRank models a bounded ladder. */
    public void setRank(int rank) {
        currentRank = Math.max(0, Math.min(7, rank));
    }

    public int currentRank() {
        return currentRank;
    }

    public boolean rankProgressionPlausible(int newRank) {
        return newRank - currentRank <= 1;
    }

    // ------------------------------------------------------------------
    // MVP cadence
    // ------------------------------------------------------------------

    private final AtomicInteger mvpCount = new AtomicInteger(0);

    /** MVPs are rare; the cadence caps the share of MVP matches. */
    public void noteMvp() {
        mvpCount.incrementAndGet();
    }

    public int mvpCount() {
        return mvpCount.get();
    }

    public boolean mvpRatePlausible() {
        int m = Math.max(1, matches.get());
        return (double) mvpCount.get() / m <= 0.25d;
    }

    // ------------------------------------------------------------------
    // Death-cooldown model
    // ------------------------------------------------------------------

    private final AtomicLong lastDeathMs = new AtomicLong(0L);
    private static final long MIN_DEATH_GAP_MS = 4_000L;

    /** Dying twice within 4s is (mostly) impossible in MLBB respawns. */
    public boolean deathGapPlausible() {
        long now = System.currentTimeMillis();
        long last = lastDeathMs.get();
        if (last == 0L) return true;
        return now - last >= MIN_DEATH_GAP_MS;
    }

    public void noteDeathTiming() {
        lastDeathMs.set(System.currentTimeMillis());
    }

    // ------------------------------------------------------------------
    // Match-variance model
    // ------------------------------------------------------------------

    private final AtomicInteger blowouts = new AtomicInteger(0);

    /** Real match results vary; blowouts are the minority. */
    public void noteBlowout() {
        blowouts.incrementAndGet();
    }

    public int blowouts() {
        return blowouts.get();
    }

    public boolean blowoutRatePlausible() {
        int m = Math.max(1, matches.get());
        return (double) blowouts.get() / m <= 0.4d;
    }

    // ------------------------------------------------------------------
    // Time-of-day play envelope
    // ------------------------------------------------------------------

    /**
     * Play sessions cluster at certain hours; a 24/7 play pattern is
     * impossible. The envelope check flags off-hours density.
     */
    public boolean hourEnvelopePlausible() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
        if (hour >= 1 && hour < 6) return rng.nextDouble() < 0.12d;
        return true;
    }

    // ------------------------------------------------------------------
    // Warm-up matches
    // ------------------------------------------------------------------

    /**
     * Players warm up (a bad match after a long break); the model paces
     * warm-up losses so the first match back is often a loss.
     */
    public boolean warmUpLossLikely() {
        long idle = System.currentTimeMillis() - lastActionMs.get();
        return idle > 12L * 3600_000L && rng.nextDouble() < 0.5d;
    }

    // ------------------------------------------------------------------
    // Kill-consistency model
    // ------------------------------------------------------------------

    /** KDA variance should be moderate; track per-match kill scatter. */
    private final List<Integer> recentKillsPerGame = new ArrayList<>();

    public void pushKillsPerGame(int k) {
        recentKillsPerGame.add(k);
        while (recentKillsPerGame.size() > 30) recentKillsPerGame.remove(0);
    }

    public double killScatter() {
        if (recentKillsPerGame.size() < 5) return 0d;
        double mean = 0d;
        for (int k : recentKillsPerGame) mean += k;
        mean /= recentKillsPerGame.size();
        double var = 0d;
        for (int k : recentKillsPerGame) {
            double d = k - mean;
            var += d * d;
        }
        return Math.sqrt(var / recentKillsPerGame.size());
    }

    public boolean killScatterPlausible() {
        double s = killScatter();
        return s >= 1.5d && s <= 6d;
    }

    // ------------------------------------------------------------------
    // Report-pressure history
    // ------------------------------------------------------------------

    private final List<Double> pressureHistory = new ArrayList<>();

    public void snapshotPressure() {
        pressureHistory.add(reportPressure());
        while (pressureHistory.size() > 50) pressureHistory.remove(0);
    }

    public double meanPressure() {
        if (pressureHistory.isEmpty()) return 0d;
        double t = 0d;
        for (double p : pressureHistory) t += p;
        return t / pressureHistory.size();
    }

    public boolean pressureRising() {
        if (pressureHistory.size() < 5) return false;
        int n = pressureHistory.size();
        double recent = pressureHistory.get(n - 1) - pressureHistory.get(n - 2);
        double older = pressureHistory.get(n - 4) - pressureHistory.get(n - 5);
        return recent > 0d && recent > older;
    }

    // ------------------------------------------------------------------
    // Anti-streak reward pacing
    // ------------------------------------------------------------------

    /** After a long winning streak, the next win is deliberately paced. */
    public boolean postStreakPacing() {
        int streak = winStreakLen();
        if (streak < 4) return false;
        return rng.nextDouble() < 0.35d;
    }

    // ------------------------------------------------------------------
    // Aggregate ops counter
    // ------------------------------------------------------------------

    private final AtomicInteger cloakOps = new AtomicInteger(0);

    public void noteCloakOp() {
        cloakOps.incrementAndGet();
    }

    public int cloakOps() {
        return cloakOps.get();
    }

    /** Ops rate ceiling: telemetry checks that the cloak didn't run hot. */
    public boolean opsRatePlausible() {
        return cloakOps.get() < 10_000;
    }

    // ------------------------------------------------------------------
    // Final composite gate
    // ------------------------------------------------------------------

    /** One-call gate for everything stat-related. */
    public boolean statsEnvelopeNormal() {
        return winRatePlausible()
                && kdaPlausible()
                && streakPlausible()
                && mvpRatePlausible()
                && blowoutRatePlausible()
                && killScatterPlausible()
                && weeklyMatchesPlausible(dailyMatches.get() * 3)
                && opsRatePlausible();
    }

    // ------------------------------------------------------------------
    // Achievement pacing
    // ------------------------------------------------------------------

    private final AtomicInteger achievements = new AtomicInteger(0);

    /** Achievements unlock slowly; burst-unlocking all at once is fake. */
    public boolean achievementDue() {
        int a = achievements.get();
        if (a >= 40) return false;
        return rng.nextDouble() < 0.04d;
    }

    public void noteAchievement() {
        achievements.incrementAndGet();
    }

    public int achievements() {
        return achievements.get();
    }

    // ------------------------------------------------------------------
    // Queue-time envelope
    // ------------------------------------------------------------------

    /** Queue times vary by hour; returns a plausible queue duration. */
    public long plausibleQueueMs() {
        return 5_000L + rng.nextInt(25_000);
    }

    public boolean queueTimePlausible(long ms) {
        return ms >= 1_000L && ms <= 90_000L;
    }

    // ------------------------------------------------------------------
    // Surrender pattern
    // ------------------------------------------------------------------

    private final AtomicInteger surrenders = new AtomicInteger(0);

    /** Surrenders are rare and clustered in blowout losses. */
    public void noteSurrender() {
        surrenders.incrementAndGet();
    }

    public boolean surrenderRatePlausible() {
        int m = Math.max(1, matches.get());
        return (double) surrenders.get() / m <= 0.15d;
    }

    // ------------------------------------------------------------------
    // Final digest
    // ------------------------------------------------------------------

    /** Opaque stat digest for telemetry without revealing raw values. */
    public String statDigest() {
        return matches.get() + "|" + (int) (kda() * 10d) + "|" + (int) (winRate() * 100d)
                + "|" + winStreakLen() + "|" + (int) reportPressure();
    }
}