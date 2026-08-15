package com.shadow.mlbbcheat.aim;

import com.shadow.mlbbcheat.models.PlayerData;
import com.shadow.mlbbcheat.utils.BehaviorMimic;

import java.util.List;

/**
 * Targeting + aim mathematics.
 *
 * Responsibilities:
 *  - pick the best target (killable low-HP, closest, in range)
 *  - lead moving targets (projectile travel time × velocity)
 *  - convert world coordinates to screen coordinates given camera params
 *  - add human error BEFORE the accessibility service dispatches the drag
 *
 * Pure math — no Android dependencies — so the whole engine is unit-testable
 * and can also be reused by the Lua side via mirrored formulas.
 */
public final class AimEngine {

    public static final float MAX_AIM_RANGE_WORLD = 2500f;
    public static final float DEFAULT_PROJECTILE_SPEED = 1200f;
    public static final float DEFAULT_ZOOM = 2.0f;

    private AimEngine() {}

    /** Candidate for a skill shot. */
    public static final class Target {
        public final PlayerData player;
        public final float distanceWorld;
        public final float screenX;
        public final float screenY;
        public final boolean killable;

        Target(PlayerData player, float distanceWorld, float screenX,
               float screenY, boolean killable) {
            this.player = player;
            this.distanceWorld = distanceWorld;
            this.screenX = screenX;
            this.screenY = screenY;
            this.killable = killable;
        }
    }

    /**
     * Select the best target:
     *  1. any enemy in range that our next skill/auto could kill (hp <= threshold)
     *  2. else the closest living enemy in range
     * Returns null when nothing is in range.
     */
    public static Target selectTarget(List<PlayerData> players,
                                      float px, float py,
                                      float zoom,
                                      float damagePerHit,
                                      float skillRangeWorld) {
        if (players == null) return null;

        Target bestKill = null;
        Target bestClose = null;
        float bestKillDist = Float.MAX_VALUE;
        float bestCloseDist = Float.MAX_VALUE;

        for (PlayerData p : players) {
            if (!p.isEnemy || !p.isAlive()) continue;
            float d = p.distanceTo(px, py);
            float range = Math.min(skillRangeWorld, MAX_AIM_RANGE_WORLD);
            if (d > range) continue;

            float sx = p.x * zoom;
            float sy = p.y * zoom;
            boolean killable = damagePerHit > 0f && p.hp <= damagePerHit * 1.1f;

            Target t = new Target(p, d, sx, sy, killable);
            if (killable && d < bestKillDist) {
                bestKill = t;
                bestKillDist = d;
            }
            if (d < bestCloseDist) {
                bestClose = t;
                bestCloseDist = d;
            }
        }
        return bestKill != null ? bestKill : bestClose;
    }

    /**
     * Lead a moving target for projectile skills.
     * @param worldToScreenScale pixels per world unit at current zoom
     * @param projectileSpeed projectile velocity in world units/sec
     */
    public static float[] leadTarget(PlayerData target,
                                     float[] targetVelocityWorld,
                                     float worldToScreenScale,
                                     float projectileSpeed) {
        float dist = (float) Math.sqrt(target.x * target.x + target.y * target.y);
        float flightTime = dist / Math.max(projectileSpeed, 1f);
        float leadX = (target.x + targetVelocityWorld[0] * flightTime) * worldToScreenScale;
        float leadY = (target.y + targetVelocityWorld[1] * flightTime) * worldToScreenScale;
        return new float[]{leadX, leadY};
    }

    /** World→screen with simple orthographic projection at a given zoom. */
    public static float[] project(float worldX, float worldY, float zoom) {
        return new float[]{worldX * zoom, worldY * zoom};
    }

    /** Compute the pixel-space drag the joystick needs to aim skill N at (sx,sy). */
    public static float[] skillDragVector(float fromX, float fromY,
                                          float sx, float sy,
                                          float aimStickRadiusPx) {
        float dx = sx - fromX;
        float dy = sy - fromY;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f) return new float[]{0f, 0f};
        float scale = aimStickRadiusPx / len;
        float aimX = dx * scale;
        float aimY = dy * scale;
        // human error after scaling (error is in aim-space, not world-space)
        aimX += BehaviorMimic.aimErrorPx(len);
        aimY += BehaviorMimic.aimErrorPx(len);
        return new float[]{aimX, aimY};
    }

    /** Effective skill range on screen at a zoom (for UI/ranges). */
    public static float skillRangePx(float skillRangeWorld, float zoom) {
        return skillRangeWorld * zoom;
    }
}
