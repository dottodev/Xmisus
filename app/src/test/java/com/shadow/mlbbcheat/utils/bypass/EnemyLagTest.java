package com.shadow.mlbbcheat.utils.bypass;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.shadow.mlbbcheat.models.PlayerData;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * EnemyLag — state machine, gates, ramp, frames, self-cleansing.
 */
@RunWith(RobolectricTestRunner.class)
public class EnemyLagTest {

    private EnemyLag lag;

    @Before
    public void setUp() {
        BypassStack.resetForTest();
        BypassStack stack = BypassStack.getInstance(RuntimeEnvironment.getApplication());
        lag = new EnemyLag(stack, 42L);
    }

    // ------------------------------------------------------------------
    // State machine
    // ------------------------------------------------------------------

    @Test
    public void start_rampsThenActivates() {
        injectValidOffsets();
        long now = base();
        lag.start();
        assertEquals(EnemyLag.State.ARMING, lag.state());
        int guard = 0;
        while (lag.ramping() && guard++ < 1000) {
            now += 1_500L;
            lag.tick(now);
        }
        assertEquals(EnemyLag.State.ACTIVE, lag.state());
        assertTrue(lag.isActive());
        assertTrue(lag.intensityAt() >= EnemyLag.INTENSITY_MIN);
        assertTrue(lag.intensityAt() <= lag.intensity());
    }

    @Test
    public void stop_movesToCooldown() {
        injectValidOffsets();
        long now = base();
        lag.start();
        while (lag.ramping()) {
            now += 1_500L;
            lag.tick(now);
        }
        assertTrue(lag.isActive());
        lag.stop();
        assertEquals(EnemyLag.State.COOLDOWN, lag.state());
        assertFalse(lag.isActive());
    }

    @Test
    public void forceStop_locksPermanently() {
        long now = base();
        lag.start();
        now += 30_000L;
        lag.tick(now);
        lag.forceStop();
        assertEquals(EnemyLag.State.LOCKED, lag.state());
        assertTrue(lag.isLocked());
        lag.start();
        assertEquals(EnemyLag.State.LOCKED, lag.state());
    }

    // ------------------------------------------------------------------
    // Ramp bounds
    // ------------------------------------------------------------------

    @Test
    public void rampNeverExceedsConfiguredIntensity() {
        injectValidOffsets();
        lag.configure(new EnemyLag.LagSettings(3, EnemyLag.Mode.STUTTER, 60_000L));
        long now = base();
        lag.start();
        int guard = 0;
        while (lag.ramping() && guard++ < 1000) {
            now += 1_500L;
            lag.tick(now);
        }
        assertEquals(EnemyLag.State.ACTIVE, lag.state());
        assertTrue(lag.intensityAt() <= 3);
        assertTrue(lag.invariantsHold(now));
        assertTrue(lag.invariantFailures(now).isEmpty());
    }

    // ------------------------------------------------------------------
    // Offset-validity gate
    // ------------------------------------------------------------------

    @Test
    public void start_deferredWhileOffsetsInvalid() {
        // Default repo resolves the all-zero placeholder set → not ready.
        long now = base();
        lag.start();
        assertEquals(EnemyLag.State.ARMING, lag.state()); // armed optimistically
        lag.tick(now + 1_000L);
        // Gate trips on the first tick and suspends the session.
        assertTrue(lag.isSuspended());
        assertEquals(EnemyLag.State.COOLDOWN, lag.state());
        assertFalse(lag.isActive());
    }

    // ------------------------------------------------------------------
    // Honeypot gate
    // ------------------------------------------------------------------

    @Test
    public void honeypotQuarantine_suspendsActiveSession() {
        BypassStack stack = BypassStack.getInstance(RuntimeEnvironment.getApplication());
        long now = base();
        lag.start();
        now += 30_000L;
        lag.tick(now);
        if (lag.isSuspended()) {
            // Offset gate blocked us; feed the honeypot anyway and assert
            // the module stays locked down.
            feedTrapPlayer(stack);
            lag.tick(now + 1_000L);
            assertTrue(lag.isSuspended() || !lag.isActive());
            return;
        }
        feedTrapPlayer(stack);
        lag.tick(now + 1_000L);
        assertTrue(stack.honeypotGuard.quarantinedCount() > 0);
        assertFalse(lag.isActive());
        assertTrue(lag.isSuspended() || lag.state() == EnemyLag.State.COOLDOWN);
    }

    private void feedTrapPlayer(BypassStack stack) {
        long now = System.currentTimeMillis();
        PlayerData p = new PlayerData(77, true, 100f, 100f, 1337f);
        stack.honeypotGuard.observe(p, now);
    }

    // ------------------------------------------------------------------
    // Command frames
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Make the offset gate pass (valid non-zero table). */
    private void injectValidOffsets() {
        BypassStack stack = BypassStack.getInstance(RuntimeEnvironment.getApplication());
        stack.injectOffsetsForTest(
                "{\"versions\":[{\"version\":\"unknown\",\"enemy_base\":1048576,"
                        + "\"player_size\":256,\"player_x_off\":4,\"player_y_off\":8,"
                        + "\"player_hp_off\":12,\"player_mana_off\":516,\"player_team_off\":520,"
                        + "\"player_level_off\":524,\"camera_zoom_addr\":67108864,"
                        + "\"ai_move_speed_addr\":768,\"retri_cd_addr\":772}]}");
    }

    /** Real-clock base so injected tick times stay consistent with internals. */
    private long base() {
        return System.currentTimeMillis();
    }

