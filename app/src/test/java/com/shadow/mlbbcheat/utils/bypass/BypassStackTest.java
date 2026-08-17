package com.shadow.mlbbcheat.utils.bypass;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.shadow.mlbbcheat.models.PlayerData;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class BypassStackTest {

    @Before
    public void setUp() {
        BypassStack.resetForTest();
    }

    private Context ctx() {
        return RuntimeEnvironment.getApplication();
    }

    @Test
    public void getInstance_returnsSharedSingleton() {
        BypassStack a = BypassStack.getInstance(ctx());
        BypassStack b = BypassStack.getInstance(ctx());
        assertTrue(a == b);
    }

    @Test
    public void onStart_activatesStack() {
        BypassStack s = BypassStack.getInstance(ctx());
        assertFalse(s.espAllowed());
        s.onStart();
        assertTrue(s.espAllowed());
    }

    @Test
    public void intensity_isBounded() {
        BypassStack s = BypassStack.getInstance(ctx());
        s.onStart();
        for (int i = 0; i < 50; i++) {
            float v = s.espIntensity();
            assertTrue(v >= 0.05f);
            assertTrue(v <= 1f);
        }
    }

    @Test
    public void sanitizeEnemies_keepsCleanList() {
        BypassStack s = BypassStack.getInstance(ctx());
        s.onStart();
        List<PlayerData> input = new ArrayList<>();
        input.add(new PlayerData(1, true, 100f, 100f, 500f));
        input.add(new PlayerData(2, true, 300f, 300f, 800f));
        List<PlayerData> out = s.sanitizeEnemies(input, System.currentTimeMillis());
        assertNotNull(out);
        assertFalse(out.isEmpty());
    }

    @Test
    public void sanitizeEnemies_handlesNullAndEmpty() {
        BypassStack s = BypassStack.getInstance(ctx());
        assertTrue(s.sanitizeEnemies(null, 0L).isEmpty());
        assertTrue(s.sanitizeEnemies(new ArrayList<PlayerData>(), 0L).isEmpty());
    }

    @Test
    public void hardStop_startsFalse() {
        BypassStack s = BypassStack.getInstance(ctx());
        assertFalse(s.hardStop());
    }

    @Test
    public void tick_doesNotThrow() {
        BypassStack s = BypassStack.getInstance(ctx());
        s.onStart();
        s.tick();
        s.tick();
    }

    @Test
    public void matchLifecycle_noErrors() {
        BypassStack s = BypassStack.getInstance(ctx());
        s.onMatchStart();
        s.onKill();
        s.onKill();
        s.onDeath();
        s.onAssist();
        s.onDomination();
        s.onReportEvent();
        s.onMatchEnd(true);
        s.onMatchEnd(false);
    }

    @Test
    public void heartbeatPacing_isBounded() {
        BypassStack s = BypassStack.getInstance(ctx());
        s.onStart();
        boolean allowed = s.heartbeatAllowed();
        s.markHeartbeatSent();
        assertTrue(s.networkShield.pacingWaitMs() >= 0L);
    }

    @Test
    public void applyRemoteOffsets_rejectsGarbage() {
        BypassStack s = BypassStack.getInstance(ctx());
        assertFalse(s.applyRemoteOffsets(null));
        assertFalse(s.applyRemoteOffsets("{bad"));
    }

    @Test
    public void stats_arePresent() {
        BypassStack s = BypassStack.getInstance(ctx());
        s.onStart();
        BypassStack.StackStats st = s.stats();
        assertNotNull(st);
        assertTrue(st.started);
        assertFalse(st.hardStop);
    }

    @Test
    public void stealth_afterHoneypotPhantom() {
        BypassStack s = BypassStack.getInstance(ctx());
        s.onStart();
        long now = 1_000_000L;
        // Freeze an enemy → phantom → stealth engages
        for (int i = 0; i < 14; i++) {
            List<PlayerData> frame = new ArrayList<>();
            frame.add(new PlayerData(7, true, 50f, 50f, 500f));
            s.sanitizeEnemies(frame, now + i * 1000L);
        }
        // A short match start resets match-level state; check it doesn't crash
        s.onMatchStart();
    }

    @Test
    public void espAllowed_falseWhenStopped() {
        BypassStack s = BypassStack.getInstance(ctx());
        s.onStart();
        assertTrue(s.espAllowed());
        s.onStop();
        assertFalse(s.espAllowed());
    }
}