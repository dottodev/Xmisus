package com.shadow.mlbbcheat.models;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Extended player snapshot for the ESP renderer.
 *
 * Core fields (x, y, hp) come from the 0x01 base frame; the 0x05 extended
 * frame adds level, mana, hero id, team slot, spell cooldowns and status
 * flags. Extended frames MERGE into existing players by id (no duplicates),
 * and stale players age out so the overlay never draws ghosts.
 */
public class PlayerData {

    public static final int HERO_UNKNOWN = 0;

    public final int id;
    public final boolean isEnemy;
    public final float x;
    public final float y;
    public final float hp;

    // extended state (defaults = unknown)
    public volatile int level = 1;
    public volatile float manaRatio = -1f;      // -1 = unknown
    public volatile int heroId = HERO_UNKNOWN;
    public volatile int team = -1;              // -1 = unknown
    public volatile boolean ultReady = false;
    public volatile boolean recalling = false;
    public volatile boolean visible = true;     // 1 = assumed visible until told otherwise
    public volatile float spell1Cd = 0f;        // seconds remaining (0 = ready/unknown)
    public volatile float spell2Cd = 0f;
    public volatile long lastSeen = System.currentTimeMillis();

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

    /** Fresh if seen within the given window (anti-ghost). */
    public boolean isFresh(long nowMs, long windowMs) {
        return nowMs - lastSeen <= windowMs;
    }

    // ------------------------------------------------------------------
    // Frame decoding
    // ------------------------------------------------------------------

    /**
     * Decode a 0x01 base frame: type(1) isEnemy(1) x(4) y(4) hp(4) + 3 reserved.
     * Returns null if the id is invalid.
     */
    public static PlayerData fromBytes(byte[] data) {
        if (data == null || data.length < 17) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int id = data[1] & 0xFF;
        if (id >= 0xFF) return null;
        boolean isEnemy = data[2] != 0;
        float x = buf.getFloat(3);
        float y = buf.getFloat(7);
        float hp = buf.getFloat(11);
        return new PlayerData(id, isEnemy, x, y, hp);
    }

    /**
     * Decode a 0x05 extended frame (17 bytes):
     *   0: type, 1: id, 2: isEnemy, 3: level, 4: mana ratio (0-255),
     *   5: heroId, 6: flags (b0 visible, b1 ultReady, b2 recalling),
     *   7: team slot, 8-13: spell1 cd (f32) + spell2 cd (f32),
     *   14: next-key slot (reserved), 15-16: reserved
     */
    public static PlayerData fromExtendedBytes(byte[] data) {
        if (data == null || data.length < 17 || data[0] != 0x05) {
            return null;
        }
        int id = data[1] & 0xFF;
        if (id >= 0xFF) return null;
        boolean isEnemy = data[2] != 0;
        int level = data[3] & 0xFF;
        float mana = (data[4] & 0xFF) / 255f;
        int hero = data[5] & 0xFF;
        byte flags = data[6];
        int team = data[7] & 0xFF;
        if (team >= 0xFF) team = -1;

        PlayerData p = new PlayerData(id, isEnemy, 0f, 0f, 0f);
        p.level = level;
        p.manaRatio = mana;
        p.heroId = hero;
        p.team = team;
        p.visible = (flags & 0x01) != 0;
        p.ultReady = (flags & 0x02) != 0;
        p.recalling = (flags & 0x04) != 0;

        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        p.spell1Cd = b.getFloat(8);
        p.spell2Cd = b.getFloat(12);
        return p;
    }

    /**
     * Merge extended state into an existing player (keeps fresh position).
     */
    public void mergeExtended(PlayerData ext) {
        this.level = ext.level;
        this.manaRatio = ext.manaRatio;
        this.heroId = ext.heroId;
        this.team = ext.team;
        this.visible = ext.visible;
        this.ultReady = ext.ultReady;
        this.recalling = ext.recalling;
        this.spell1Cd = ext.spell1Cd;
        this.spell2Ext(ext.spell2Cd);
        this.lastSeen = System.currentTimeMillis();
    }

    private void spell2Ext(float v) {
        this.spell2Cd = v;
    }

    /** Touch the staleness timestamp (position updates). */
    public void touchSeen() {
        this.lastSeen = System.currentTimeMillis();
    }
}