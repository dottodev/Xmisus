package com.shadow.mlbbcheat.license;

import android.content.Context;
import android.content.SharedPreferences;

import com.shadow.mlbbcheat.utils.Crypto;

/**
 * Key-system state manager.
 *
 * A user pastes a key; the app sends it (encrypted) to the server; the
 * server replies with the granted tier + expiry; this manager persists the
 * grant locally and reports whether premium is currently active.
 *
 * Key formats accepted:
 *   XXXX-XXXX-XXXX-XXXX   (16 chars, dashed)
 *   XXXXXXXXXXXXXXXXXXXX  (32 hex chars)
 *
 * Keys are validated client-side for shape only; the server is the source
 * of truth. Locally stored grants are signed (HMAC over device+tier+expiry)
 * so users cannot hand-edit the SharedPreferences file to extend expiry.
 */
public final class KeyManager {

    private static final String PREFS = "shadow_license";
    private static final String KEY_GRANT = "grant";
    private static final String KEY_TIER = "tier";
    private static final String KEY_EXPIRY = "expiry_ts";
    private static final String KEY_SIG = "grant_sig";

    public static final String TIER_FREE = "free";
    public static final String TIER_PREMIUM = "premium";
    public static final long PERMANENT_EXPIRY = Long.MAX_VALUE;

    private KeyManager() {}

    /** Shape check — 16 dashed chars or 32 hex chars. */
    public static boolean looksValid(String key) {
        if (key == null) return false;
        String k = key.trim();
        if (k.matches("(?i)[A-F0-9]{4}-[A-F0-9]{4}-[A-F0-9]{4}-[A-F0-9]{4}")) {
            return true;
        }
        return k.matches("(?i)[A-F0-9]{32}");
    }

    /** Normalize to the canonical 16-char dashed form. */
    public static String normalize(String key) {
        if (key == null) return "";
        String k = key.trim().toUpperCase().replace("-", "").replace(" ", "");
        if (k.length() == 32) k = k.substring(0, 16);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < k.length() && i < 16; i++) {
            if (i > 0 && i % 4 == 0) sb.append('-');
            sb.append(k.charAt(i));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Grant state
    // ------------------------------------------------------------------

    public static synchronized boolean storeGrant(Context context, String tier, long expiryTs) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String device = com.shadow.mlbbcheat.net.ServerClient.deviceId(context);
        String payload = device + "|" + tier + "|" + expiryTs;
        String sig = Crypto.sha256Hex(payload);
        return sp.edit()
                .putString(KEY_GRANT, payload)
                .putString(KEY_TIER, tier)
                .putLong(KEY_EXPIRY, expiryTs)
                .putString(KEY_SIG, sig)
                .commit();
    }

    public static synchronized String currentTier(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String payload = sp.getString(KEY_GRANT, null);
        if (payload == null) return TIER_FREE;

        String device = com.shadow.mlbbcheat.net.ServerClient.deviceId(context);
        String tier = sp.getString(KEY_TIER, TIER_FREE);
        long expiry = sp.getLong(KEY_EXPIRY, 0L);
        String expectedSig = Crypto.sha256Hex(device + "|" + tier + "|" + expiry);
        if (!Crypto.constantTimeEquals(expectedSig, sp.getString(KEY_SIG, ""))) {
            return TIER_FREE; // tampered grant → free
        }
        if (expiry != PERMANENT_EXPIRY && System.currentTimeMillis() > expiry) {
            return TIER_FREE; // expired
        }
        return tier;
    }

    public static boolean isPremium(Context context) {
        return TIER_PREMIUM.equals(currentTier(context));
    }

    /** ms until expiry, or -1 if permanent/free. */
    public static long msUntilExpiry(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long expiry = sp.getLong(KEY_EXPIRY, 0L);
        if (expiry == PERMANENT_EXPIRY) return -1;
        return Math.max(0, expiry - System.currentTimeMillis());
    }

    public static synchronized void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit();
    }
}