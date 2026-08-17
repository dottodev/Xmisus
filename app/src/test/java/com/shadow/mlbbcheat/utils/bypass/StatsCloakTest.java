package com.shadow.mlbbcheat.utils.bypass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StatsCloakTest {

    @Test
    public void freshCloak_startsClean() {
        StatsCloak c = new StatsCloak();
        assertEquals(0, c.snapshot().matches);
        assertEquals(0d, c.winRate(), 0.0001);
        assertEquals(0d, c.reportPressure(), 0.0001);
        assertTrue(c.allPlausible());
    }

    @Test
    public void killsAndDeaths_affectKda() {
        StatsCloak c = new StatsCloak();
        c.noteKill();
        c.noteKill();
        c.noteKill();
        c.noteDeath();
        c.noteAssist();
        assertEquals(4d, c.kda(), 0.0001);
    }

    @Test
    public void winRate_tracksMatches() {
        StatsCloak c = new StatsCloak();
        c.endMatch(true);
        c.endMatch(true);
        c.endMatch(false);
        assertEquals(3, c.snapshot().matches);
        assertEquals(2d / 3d, c.winRate(), 0.0001);
        assertEquals(0, c.winStreakLen());
    }

    @Test
    public void longStreak_flagsHotness() {
        StatsCloak c = new StatsCloak();
        for (int i = 0; i < 10; i++) c.endMatch(true);
        assertTrue(c.accountHot());
        assertFalse(c.streakPlausible());
    }

    @Test
    public void kills_raiseReportPressure() {
        StatsCloak c = new StatsCloak();
        for (int i = 0; i < 45; i++) c.noteKill();
        assertTrue(c.reportPressure() > 0.5d);
        assertTrue(c.shouldCoolDown() || c.cooldownFactor() < 1d);
    }

    @Test
    public void resetAll_clearsEverything() {
        StatsCloak c = new StatsCloak();
        c.noteKill();
        c.endMatch(true);
        c.resetAll();
        assertTrue(c.allPlausible());
        assertEquals(0, c.snapshot().matches);
        assertEquals(0d, c.reportPressure(), 0.0001);
    }

    @Test
    public void jitterFarm_neverReturnsNegative() {
        StatsCloak c = new StatsCloak();
        for (int i = 0; i < 200; i++) {
            assertTrue(c.jitterFarm(1500f) >= 0d);
        }
    }

    @Test
    public void successProbability_cappedAtCeiling() {
        StatsCloak c = new StatsCloak();
        for (int i = 0; i < 200; i++) {
            assertTrue(c.successProbability(1d) <= 0.80d);
            assertTrue(c.successProbability(0.5d) >= 0.4d);
        }
    }

    @Test
    public void riskBudget_isSpentAndExhausted() {
        StatsCloak c = new StatsCloak();
        c.beginMatch();
        float first = c.spendRisk(1f);
        assertTrue(first > 0f);
        float second = c.spendRisk(1f);
        assertTrue(second >= 0f);
        float third = c.spendRisk(1f);
        assertTrue(third >= 0f);
        assertTrue(c.remainingBudget() >= 0d);
    }

    @Test
    public void killWindow_tracksVelocity() {
        StatsCloak c = new StatsCloak();
        for (int i = 0; i < 7; i++) c.noteKillWindow();
        assertTrue(c.killVelocitySuspicious());
    }

    @Test
    public void matchLengthModel_isPlausible() {
        StatsCloak c = new StatsCloak();
        long len = c.plausibleMatchLengthMs();
        assertTrue(c.matchLengthPlausible(len));
        assertFalse(c.matchLengthPlausible(2 * 60_000L));
    }

    @Test
    public void breaks_areHumanSized() {
        StatsCloak c = new StatsCloak();
        long br = c.suggestedBreakMs();
        assertTrue(br >= 20_000L);
        assertTrue(br <= 4 * 60_000L);
    }

    @Test
    public void aggression_neverNegative() {
        StatsCloak c = new StatsCloak();
        for (int i = 0; i < 50; i++) {
            assertTrue(c.aggressionFactor() >= 0f);
            assertTrue(c.aggressionFactor() <= 1.1f);
        }
    }

    @Test
    public void hotAccount_coolsDown() {
        StatsCloak c = new StatsCloak();
        for (int i = 0; i < 8; i++) c.endMatch(true);
        assertTrue(c.aggressionFactor() < 1f);
    }
}