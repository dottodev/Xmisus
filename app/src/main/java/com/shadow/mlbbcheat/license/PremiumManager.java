package com.shadow.mlbbcheat.license;

import android.content.Context;
import android.content.SharedPreferences;

import com.shadow.mlbbcheat.utils.Crypto;

/**
 * Feature gating by license tier.
 *
 * Free tier keeps the core QoL features (ESP, map hack, enemy alert,
 * auto-retri). Premium unlocks the high-risk/high-value features:
 * drone view, aim assist, and advanced predictive aim.
 *
 * Also supports ad-rewarded temporary premium (e.g. "watch an ad for 1h
 * premium") — stored as a separate, signed grant so it composes with the
 * key system (whichever is more generous wins).
 */
public final class PremiumManager {

    public static final String FEATURE_ESP = "esp";
    public static final String FEATURE_MAP = "map";
    public static final String FEATURE_ALERT = "alert";
    public static final String FEATURE_RETRI = "retri";
    public static final String FEATURE_DRONE = "drone";
    public static final String FEATURE_AIM = "aim";

    private static final String PREFS = "shadow_premium";
    private static final String KEY_TEMP_TIER = "temp_tier";
    private static final String KEY_TEMP_EXPIRY = "temp_expiry_ts";
    private static final String KEY_TEMP_SIG = "temp_sig";

    private static final String[] FREE_FEATURES = {
        FEATURE_ESP, FEATURE_MAP, FEATURE_ALERT, FEATURE_RETRI
    };
    private static final String[] PREMIUM_FEATURES = {
        FEATURE_DRONE, FEATURE_AIM
    };

    private PremiumManager() {}

    /** Whether a feature is enabled for the current state. */
    public static boolean featureEnabled(Context context, String feature) {
        if (isFreeFeature(feature)) return true;
        if (!isPremiumFeature(feature)) return false;
        return isPremiumActive(context);
    }

    /** Premium if the key grant says premium, OR temp ad grant is live. */
    public static boolean isPremiumActive(Context context) {
        if (KeyManager.isPremium(context)) return true;
        return temporaryPremiumMs(context) > 0;
    }

    // ------------------------------------------------------------------
    // Ad-rewarded temporary premium
    // ------------------------------------------------------------------

    /** Grant temporary premium from an ad reward. */
    public static synchronized void grantTemporaryPremium(Context context, long durationMs) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long expiry = System.currentTimeMillis() + durationMs;
        String device = com.shadow.mlbbcheat.net.ServerClient.deviceId(context);
        String payload = device + "|" + expiry;
        String sig = Crypto.sha256Hex(payload);
        sp.edit()
                .putLong(KEY_TEMP_EXPIRY, expiry)
                .putString(KEY_TEMP_SIG, sig)
                .putString(KEY_TEMP_TIER, KeyManager.TIER_PREMIUM)
                .apply();
    }

    /** Remaining temp premium in ms (0 if none/expired/tampered). */
    public static synchronized long temporaryPremiumMs(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long expiry = sp.getLong(KEY_TEMP_EXPIRY, 0L);
        String device = com.shadow.mlbbcheat.net.ServerClient.deviceId(context);
        String expectedSig = Crypto.sha256Hex(device + "|" + expiry);
        if (!Crypto.constantTimeEquals(expectedSig, sp.getString(KEY_TEMP_SIG, ""))) {
            return 0;
        }
        return Math.max(0, expiry - System.currentTimeMillis());
    }

    // ------------------------------------------------------------------

    private static boolean isFreeFeature(String f) {
        for (String s : FREE_FEATURES) if (s.equals(f)) return true;
        return false;
    }

    private static boolean isPremiumFeature(String f) {
        for (String s : PREMIUM_FEATURES) if (s.equals(f)) return true;
        return false;
    }
}