-- mlbb_cheat.lua - MLBB memory reader for MLBB Cheat app
-- Runs inside GameGuardian. Sends data to app socket on port 48123.

local CONFIG = {
    ESP = true,
    MAP_HACK = true,
    DRONE_VIEW = false,
    AUTO_AIM = false
}

local ENEMY_BASE = 0x12345678
local PLAYER_SIZE = 0x400
local PLAYER_X_OFF = 0x100
local PLAYER_Y_OFF = 0x104
local PLAYER_HP_OFF = 0x200
local CAMERA_ZOOM_ADDR = 0x12349000
local MAX_ENEMIES = 5
local PORT = 48123

local socket = require("socket")
local client

local function randomDelay()
    local d = math.random(50, 200)
    gg.sleep(d)
end

local function connect()
    if client then return true end
    local ok = pcall(function()
        client = socket.tcp()
        client:settimeout(1)
        client:connect("127.0.0.1", PORT)
    end)
    return ok
end

local function sendFrame(frame)
    if not connect() then return end
    pcall(function() client:send(frame) end)
end

local function packFloat(v)
    return string.pack("<f", v)
end

local function readEnemyFrame(i)
    local addr = ENEMY_BASE + (i * PLAYER_SIZE)
    local vals = gg.getValues({
        {address = addr + PLAYER_X_OFF},
        {address = addr + PLAYER_Y_OFF},
        {address = addr + PLAYER_HP_OFF}
    })
    local x = vals[1].value
    local y = vals[2].value
    local hp = vals[3].value
    if x == 0 and y == 0 then return nil end

    return string.char(0x01, 0x01)
        .. packFloat(x) .. packFloat(y) .. packFloat(hp)
        .. string.char(0, 0, 0)
end

local function readLevelFrame()
    local level = gg.getValues({{address = ENEMY_BASE + 0x300}})[1].value
    return string.char(0x02, 0x00) .. packFloat(level)
        .. string.char(0, 0, 0, 0, 0, 0, 0, 0)
end

local function sendDroneState()
    local on = CONFIG.DRONE_VIEW and 1 or 0
    local frame = string.char(0x03, 0x00, on)
        .. string.char(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    sendFrame(frame)
end

while true do
    if CONFIG.ESP or CONFIG.MAP_HACK then
        for i = 0, MAX_ENEMIES - 1 do
            local frame = readEnemyFrame(i)
            if frame then sendFrame(frame) end
        end
    end
    sendFrame(readLevelFrame())
    if CONFIG.DRONE_VIEW then
        gg.setValues({{address = CAMERA_ZOOM_ADDR, value = 3000}})
    end
    sendDroneState()
    randomDelay()
end
