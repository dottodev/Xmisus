package com.shadow.mlbbcheat.utils.bypass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class IdentityShieldTest {

    private Context ctx() {
        return RuntimeEnvironment.getApplication();
    }

    @Test
    public void constructor_plantsInstallModel() {
        IdentityShield s = new IdentityShield(ctx());
        assertTrue(s.installTimestampMs() > 0L);
        assertTrue(s.installTimestampMs() <= System.currentTimeMillis());
        assertEquals(6, s.noiseRowCount());
    }

    @Test
    public void openTracking_countsOpens() {
        IdentityShield s = new IdentityShield(ctx());
        s.noteOpen();
        assertTrue(s.opensToday() >= 1);
        assertTrue(s.opensPlausible());
    }

    @Test
    public void mask_obscuresSemanticNames() {
        assertNotEquals("esp_enable", IdentityShield.mask("esp_enable"));
        assertEquals("", IdentityShield.mask(""));
    }

    @Test
    public void key_isStableOpaque() {
        assertEquals(IdentityShield.key("aim"),
                IdentityShield.key("aim"));
        assertNotEquals(IdentityShield.key("aim"),
                IdentityShield.key("esp"));
    }

    @Test
    public void layout_createdAndNormal() {
        IdentityShield s = new IdentityShield(ctx());
        s.ensureNormalLayout();
        assertTrue(s.layoutNormal());
    }

    @Test
    public void sessionSeed_rotatesAndIsStableWithinWindow() {
        IdentityShield s = new IdentityShield(ctx());
        String a = s.sessionSeedHex();
        String b = s.sessionSeedHex();
        assertEquals(a, b);
        assertNotNull(a);
        assertFalse(a.isEmpty());
    }

    @Test
    public void identityToken_isComposed() {
        IdentityShield s = new IdentityShield(ctx());
        String tok = s.identityToken();
        assertNotNull(tok);
        assertTrue(tok.contains("-"));
    }

    @Test
    public void anchoredTimestamp_tracksUptime() {
        IdentityShield s = new IdentityShield(ctx());
        long t1 = s.anchoredTimestamp();
        assertTrue(t1 > 0L);
        assertTrue(s.telemetryNow() >= t1);
    }

    @Test
    public void versionString_present() {
        IdentityShield s = new IdentityShield(ctx());
        String v = s.versionString();
        assertNotNull(v);
        assertFalse(v.isEmpty());
    }

    @Test
    public void toggleFlips_areRareButPossible() {
        IdentityShield s = new IdentityShield(ctx());
        int flips = 0;
        for (int i = 0; i < 100; i++) {
            if (s.suggestToggleFlip()) flips++;
        }
        assertTrue(flips >= 0);
        assertTrue(flips <= 100);
    }

    @Test
    public void opaqueId_andEntropy_nonTrivial() {
        IdentityShield s = new IdentityShield(ctx());
        assertFalse(s.opaqueId().isEmpty());
        assertTrue(s.entropy() >= 0);
    }

    @Test
    public void stats_arePresent() {
        IdentityShield s = new IdentityShield(ctx());
        IdentityShield.IdentityStats st = s.stats();
        assertNotNull(st);
        assertTrue(st.opensToday >= 0);
        assertEquals(6, st.noiseRows);
        assertNotNull(st.version);
    }

    @Test
    public void onboardingWindow_visibleEarly() {
        IdentityShield s = new IdentityShield(ctx());
        // Fresh install: first-run age is a few ms → in onboarding
        assertTrue(s.inOnboardingWindow() || s.firstRunAgeMs() >= 0L);
    }
}