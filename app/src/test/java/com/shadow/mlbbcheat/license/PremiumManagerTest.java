package com.shadow.mlbbcheat.license;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class PremiumManagerTest {

    private Context ctx() {
        return RuntimeEnvironment.getApplication();
    }

    @Test
    public void freeFeaturesEnabledByDefault() {
        KeyManager.clear(ctx());
        assertTrue(PremiumManager.featureEnabled(ctx(), PremiumManager.FEATURE_ESP));
        assertTrue(PremiumManager.featureEnabled(ctx(), PremiumManager.FEATURE_MAP));
        assertTrue(PremiumManager.featureEnabled(ctx(), PremiumManager.FEATURE_ALERT));
        assertTrue(PremiumManager.featureEnabled(ctx(), PremiumManager.FEATURE_RETRI));
    }

    @Test
    public void premiumFeaturesDisabledByDefault() {
        KeyManager.clear(ctx());
        assertFalse(PremiumManager.featureEnabled(ctx(), PremiumManager.FEATURE_DRONE));
        assertFalse(PremiumManager.featureEnabled(ctx(), PremiumManager.FEATURE_AIM));
        assertFalse(PremiumManager.isPremiumActive(ctx()));
    }

    @Test
    public void permanentKeyUnlocksPremiumFeatures() {
        KeyManager.clear(ctx());
        KeyManager.storeGrant(ctx(), KeyManager.TIER_PREMIUM, Long.MAX_VALUE);
        assertTrue(PremiumManager.featureEnabled(ctx(), PremiumManager.FEATURE_DRONE));
        assertTrue(PremiumManager.featureEnabled(ctx(), PremiumManager.FEATURE_AIM));
    }

    @Test
    public void unknownFeatureIsDenied() {
        assertFalse(PremiumManager.featureEnabled(ctx(), "not_a_feature"));
    }

    @Test
    public void adRewardGrantsTemporaryPremium() {
        KeyManager.clear(ctx());
        PremiumManager.grantTemporaryPremium(ctx(), 60_000);
        assertTrue(PremiumManager.isPremiumActive(ctx()));
        assertTrue(PremiumManager.temporaryPremiumMs(ctx()) > 0);
        assertTrue(PremiumManager.featureEnabled(ctx(), PremiumManager.FEATURE_DRONE));
    }

    @Test
    public void expiredAdPremiumRevertsToFree() {
        KeyManager.clear(ctx());
        PremiumManager.grantTemporaryPremium(ctx(), 1000);
        // simulate time passing by writing an already-expired grant
        PremiumManager.grantTemporaryPremium(ctx(), -1000);
        assertFalse(PremiumManager.isPremiumActive(ctx()));
        assertFalse(PremiumManager.featureEnabled(ctx(), PremiumManager.FEATURE_AIM));
    }
}