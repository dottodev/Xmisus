package com.shadow.mlbbcheat.models;

import static org.junit.Assert.*;

import org.junit.Test;

public class PlayerDataTest {

    @Test
    public void fromBytes_parsesValidFrame() {
        byte[] frame = new byte[17];
        frame[0] = 0x01;               // frame type
        frame[1] = 1;                  // id
        frame[2] = 1;                  // isEnemy
        writeFloat(frame, 3, 100.5f);  // x
        writeFloat(frame, 7, 200.25f); // y
        writeFloat(frame, 11, 3500f);  // hp

        PlayerData p = PlayerData.fromBytes(frame);

        assertEquals(1, p.id);
        assertTrue(p.isEnemy);
        assertEquals(100.5f, p.x, 0.001f);
        assertEquals(200.25f, p.y, 0.001f);
        assertEquals(3500f, p.hp, 0.001f);
        assertTrue(p.isAlive());
    }

    @Test
    public void isAlive_falseWhenHpZero() {
        byte[] frame = new byte[17];
        writeFloat(frame, 11, 0f);

        PlayerData p = PlayerData.fromBytes(frame);

        assertFalse(p.isAlive());
    }

    @Test
    public void distanceTo_computesEuclideanDistance() {
        PlayerData p = new PlayerData(1, true, 0f, 0f, 100f);
        assertEquals(5f, p.distanceTo(3f, 4f), 0.001f);
    }

    private void writeFloat(byte[] arr, int offset, float value) {
        int bits = Float.floatToIntBits(value);
        for (int i = 0; i < 4; i++) {
            arr[offset + i] = (byte) (bits >>> (8 * i));
        }
    }
}
