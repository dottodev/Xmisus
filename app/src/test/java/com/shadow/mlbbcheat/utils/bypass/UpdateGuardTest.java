package com.shadow.mlbbcheat.utils.bypass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.shadow.mlbbcheat.memory.GameOffsets;
import com.shadow.mlbbcheat.memory.OffsetRepository;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class UpdateGuardTest {

    private Context ctx() {
        return RuntimeEnvironment.getApplication();
    }

    private UpdateGuard guard() {
        OffsetRepository offsets = new OffsetRepository(ctx());
        return new UpdateGuard(ctx(), offsets);
    }

    @Test
    public void freshGuard_isNotSuspended() {
        UpdateGuard g = guard();
        assertFalse(g.suspended());
        assertFalse(g.watchOnly());
        assertFalse(g.crashLoop());
        assertFalse(g.reducedMode());
    }

    @Test
    public void firstVersionCheck_initializes() {
        UpdateGuard g = guard();
        assertFalse(g.versionChanged());
        assertFalse(g.versionChanged());
    }

    @Test
    public void placeholderOffsets_areStructurallyValid() {
        UpdateGuard g = guard();
        assertTrue(g.offsetsValid(GameOffsets.getPlaceholder()));
    }

    @Test
    public void zeroedAssetOffsets_areRejected() {
        // The bundled seed DB contains all-zero offsets (placeholder) —
        // structurally invalid, so the guard refuses to trust them.
        UpdateGuard g = guard();
        assertFalse(g.activeOffsetsValid());
    }

    @Test
    public void nullOffsets_invalid() {
        UpdateGuard g = guard();
        assertFalse(g.offsetsValid(null));
    }

    @Test
    public void badRemotePayload_rejected() {
        UpdateGuard g = guard();
        assertFalse(g.tryRemoteUpdate(null));
        assertFalse(g.tryRemoteUpdate(""));
        assertFalse(g.tryRemoteUpdate("{not json"));
        assertFalse(g.tryRemoteUpdate("{\"versions\":[{\"version\":\"v1\"}]}"));
    }

    @Test
    public void rollback_withoutLastGood_fails() {
        UpdateGuard g = guard();
        assertFalse(g.hasLastGood());
        assertFalse(g.rollbackToLastGood());
    }

    @Test
    public void staleness_risesAfterPatch() {
        UpdateGuard g = guard();
        g.notePatch(System.currentTimeMillis() - 100L * 3600_000L);
        assertTrue(g.stalenessRisk() > 0d);
        assertTrue(g.intensityFactor() < 1f);
        assertTrue(g.intensityFactor() >= 0.15f);
    }

    @Test
    public void checkDelays_areJitteredAndPositive() {
        UpdateGuard g = guard();
        assertTrue(g.nextCheckDelayMs() > 0L);
        assertFalse(g.checkDue(System.currentTimeMillis()));
        assertTrue(g.checkDue(System.currentTimeMillis() - 10 * 60_000L));
    }

    @Test
    public void crashLoop_afterAbnormalExits() {
        UpdateGuard g = guard();
        for (int i = 0; i < 3; i++) g.noteAbnormalExit();
        assertTrue(g.crashLoop());
        assertTrue(g.reducedMode());
        g.noteCleanStart();
    }

    @Test
    public void stats_arePresent() {
        UpdateGuard g = guard();
        UpdateGuard.GuardStats st = g.stats();
        assertNotNull(st);
        assertNotNull(st.detectedVersion);
        assertFalse(st.suspended);
    }

    @Test
    public void suspendRemaining_zeroWhenNotSuspended() {
        UpdateGuard g = guard();
        assertEquals(0L, g.suspendRemainingMs());
    }

    @Test
    public void telemetryGate_alwaysCloses() {
        UpdateGuard g = guard();
        assertFalse(g.telemetryGate());
    }
}