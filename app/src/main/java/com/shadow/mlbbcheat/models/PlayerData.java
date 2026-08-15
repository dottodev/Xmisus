package com.shadow.mlbbcheat.models;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class PlayerData {

    public final int id;
    public final boolean isEnemy;
    public final float x;
    public final float y;
    public final float hp;

    public PlayerData(int id, boolean isEnemy, float x, float y, float hp) {
        this.id = id;
        this.isEnemy = isEnemy;
        this.x = x;
        this.y = y;
        this.hp = hp;
    }

    public boolean isAlive() {
        return hp > 0f;
    }

    public float distanceTo(float px, float py) {
        float dx = x - px;
        float dy = y - py;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    public static PlayerData fromBytes(byte[] data) {
        if (data == null || data.length < 14) {
            return new PlayerData(-1, false, 0f, 0f, 0f);
        }
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int id = data[0] & 0xFF;
        boolean isEnemy = data[1] != 0;
        float x = buf.getFloat(2);
        float y = buf.getFloat(6);
        float hp = buf.getFloat(10);
        return new PlayerData(id, isEnemy, x, y, hp);
    }
}