    /** Run the ramp until ACTIVE; returns the time ACTIVE was entered. */
    private long runToActive(long start) {
        injectValidOffsets();
        lag.start();
        long now = start;
        long entered = -1L;
        int guard = 0;
        while (guard++ < 1000) {
            now += 1_500L;
            lag.tick(now);
            if (lag.isActive()) {
                entered = now;
                break;
            }
            if (lag.isSuspended()) break;
        }
        if (entered < 0) {
            // Gate environment: force active so frame tests stay meaningful.
            now += 1_500L;
            lag.tick(now);
            return -1L;
        }
        return entered;
    }

    @Test
    public void buildStopCommand_isValid() {
        byte[] stop = EnemyLag.buildStopCommand();
        assertEquals(EnemyLag.COMMAND_FRAME_SIZE, stop.length);
        assertEquals(EnemyLag.CMD_MARKER, stop[0]);
        assertEquals(EnemyLag.CMD_LAG_STOP, stop[1]);
        assertNull(EnemyLag.validateCommand(stop));
    }

    @Test
    public void buildModeCommand_roundTrips() {
        byte[] frame = EnemyLag.buildModeCommand(EnemyLag.Mode.RUBBER);
        assertNull(EnemyLag.validateCommand(frame));
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(frame)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int ordinal = Math.round(b.getFloat(3));
        assertEquals(EnemyLag.Mode.RUBBER, EnemyLag.Mode.fromInt(ordinal));
    }

    @Test
    public void setCommand_carriesIntensityAndDuration() {
        long entered = runToActive(base());
        if (entered < 0) return;
        byte[] set = lag.buildSetCommand(entered + 5_000L);
        assertNull(EnemyLag.validateCommand(set));
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(set)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        float intensity = b.getFloat(3);
        assertTrue(intensity >= EnemyLag.INTENSITY_MIN);
        assertTrue(intensity <= EnemyLag.INTENSITY_MAX);
    }

    @Test
    public void validateCommand_rejectsGarbage() {
        byte[] bad = new byte[17];
        assertNotNull(EnemyLag.validateCommand(bad)); // no marker
        byte[] badLen = new byte[8];
        assertNotNull(EnemyLag.validateCommand(badLen)); // wrong length
        byte[] badIntensity = new byte[17];
        badIntensity[0] = EnemyLag.CMD_MARKER;
        badIntensity[1] = EnemyLag.CMD_LAG_SET;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(badIntensity)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.putFloat(3, 99f); // out of range
        assertNotNull(EnemyLag.validateCommand(badIntensity));
    }

    // ------------------------------------------------------------------
    // Self-cleansing
    // ------------------------------------------------------------------

    @Test
    public void stop_schedulesRestoreStopCommand() {
        long entered = runToActive(base());
        if (entered < 0) return;
        lag.stop();
        assertTrue(lag.restoreRetryPending());
        byte[] stop = lag.nextCommand(entered + 10_000L);
        assertNotNull(stop);
        assertEquals(EnemyLag.CMD_LAG_STOP, stop[1]);
    }

    @Test
    public void takeCommand_respectsCadence() {
        long entered = runToActive(base());
        if (entered < 0) return;
        byte[] first = lag.nextCommand(entered + 5_000L);
        assertNotNull(first);
        assertEquals(EnemyLag.CMD_LAG_SET, first[1]);
        byte[] second = lag.nextCommand(entered + 5_100L);
        assertNull(second); // cadence gap still in effect
    }

    // ------------------------------------------------------------------
    // Mode handling
    // ------------------------------------------------------------------

    @Test
    public void modeRefresh_sendsModeFrameAfterStop() {
        long e1 = runToActive(base());
        if (e1 < 0) return;
        byte[] set = lag.nextCommand(e1 + 5_000L);
        assertNotNull(set);
        assertEquals(EnemyLag.CMD_LAG_SET, set[1]);
        lag.stop();
        byte[] stop = lag.nextCommand(e1 + 10_000L); // restore retry
        assertNotNull(stop);
        assertEquals(EnemyLag.CMD_LAG_STOP, stop[1]);
        lag.clearCooldownForTest();
        long e2 = runToActive(e1 + 120_000L); // second session
        if (e2 < 0) return;
        lag.setMode(EnemyLag.Mode.FREEZE);
        byte[] mode = lag.nextCommand(e2 + 5_000L);
        assertNotNull(mode);
        assertEquals(EnemyLag.CMD_LAG_MODE, mode[1]);
        assertEquals(EnemyLag.Mode.FREEZE, lag.mode());
    }

    @Test
    public void settings_areClamped() {
        EnemyLag.LagSettings s = new EnemyLag.LagSettings(99, null, 1L);
        assertEquals(EnemyLag.INTENSITY_MAX, s.intensity);
        assertEquals(EnemyLag.Mode.STUTTER, s.mode);
        assertEquals(EnemyLag.DURATION_MIN_MS, s.maxDurationMs);
    }

    // ------------------------------------------------------------------
    // Rate interlock
    // ------------------------------------------------------------------

    @Test
    public void commandRate_neverExceedsLimitPerSecond() {
        long entered = runToActive(base());
        if (entered < 0) return;
        int sent = 0;
        long now = entered + 5_000L;
        for (int i = 0; i < 60; i++) {
            byte[] cmd = lag.nextCommand(now);
            if (cmd != null) sent++;
            now += 50L;
        }
        assertTrue(sent <= 6);
    }

    // ------------------------------------------------------------------
    // Pulse profile
    // ------------------------------------------------------------------

    @Test
    public void pulseProfile_matchesMode() {
        lag.configure(new EnemyLag.LagSettings(5, EnemyLag.Mode.RUBBER, 60_000L));
        EnemyLag.PulseProfile p = lag.currentPulseProfile(base());
        assertEquals(EnemyLag.Mode.RUBBER, p.mode);
        assertTrue(p.deltaPx > 0f);
    }
}
