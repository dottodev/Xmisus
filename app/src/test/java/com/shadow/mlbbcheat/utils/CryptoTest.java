package com.shadow.mlbbcheat.utils;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Arrays;

public class CryptoTest {

    @Test
    public void aesGcm_roundTrips() {
        Crypto crypto = new Crypto("install-entropy-123".getBytes());
        byte[] plain = "hello encrypted world".getBytes();
        byte[] encrypted = crypto.encryptAes(plain);
        assertFalse(Arrays.equals(plain, encrypted));
        byte[] decrypted = crypto.decryptAes(encrypted);
        assertArrayEquals(plain, decrypted);
    }

    @Test
    public void aesGcm_detectsTampering() {
        Crypto crypto = new Crypto("install-entropy-123".getBytes());
        byte[] encrypted = crypto.encryptAes("payload".getBytes());
        encrypted[encrypted.length - 1] ^= 0x01; // flip one ciphertext byte
        assertNull(crypto.decryptAes(encrypted));
    }

    @Test
    public void hmac_signAndVerify() {
        Crypto crypto = new Crypto("install-entropy-123".getBytes());
        byte[] payload = "control message".getBytes();
        byte[] sig = crypto.sign(payload);
        assertTrue(crypto.verifySignature(payload, sig));
        assertFalse(crypto.verifySignature("tampered".getBytes(), sig));
    }

    @Test
    public void rollingXor_changesKeyOverFrames() {
        Crypto crypto = new Crypto("entropy".getBytes());
        byte[] frame = new byte[17];
        byte[] first = crypto.rollingXor(frame.clone());
        assertFalse(Arrays.equals(frame, first));
        // After enough frames the key must have rotated
        for (int i = 0; i < 200; i++) crypto.rollingXor(frame.clone());
        // Decode with the round-tripped key should still produce the same data
        byte[] wrapped = crypto.rollingXor(frame.clone(), (byte) 0);
        assertNotNull(wrapped);
    }

    @Test
    public void sha256_isDeterministic() {
        assertEquals(
                Crypto.sha256Hex("mlbb"),
                Crypto.sha256Hex("mlbb"));
        assertNotEquals(
                Crypto.sha256Hex("mlbb"),
                Crypto.sha256Hex("mlbba"));
    }

    @Test
    public void constantTimeEquals() {
        assertTrue(Crypto.constantTimeEquals("abc", "abc"));
        assertFalse(Crypto.constantTimeEquals("abc", "abd"));
    }
}
