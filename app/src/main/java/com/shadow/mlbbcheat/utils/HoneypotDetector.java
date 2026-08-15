package com.shadow.mlbbcheat.utils;

import com.shadow.mlbbcheat.models.PlayerData;

import java.util.List;

/**
 * Detects "honeypot" game states — situations where the server is feeding
 * us fake data to expose memory readers.
 *
 * Real MLBB matches are noisy: HP fluctuates, positions jitter, players
 * respawn, creep counts vary. A honeypot stream is suspiciously stable:
 * perfectly identical HP, zero movement variance, all enemies exactly
 * equally spaced, teleporting coordinates, or every entity at the exact
 * same position. When one is detected the engine throttles features back
 * to a human-safe profile instead of burning the account.
 */
public final class HoneypotDetector {

    private float lastHpSum = -1f;
    private float lastPositionsSum = -1f;
    private int stableFrames = 0;
    private int teleportFrames = 0;
    private final float[] lastPositions = new float[10];
    private int positionIndex = 0;

    public static final class Verdict {
        public final boolean suspicious;
        public final String reason;
        Verdict(boolean suspicious, String reason) {
            this.suspicious = suspicious;
            this.reason = reason;
        }
    }

    public synchronized Verdict assess(List<PlayerData> players) {
        if (players == null || players.isEmpty()) {
            return new Verdict(false, "no data");
        }

        // 1. All identical HP at exact same value → fake stream
        float hp0 = players.get(0).hp;
        boolean identicalHp = true;
        for (PlayerData p : players) {
            if (Math.abs(p.hp - hp0) > 0.001f) { identicalHp = false; break; }
        }
        if (identicalHp && players.size() > 1) {
            return new Verdict(true, "identical HP across entities");
        }

        // 2. Everything at the same coordinates → fake stream
        float x0 = players.get(0).x, y0 = players.get(0).y;
        boolean stacked = true;
        for (PlayerData p : players) {
            if (Math.abs(p.x - x0) > 0.01f || Math.abs(p.y - y0) > 0.01f) {
                stacked = false;
                break;
            }
        }
        if (stacked && players.size() > 1) {
            return new Verdict(true, "all entities stacked");
        }

        // 3. Zero movement across frames → frozen/fake
        float posSum = 0f;
        for (PlayerData p : players) posSum += p.x + p.y;
        if (lastPositionsSum >= 0f && Math.abs(posSum - lastPositionsSum) < 0.0001f
                && players.size() > 1) {
            stableFrames++;
            if (stableFrames > 15) {
                return new Verdict(true, "frozen positions");
            }
        } else {
            stableFrames = 0;
        }
        lastPositionsSum = posSum;

        // 4. Teleporting entity (moves > 50k units in one poll while
        //    everything else stands still)
        if (lastPositions[positionIndex] != 0f) {
            float dx = players.get(0).x - lastPositions[positionIndex];
            if (dx > 50000f) {
                teleportFrames++;
                if (teleportFrames > 3) {
                    teleportFrames = 0;
                    return new Verdict(true, "teleporting entity");
                }
            } else {
                teleportFrames = 0;
            }
        }
        if (!players.isEmpty()) {
            lastPositions[positionIndex] = players.get(0).x;
            positionIndex = (positionIndex + 1) % lastPositions.length;
        }

        return new Verdict(false, "clean");
    }

    /** Reset when a new match starts (frame type CLEAR received). */
    public synchronized void reset() {
        lastHpSum = -1f;
        lastPositionsSum = -1f;
        stableFrames = 0;
        teleportFrames = 0;
        lastPositions[0] = 0f;
        positionIndex = 0;
    }
}
