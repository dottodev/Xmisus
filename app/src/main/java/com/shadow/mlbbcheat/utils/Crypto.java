package com.shadow.mlbbcheat.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Random;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * All crypto used by the cheat stack.
 *
 * - AES-256-GCM for payload encryption (authenticated, tamper-evident)
 * - HMAC-SHA256 for request signing / integrity of control messages
 * - Rolling XOR for the high-frequency memory frames (cheap, key rotates
 *   every N frames so pattern analysis sees no stable ciphertext)
 * - SHA-256 for fingerprints and integrity digests
 *
 * Keys are NOT hardcoded in the shipped app beyond a derivation seed; the
 * runtime master key is derived per-install from device-scoped entropy so a
 * memory dump of one user's device does not break every other install.
 */
public final class Crypto {

    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Random rollingRandom = new Random();
    private final SecretKeySpec aesKey;
    private final byte[] hmacKey;
    private byte rollingKey = (byte) rollingRandom.nextInt(256);
    private int framesSinceKeyChange = 0;
    private int maxFramesPerKey = 16 + rollingRandom.nextInt(24);

    public Crypto(byte[] installEntropy) {
        byte[] seed = sha256(installEntropy);
        this.aesKey = new SecretKeySpec(Arrays.copyOfRange(seed, 0, 32), "AES");
        this.hmacKey = Arrays.copyOfRange(seed, 32, 64);
    }

    // ------------------------------------------------------------------
    // AES-256-GCM
    // ------------------------------------------------------------------

    public byte[] encryptAes(byte[] plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = c.doFinal(plaintext);
            byte[] out = new byte[GCM_IV_BYTES + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, GCM_IV_BYTES);
            System.arraycopy(ciphertext, 0, out, GCM_IV_BYTES, ciphertext.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM encrypt failed", e);
        }
    }

    /** Returns null on authentication failure (tamper / wrong key). */
    public byte[] decryptAes(byte[] data) {
        try {
            if (data == null || data.length <= GCM_IV_BYTES) return null;
            byte[] iv = Arrays.copyOfRange(data, 0, GCM_IV_BYTES);
            byte[] body = Arrays.copyOfRange(data, GCM_IV_BYTES, data.length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return c.doFinal(body);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // HMAC-SHA256
    // ------------------------------------------------------------------

    public byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failed", e);
        }
    }

    public boolean verifySignature(byte[] payload, byte[] signature) {
        byte[] expected = sign(payload);
        return MessageDigest.isEqual(expected, signature);
    }

    // ------------------------------------------------------------------
    // Rolling XOR for high-frequency frames
    // ------------------------------------------------------------------

    public synchronized byte[] rollingXor(byte[] data) {
        if (data == null) return null;
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte) (data[i] ^ rollingKey);
        }
        framesSinceKeyChange++;
        if (framesSinceKeyChange >= maxFramesPerKey) {
            rollingKey = (byte) rollingRandom.nextInt(256);
            maxFramesPerKey = 16 + rollingRandom.nextInt(24);
            framesSinceKeyChange = 0;
        }
        return out;
    }

    public synchronized byte[] rollingXor(byte[] data, byte key) {
        if (data == null) return null;
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte) (data[i] ^ key);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Digests
    // ------------------------------------------------------------------

    public static byte[] sha256(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(input);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String sha256Hex(String input) {
        byte[] h = sha256(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(h.length * 2);
        for (byte b : h) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** Constant-time string comparison. */
    public static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
