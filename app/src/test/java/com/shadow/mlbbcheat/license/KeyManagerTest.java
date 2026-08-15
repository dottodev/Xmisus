package com.shadow.mlbbcheat.license;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class KeyManagerTest {

    private Context ctx() {
        return RuntimeEnvironment.getApplication();
    }

    @Test
    public void looksValid_acceptsDashedForm() {
        assertTrue(KeyManager.looksValid("A1B2-C3D4-E5F6-0718"));
        assertTrue(KeyManager.looksValid("a1b2-c3d4-e5f6-0718"));
        assertTrue(KeyManager.looksValid("A1B2-C3D4-E5F6-0718"));
    }

    @Test
    public void looksValid_accepts32Hex() {
        assertTrue(KeyManager.looksValid("a1b2c3d4e5f60718293a4b5c6d7e8f90"));
        assertTrue(KeyManager.looksValid("A1B2C3D4E5F60718293A4B5C6D7E8F90"));
    }

    @Test
    public void looksValid_rejectsGarbage() {
        assertFalse(KeyManager.looksValid(""));
        assertFalse(KeyManager.looksValid("short"));
        assertFalse(KeyManager.looksValid("not-a-key-here-123"));
        assertFalse(KeyManager.looksValid("ZZZZ-ZZZZ-ZZZZ-ZZZZ")); // non-hex
        assertFalse(KeyManager.looksValid(null));
    }

    @Test
    public void normalize_producesCanonicalDashedForm() {
        assertEquals("A1B2-C3D4-E5F6-0718",
                KeyManager.normalize("A1B2-C3D4-E5F6-0718"));
        assertEquals("A1B2-C3D4-E5F6-0718",
                KeyManager.normalize("a1b2c3d4e5f60718"));
        assertEquals("A1B2-C3D4-E5F6-0718",
                KeyManager.normalize("a1b2c3d4e5f60718293a4b5c6d7e8f90")); // 32-hex truncated
    }

    @Test
    public void defaultTierIsFree() {
        assertEquals(KeyManager.TIER_FREE, KeyManager.currentTier(ctx()));
        assertFalse(KeyManager.isPremium(ctx()));
    }

    @Test
    public void storeGrant_makesPremiumActive() {
        assertTrue(KeyManager.storeGrant(ctx(), KeyManager.TIER_PREMIUM, Long.MAX_VALUE));
        assertTrue(KeyManager.isPremium(ctx()));
        assertEquals(KeyManager.TIER_PREMIUM, KeyManager.currentTier(ctx()));
    }

    @Test
    public void expiredGrantFallsBackToFree() {
        KeyManager.storeGrant(ctx(), KeyManager.TIER_PREMIUM,
                System.currentTimeMillis() - 1000);
        assertEquals(KeyManager.TIER_FREE, KeyManager.currentTier(ctx()));
    }

    @Test
    public void futureExpiryGrantStaysPremium() {
        KeyManager.storeGrant(ctx(), KeyManager.TIER_PREMIUM,
                System.currentTimeMillis() + 60_000);
        assertTrue(KeyManager.isPremium(ctx()));
        assertTrue(KeyManager.msUntilExpiry(ctx()) > 0);
    }

    @Test
    public void clearResetsToFree() {
        KeyManager.storeGrant(ctx(), KeyManager.TIER_PREMIUM, Long.MAX_VALUE);
        KeyManager.clear(ctx());
        assertFalse(KeyManager.isPremium(ctx()));
    }
}