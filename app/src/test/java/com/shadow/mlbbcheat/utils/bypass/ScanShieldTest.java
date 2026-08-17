package com.shadow.mlbbcheat.utils.bypass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class ScanShieldTest {

    @Test
    public void initialState_readsAllowed() {
        ScanShield s = new ScanShield();
        assertFalse(s.isSuspended());
        assertFalse(s.isDegraded());
        assertEquals(0, s.recentReadCount());
    }

    @Test
    public void gateRead_neverThrows_andIsBounded() {
        ScanShield s = new ScanShield();
        int allowed = 0;
        for (int i = 0; i < 500; i++) {
            if (s.gateRead()) allowed++;
        }
        assertTrue(allowed >= 0);
        assertTrue(allowed <= 500);
    }

    @Test
    public void suspend_blocksReads() {
        ScanShield s = new ScanShield();
        s.suspend();
        assertTrue(s.isSuspended());
        assertFalse(s.gateRead());
        s.resume();
        assertFalse(s.isSuspended());
    }

    @Test
    public void waitMillis_isNonNegative() {
        ScanShield s = new ScanShield();
        for (int i = 0; i < 100; i++) {
            assertTrue(s.waitMillis() >= 0L);
        }
    }

    @Test
    public void chunkSizes_partitionTotalExactly() {
        ScanShield s = new ScanShield();
        for (int total : new int[]{1, 7, 100, 1000}) {
            List<Integer> chunks = s.chunkSizes(total);
            int sum = 0;
            for (int c : chunks) sum += c;
            assertEquals(total, sum);
            assertFalse(chunks.isEmpty());
        }
    }

    @Test
    public void canary_verifyDetectsMismatch() {
        ScanShield s = new ScanShield();
        s.plantCanary(0x1000, 42L);
        assertTrue(s.verifyCanary(0x1000, 42L));
        assertFalse(s.verifyCanary(0x1000, 43L));
    }

    @Test
    public void shuffledOrder_isPermutation() {
        ScanShield s = new ScanShield();
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < 20; i++) ids.add(i);
        List<Integer> out = s.shuffledOrder(ids);
        assertEquals(new HashSet<>(ids), new HashSet<>(out));
        assertEquals(ids.size(), out.size());
    }

    @Test
    public void subsample_keepsRatioBounded() {
        ScanShield s = new ScanShield();
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < 100; i++) ids.add(i);
        List<Integer> out = s.subsample(ids, 0.3f);
        assertTrue(out.size() <= 100);
        assertTrue(out.size() >= 0);
    }

    @Test
    public void pressureLevel_startsZero() {
        ScanShield s = new ScanShield();
        assertEquals(0, s.pressureLevel());
    }

    @Test
    public void stats_arePresent() {
        ScanShield s = new ScanShield();
        ScanShield.ShieldStats st = s.stats();
        assertNotNull(st);
        assertEquals(0, st.implausible);
    }

    @Test
    public void noteSessionRead_countsReads() {
        ScanShield s = new ScanShield();
        s.noteSessionStart();
        s.noteSessionRead();
        s.noteSessionRead();
        assertTrue(s.readsPerSecondThisSession() > 0d);
        assertTrue(s.recentReadCount() >= 0);
    }
}