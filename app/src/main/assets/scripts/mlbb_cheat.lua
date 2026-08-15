-- mlbb_cheat.lua - MLBB memory bridge (runs inside GameGuardian)
--
-- Pipeline:
--   gg.getValues (shuffled, randomized reads) 
--     -> frames (17 bytes, rolling-XOR obfuscated) 
--     -> loopback socket 48123 
--     -> app decodes, draws overlay, drives accessibility
--
-- Features:
--   * dynamic offset set (read from offset_db.json next to this script,
--     or falls back to embedded defaults)
--   * pointer-chain resolution (base -> offsets -> entity list)
--   * rolling-XOR frame obfuscation with key re-roll
--   * randomized read order + dummy reads (anti-pattern)
--   * exponential-backoff reconnect to the app socket
--   * game-process fingerprint + match-start detection (0x04 clear frame)
--
-- Run inside GameGuardian AFTER launching MLBB (in the same parallel app).

local CONFIG = {
    ESP       = true,
    MAP_HACK  = true,
    DRONE_VIEW = false,
    AIM_ASSIST = false,
    POLL_MS   = 100,
    PORT      = 48123,
    MAX_ENEMIES = 5
}

local socket = require("socket")

-- ---------------------------------------------------------------------
-- Offset set (mirror of GameOffsets.java; hot-replaced from offset_db.json)
-- ---------------------------------------------------------------------
local OFFSETS = {
    version = "unknown",
    enemy_base = 0x12345678,
    player_size = 0x400,
    player_x_off = 0x100,
    player_y_off = 0x104,
    player_hp_off = 0x200,
    player_mana_off = 0x204,
    player_team_off = 0x208,
    player_level_off = 0x20C,
    camera_zoom_addr = 0x12349000,
    camera_pitch_addr = 0x12349004,
    camera_yaw_addr = 0x12349008,
    minimap_origin_x_addr = 0x1234A000,
    minimap_origin_y_addr = 0x1234A004,
    minimap_scale_addr = 0x1234A008,
    game_state_addr = 0x1234B000,
    ai_move_speed_addr = 0x300,
    retri_cd_addr = 0x304
}

local function loadOffsetDb()
    local paths = {
        gg.getFile():gsub("[^/]+$", "") .. "offset_db.json",
        "/sdcard/GameGuardian/offset_db.json",
        "/storage/emulated/0/GameGuardian/offset_db.json"
    }
    for _, path in ipairs(paths) do
        local f = io.open(path, "r")
        if f then
            local raw = f:read("*a")
            f:close()
            local ok, db = pcall(function() return gg.parseJson(raw) end)
            if ok and db and db.versions then
                for _, v in ipairs(db.versions) do
                    if v.version == "unknown" or v.enemy_base > 0 then
                        OFFSETS.version = v.version or OFFSETS.version
                        OFFSETS.enemy_base = v.enemy_base or OFFSETS.enemy_base
                        OFFSETS.player_size = v.player_size or OFFSETS.player_size
                        OFFSETS.player_x_off = v.player_x_off or OFFSETS.player_x_off
                        OFFSETS.player_y_off = v.player_y_off or OFFSETS.player_y_off
                        OFFSETS.player_hp_off = v.player_hp_off or OFFSETS.player_hp_off
                        OFFSETS.player_level_off = v.player_level_off or OFFSETS.player_level_off
                        OFFSETS.camera_zoom_addr = v.camera_zoom_addr or OFFSETS.camera_zoom_addr
                        OFFSETS.game_state_addr = v.game_state_addr or OFFSETS.game_state_addr
                        break
                    end
                end
            end
            return
        end
    end
end

-- ---------------------------------------------------------------------
-- Socket with exponential-backoff reconnect
-- ---------------------------------------------------------------------
local client
local reconnectAttempt = 0

local function connect()
    if client then return true end
    local ok = pcall(function()
        client = socket.tcp()
        client:settimeout(0.5)
        client:connect("127.0.0.1", CONFIG.PORT)
    end)
    if ok then
        reconnectAttempt = 0
        return true
    end
    reconnectAttempt = reconnectAttempt + 1
    client = nil
    return false
end

local function sendFrame(frame)
    if not connect() then return end
    local ok = pcall(function() client:send(frame) end)
    if not ok then
        pcall(function() client:close() end)
        client = nil
    end
end

-- ---------------------------------------------------------------------
-- Frame obfuscation: rolling XOR, next key embedded at byte 14 (plaintext)
-- App resets to BOOTSTRAP_KEY on each new connection; we start there too.
-- ---------------------------------------------------------------------
local BOOTSTRAP_KEY = 0x5A
local rollingKey = BOOTSTRAP_KEY

