package com.shadow.mlbbcheat.net;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import com.shadow.mlbbcheat.utils.Crypto;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Encrypted control-channel client.
 *
 * Protocol (JSON over HTTPS, every body AES-256-GCM wrapped, every request
 * HMAC-signed with a per-install key):
 *
 *   POST {BASE}/heartbeat   → body: {device, version, mlbb, ts}
 *                            reply: {kill:false, config:<offsetDB>|null}
 *   POST {BASE}/activate    → body: {device, license, ts}
 *                            reply: {ok:true|false}
 *
 * Kill-switch semantics: if the server says kill, the app stops the cheat
 * stack and shows a "service discontinued" state. The offset DB arrives in
 * the same response so one round trip keeps the whole product drivable.
 */
public final class ServerClient {

    private static final String PREFS = "shadow_net";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_LICENSE = "license_key";
    private static final String KEY_BANNED = "banned";
    private static final String KEY_KILL = "kill_switch";

    /**
     * Control server base URL.
     * Local testing: phone and PC on the same Wi-Fi, run `node server/server.js`
     * on the PC, then use `http://<PC-LAN-IP>:8080`. Plain HTTP is fine for LAN;
     * use HTTPS (reverse proxy) if you ever expose it publicly.
     */
    private static final String BASE_URL = "http://192.168.1.100:8080";
    private static final int TIMEOUT_MS = 8000;

    private final Context context;
    private final Crypto crypto;

    public ServerClient(Context context, Crypto crypto) {
        this.context = context.getApplicationContext();
        this.crypto = crypto;
    }

    public static String deviceId(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = sp.getString(KEY_DEVICE_ID, null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            sp.edit().putString(KEY_DEVICE_ID, id).apply();
        }
        return id;
    }

    public static void setLicense(Context context, String license) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LICENSE, license).apply();
    }

    public static String license(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LICENSE, null);
    }

    public static boolean isBanned(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_BANNED, false);
    }

    public static void setBanned(Context context, boolean banned) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_BANNED, banned).apply();
    }

    public boolean hasNetwork() {
        ConnectivityManager cm = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo ni = cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }

    /** Result of one heartbeat round trip. */
    public static final class HeartbeatResult {
        public final boolean killSwitch;
        public final String offsetDbJson;
        public HeartbeatResult(boolean killSwitch, String offsetDbJson) {
            this.killSwitch = killSwitch;
            this.offsetDbJson = offsetDbJson;
        }
    }

    public HeartbeatResult heartbeat(String appVersion, String mlbbFingerprint) throws IOException {
        if (!hasNetwork()) return new HeartbeatResult(false, null);

        String body = "{\"device\":\"" + deviceId(context)
                + "\",\"version\":\"" + appVersion
                + "\",\"mlbb\":\"" + mlbbFingerprint
                + "\",\"ts\":" + System.currentTimeMillis() + "}";
        return post("/heartbeat", body);
    }

    public boolean activate(String license) throws IOException {
        if (!hasNetwork()) return false;
        String body = "{\"device\":\"" + deviceId(context)
                + "\",\"license\":\"" + license
                + "\",\"ts\":" + System.currentTimeMillis() + "}";
        byte[] resp = postRaw("/activate", body);
        if (resp == null) return false;
        String text = new String(resp, StandardCharsets.UTF_8);
        return text.contains("\"ok\":true");
    }

    /** Result of a key validation round trip. */
    public static final class KeyResult {
        public final boolean ok;
        public final String tier;
        public final long expiryTs; // Long.MAX_VALUE = permanent
        public final String message;

        KeyResult(boolean ok, String tier, long expiryTs, String message) {
            this.ok = ok;
            this.tier = tier;
            this.expiryTs = expiryTs;
            this.message = message;
        }
    }

    /**
     * Validate a user-entered key with the server.
     * Server replies: {"ok":true,"tier":"premium","expiry":1234567890,"msg":"..."}
     * or {"ok":false,"msg":"invalid key"}. Keys are never logged locally.
     */
    public KeyResult validateKey(String key) throws IOException {
        if (!hasNetwork()) {
            return new KeyResult(false, null, 0L, "Offline — cannot validate key");
        }
        String body = "{\"device\":\"" + deviceId(context)
                + "\",\"key\":\"" + key
                + "\",\"ts\":" + System.currentTimeMillis() + "}";
        byte[] resp = postRaw("/validate", body);
        if (resp == null) {
            return new KeyResult(false, null, 0L, "Server unreachable");
        }
        String text = new String(resp, StandardCharsets.UTF_8);
        boolean ok = text.contains("\"ok\":true");
        String tier = com.shadow.mlbbcheat.license.KeyManager.TIER_PREMIUM;
        long expiry = Long.MAX_VALUE;
        String msg = "Key accepted";
        if (!ok) {
            msg = "Invalid or used key";
        } else {
            int ti = text.indexOf("\"tier\":");
            if (ti >= 0) {
                int q1 = text.indexOf('"', ti + 7);
                int q2 = text.indexOf('"', q1 + 1);
                if (q1 >= 0 && q2 > q1) tier = text.substring(q1 + 1, q2);
            }
            int ei = text.indexOf("\"expiry\":");
            if (ei >= 0) {
                int numStart = ei + 9;
                int numEnd = numStart;
                while (numEnd < text.length()
                        && Character.isDigit(text.charAt(numEnd))) numEnd++;
                if (numEnd > numStart) {
                    try {
                        expiry = Long.parseLong(text.substring(numStart, numEnd));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return new KeyResult(ok, tier, expiry, msg);
    }

    // ------------------------------------------------------------------

    private HeartbeatResult post(String path, String plainBody) throws IOException {
        byte[] response = postRaw(path, plainBody);
        if (response == null) return new HeartbeatResult(false, null);
        String text = new String(response, StandardCharsets.UTF_8);
        boolean kill = text.contains("\"kill\":true");
        String config = null;
        if (text.contains("\"config\":")) {
            int i = text.indexOf("\"config\":");
            int start = text.indexOf('{', i);
            int end = text.lastIndexOf('}');
            if (start >= 0 && end > start) config = text.substring(start, end + 1);
        }
        if (kill) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_KILL, true).apply();
        }
        return new HeartbeatResult(kill, config);
    }

    /** Send an HMAC-signed, AES-GCM-encrypted body; return decrypted reply. */
    private byte[] postRaw(String path, String plainBody) throws IOException {
        URL url = new URL(BASE_URL + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/octet-stream");
        conn.setRequestProperty("X-Dev", deviceId(context));

        byte[] payload = plainBody.getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = crypto.encryptAes(payload);
        byte[] signature = crypto.sign(encrypted);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(signature);
            out.write(encrypted);
            out.flush();
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            return null;
        }
        byte[] reply = readAll(conn.getInputStream());
        conn.disconnect();
        if (reply.length < 32) return null;
        byte[] sig = new byte[32];
        byte[] body = new byte[reply.length - 32];
        System.arraycopy(reply, 0, sig, 0, 32);
        System.arraycopy(reply, 32, body, 0, body.length);
        if (!crypto.verifySignature(body, sig)) return null;
        return crypto.decryptAes(body);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    /** Suppress unused-import style warnings for BufferedReader if unused later. */
    private static void unused(BufferedReader r) {
    }
}
