package com.shadow.mlbbcheat.memory;

import java.util.HashMap;
import java.util.Map;

/**
 * Memory offsets for Mobile Legends: Bang Bang.
 *
 * Offsets are version-specific. The active set is resolved at runtime via
 * {@link OffsetRepository} using the installed MLBB version fingerprint.
 *
 * IMPORTANT: The values below are STRUCTURAL PLACEHOLDERS. They must be
 * replaced with real offsets discovered from a memory dump of the current
 * MLBB APK (find via GameGuardian scan + pointer search). The repository
 * supports hot-swapping the whole set from the server, so shipping a
 * placeholder default is intentional: the server delivers real offsets.
 */
public final class GameOffsets {

    private GameOffsets() {}

    /** Frame layout constants shared with the Lua bridge. */
    public static final int FRAME_SIZE = 17;
    public static final int FRAME_TYPE_PLAYER = 0x01;
    public static final int FRAME_TYPE_LEVEL = 0x02;
    public static final int FRAME_TYPE_DRONE = 0x03;
    public static final int FRAME_TYPE_CLEAR = 0x04;
    public static final int FRAME_TYPE_AIM_TARGET = 0x05;
    public static final int FRAME_TYPE_PING = 0x06;

    public static final int RETRI_SKILL_SLOT = 3;
    public static final int MAX_ENEMIES = 5;

    /** A complete, self-contained offset set for one MLBB version. */
    public static final class OffsetSet {
        public final String version;
        public final long enemyBase;
        public final int playerSize;
        public final int playerXOff;
        public final int playerYOff;
        public final int playerHpOff;
        public final int playerManaOff;
        public final int playerTeamOff;
        public final int playerLevelOff;
        public final long cameraZoomAddr;
        public final long cameraPitchAddr;
        public final long cameraYawAddr;
        public final long minimapOriginXAddr;
        public final long minimapOriginYAddr;
        public final long minimapScaleAddr;
        public final long gameStateAddr;
        public final int aiMoveSpeedAddr;
        public final int retriCdAddr;

        public OffsetSet(String version,
                         long enemyBase, int playerSize,
                         int playerXOff, int playerYOff,
                         int playerHpOff, int playerManaOff,
                         int playerTeamOff, int playerLevelOff,
                         long cameraZoomAddr, long cameraPitchAddr, long cameraYawAddr,
                         long minimapOriginXAddr, long minimapOriginYAddr, long minimapScaleAddr,
                         long gameStateAddr, int aiMoveSpeedAddr, int retriCdAddr) {
            this.version = version;
            this.enemyBase = enemyBase;
            this.playerSize = playerSize;
            this.playerXOff = playerXOff;
            this.playerYOff = playerYOff;
            this.playerHpOff = playerHpOff;
            this.playerManaOff = playerManaOff;
            this.playerTeamOff = playerTeamOff;
            this.playerLevelOff = playerLevelOff;
            this.cameraZoomAddr = cameraZoomAddr;
            this.cameraPitchAddr = cameraPitchAddr;
            this.cameraYawAddr = cameraYawAddr;
            this.minimapOriginXAddr = minimapOriginXAddr;
            this.minimapOriginYAddr = minimapOriginYAddr;
            this.minimapScaleAddr = minimapScaleAddr;
            this.gameStateAddr = gameStateAddr;
            this.aiMoveSpeedAddr = aiMoveSpeedAddr;
            this.retriCdAddr = retriCdAddr;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new HashMap<>();
            m.put("version", version);
            m.put("enemy_base", enemyBase);
            m.put("player_size", playerSize);
            m.put("player_x_off", playerXOff);
            m.put("player_y_off", playerYOff);
            m.put("player_hp_off", playerHpOff);
            m.put("player_mana_off", playerManaOff);
            m.put("player_team_off", playerTeamOff);
            m.put("player_level_off", playerLevelOff);
            m.put("camera_zoom_addr", cameraZoomAddr);
            m.put("camera_pitch_addr", cameraPitchAddr);
            m.put("camera_yaw_addr", cameraYawAddr);
            m.put("minimap_origin_x_addr", minimapOriginXAddr);
            m.put("minimap_origin_y_addr", minimapOriginYAddr);
            m.put("minimap_scale_addr", minimapScaleAddr);
            m.put("game_state_addr", gameStateAddr);
            m.put("ai_move_speed_addr", aiMoveSpeedAddr);
            m.put("retri_cd_addr", retriCdAddr);
            return m;
        }
    }

    /** Default placeholder set — replaced at runtime from the offset DB. */
    public static OffsetSet getPlaceholder() {
        return new OffsetSet(
                "unknown",
                0x12345678L, 0x400,
                0x100, 0x104,
                0x200, 0x204,
                0x208, 0x20C,
                0x12349000L, 0x12349004L, 0x12349008L,
                0x1234A000L, 0x1234A004L, 0x1234A008L,
                0x1234B000L, 0x300, 0x304);
    }
}