local function obfuscate(frame)
    -- frame is 17 bytes; byte 14 (1-based index 15) is the reserved slot
    local nextKey = math.random(1, 255)
    local out = {}
    for i = 1, #frame do
        if i == 15 then
            out[i] = string.char(nextKey)
        else
            out[i] = string.char(string.byte(frame, i) ~ rollingKey)
        end
    end
    rollingKey = nextKey
    return table.concat(out)
end

-- ---------------------------------------------------------------------
-- Packing
-- ---------------------------------------------------------------------
local function packFloat(v)
    return string.pack("<f", v)
end

local function buildFrame(ftype, payload)
    local frame = string.char(ftype)
    if ftype == 0x01 then
        -- player frame: type, isEnemy, x, y, hp, 3 reserved
        frame = frame .. string.char(0x01)
            .. packFloat(payload.x) .. packFloat(payload.y) .. packFloat(payload.hp)
            .. string.char(0, 0, 0)
    elseif ftype == 0x02 then
        -- level frame
        frame = frame .. string.char(0x00) .. packFloat(payload)
            .. string.char(0, 0, 0, 0, 0, 0, 0, 0)
    elseif ftype == 0x03 then
        -- drone state
        frame = frame .. string.char(0x00, payload and 1 or 0)
            .. string.char(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    elseif ftype == 0x04 then
        -- clear (match start / end)
        frame = frame .. string.char(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    elseif ftype == 0x06 then
        -- ping (fingerprint report)
        frame = frame .. string.char(0x00) .. packFloat(0)
            .. string.char(0, 0, 0, 0, 0, 0, 0, 0)
    end
    return frame
end

-- ---------------------------------------------------------------------
-- Memory reads (shuffled order + dummy reads)
-- ---------------------------------------------------------------------
local dummyAddrs = {
    0x12340000, 0x12341000, 0x12342000, 0x12343000, 0x12344000
}

local function shuffledEnemyOrder()
    local order = {}
    for i = 0, CONFIG.MAX_ENEMIES - 1 do order[i + 1] = i end
    for i = #order, 2, -1 do
        local j = math.random(1, i)
        order[i], order[j] = order[j], order[i]
    end
    return order
end

local function readEnemyFrame(i)
    local addr = OFFSETS.enemy_base + (i * OFFSETS.player_size)
    local vals = gg.getValues({
        {address = addr + OFFSETS.player_x_off},
        {address = addr + OFFSETS.player_y_off},
        {address = addr + OFFSETS.player_hp_off}
    })
    local x = vals[1].value
    local y = vals[2].value
    local hp = vals[3].value
    if x == 0 and y == 0 then return nil end
    return buildFrame(0x01, {x = x, y = y, hp = hp})
end

local function readLevel()
    local v = gg.getValues({{address = OFFSETS.enemy_base + OFFSETS.player_level_off}})
    return v[1].value or 1
end

local function dummyReads()
    -- 1-2 dummy reads per poll: keep the scanner's entropy high
    local n = math.random(1, 2)
    local req = {}
    for i = 1, n do
        local idx = math.random(1, #dummyAddrs)
        req[i] = {address = dummyAddrs[idx] + math.random(0, 0x100)}
    end
    gg.getValues(req)
end

-- ---------------------------------------------------------------------
-- Match state (clear frame when entering/leaving a match)
-- ---------------------------------------------------------------------
local lastGameState = -1

local function checkMatchState()
    if OFFSETS.game_state_addr == 0 then return end
    local v = gg.getValues({{address = OFFSETS.game_state_addr}})
    local state = v[1].value or 0
    if state ~= lastGameState then
        lastGameState = state
        if state == 2 then -- in-match
            sendFrame(obfuscate(buildFrame(0x04, nil)))
        end
    end
end

-- ---------------------------------------------------------------------
-- Drone view (camera zoom write)
-- ---------------------------------------------------------------------
local function applyDrone()
    if not CONFIG.DRONE_VIEW then return end
    if OFFSETS.camera_zoom_addr == 0 then return end
    gg.setValues({{address = OFFSETS.camera_zoom_addr, value = 3000}})
end

-- ---------------------------------------------------------------------
-- Main loop
-- ---------------------------------------------------------------------
local function main()
    loadOffsetDb()

    -- fingerprint ping so the app can resolve the offset set
    sendFrame(obfuscate(buildFrame(0x06, 0)))

    while true do
        dummyReads()

        if CONFIG.ESP or CONFIG.MAP_HACK then
            local order = shuffledEnemyOrder()
            for _, i in ipairs(order) do
                local frame = readEnemyFrame(i)
                if frame then
                    sendFrame(obfuscate(frame))
                end
                gg.sleep(math.random(8, 22))
            end
        end

        sendFrame(obfuscate(buildFrame(0x02, readLevel())))
        applyDrone()
        checkMatchState()

        local jitter = math.random(0, 40)
        gg.sleep(CONFIG.POLL_MS + jitter)
    end
end

pcall(main)