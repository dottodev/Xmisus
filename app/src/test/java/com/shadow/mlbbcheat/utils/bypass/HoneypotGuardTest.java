package com.shadow.mlbbcheat.utils.bypass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.shadow.mlbbcheat.models.PlayerData;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class HoneypotGuardTest {

    @Test
    public void teleportingEntity_isFlagged() {
        HoneypotGuard g = new HoneypotGuard();
        long now = 1_000_000L;
        g.observe(new PlayerData(1, true, 100f, 100f, 500f), now);
        g.observe(new PlayerData(1, true, 100f, 100f, 500f), now + 100);
        g.observe(new PlayerData(1, true, 100f, 100f, 500f), now + 200);
        // huge jump in one tick = teleport
        g.observe(new PlayerData(1, true, 5000f, 5000f, 500f), now + 300);
        assertTrue(g.isTeleporting(1));
        assertTrue(g.suspicion(1) > 0d);
    }

    @Test
    public void stablePhantom_isDetected() {
        HoneypotGuard g = new HoneypotGuard();
        long now = System.currentTimeMillis();
        // >12 samples, >4s old, near-zero movement = phantom
        for (int i = 0; i < 16; i++) {
            g.observe(new PlayerData(1, true, 100f + i * 0.01f, 100f, 500f), now + i * 1000L);
        }
        assertTrue(g.isPhantom(1));
    }

    @Test
    public void movingEntity_staysClean() {
        HoneypotGuard g = new HoneypotGuard();
        long now = 1_000_000L;
        for (int i = 0; i < 16; i++) {
            g.observe(new PlayerData(1, true, 100f + i * 50f, 100f, 500f), now + i * 1000L);
            g.observe(new PlayerData(2, true, 400f - i * 30f, 100f, 800f), now + i * 1000L);
        }
        assertFalse(g.isPhantom(1));
        assertFalse(g.isTeleporting(1));
        assertEquals(0, g.quarantinedCount());
    }

    @Test
    public void deadEntity_suspicionDecays() {
        HoneypotGuard g = new HoneypotGuard();
        long now = 1_000_000L;
        g.observe(new PlayerData(1, true, 100f, 100f, 500f), now);
        g.observe(new PlayerData(1, true, 5000f, 5000f, 500f), now + 100);
        double before = g.suspicion(1);
        g.observe(new PlayerData(1, true, 5000f, 5000f, 0f), now + 200);
        assertTrue(g.suspicion(1) <= before);
    }

    @Test
    public void renderable_filtersQuarantined() {
        HoneypotGuard g = new HoneypotGuard();
        long now = 1_000_000L;
        // Entity 1: teleports repeatedly → quarantined
        for (int i = 0; i < 8; i++) {
            g.observe(new PlayerData(1, true, 100f, 100f, 500f), now + i * 100L);
            g.observe(new PlayerData(1, true, 9000f, 9000f, 500f), now + i * 100L + 50);
        }
        List<PlayerData> input = new ArrayList<>();
        input.add(new PlayerData(1, true, 9000f, 9000f, 500f));
        input.add(new PlayerData(2, true, 300f, 300f, 800f));
        List<PlayerData> out = g.renderable(input, now + 10_000L);
        assertEquals(1, out.size());
        assertEquals(2, out.get(0).id);
    }

    @Test
    public void trapValue_flagsImpossibleNumbers() {
        HoneypotGuard g = new HoneypotGuard();
        assertTrue(g.trapValue(Float.NaN));
        assertTrue(g.trapValue(Float.POSITIVE_INFINITY));
        assertFalse(g.trapValue(42f));
    }

    @Test
    public void reportWave_tracksAndExpires() {
        HoneypotGuard g = new HoneypotGuard();
        assertFalse(g.reportWaveActive());
        for (int i = 0; i < 5; i++) g.noteReportEvent();
        assertTrue(g.reportWave() >= 5);
    }

    @Test
    public void staleCount_detectsFrozenStream() {
        HoneypotGuard g = new HoneypotGuard();
        long now = System.currentTimeMillis();
        for (int i = 0; i < 10; i++) {
            g.observe(new PlayerData(1, true, 100f, 100f, 500f), now + i * 500L);
        }
        // no observations for 120s → the entity is stale
        List<PlayerData> input = new ArrayList<>();
        input.add(new PlayerData(1, true, 100f, 100f, 500f));
        int stale = g.staleCount(input, now + 120_000L);
        assertEquals(1, stale);
    }

    @Test
    public void stats_arePresent() {
        HoneypotGuard g = new HoneypotGuard();
        HoneypotGuard.GuardStats st = g.stats();
        assertNotNull(st);
        assertEquals(0, st.quarantined);
    }

    @Test
    public void verdict_any_afterSuspiciousStream() {
        HoneypotGuard g = new HoneypotGuard();
        long now = 1_000_000L;
        for (int i = 0; i < 8; i++) {
            g.observe(new PlayerData(1, true, 100f, 100f, 500f), now + i * 100L);
            g.observe(new PlayerData(1, true, 9500f, 9500f, 500f), now + i * 100L + 40);
        }
        List<PlayerData> input = new ArrayList<>();
        input.add(new PlayerData(1, true, 9500f, 9500f, 500f));
        assertTrue(g.verdict(input, now + 10_000L).any());
    }
}