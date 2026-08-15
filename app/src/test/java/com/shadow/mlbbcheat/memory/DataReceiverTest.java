package com.shadow.mlbbcheat.memory;

import static org.junit.Assert.*;

import com.shadow.mlbbcheat.models.PlayerData;

import org.junit.Test;

public class DataReceiverTest {

    @Test
    public void encodeFrame_roundTripsThroughFromBytes() {
        PlayerData p = new PlayerData(2, true, 55f, 66f, 1200f);
        byte[] frame = DataReceiver.encodeFrame(p);
        PlayerData decoded = PlayerData.fromBytes(frame);
        assertEquals(p.id, decoded.id);
        assertEquals(p.isEnemy, decoded.isEnemy);
        assertEquals(p.x, decoded.x, 0.001f);
        assertEquals(p.y, decoded.y, 0.001f);
        assertEquals(p.hp, decoded.hp, 0.001f);
    }

    @Test
    public void encodeFrame_rejectsBadId() {
        PlayerData p = new PlayerData(300, true, 0f, 0f, 0f);
        assertNull(DataReceiver.encodeFrame(p));
    }
}
