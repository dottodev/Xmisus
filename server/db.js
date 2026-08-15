'use strict';

/**
 * SQLite data layer for the MLBB cheat control server.
 * Uses Node's built-in `node:sqlite` (Node >= 22.5) — no npm dependencies.
 */

const { DatabaseSync } = require('node:sqlite');
const path = require('path');

const DB_PATH = process.env.DB_PATH || path.join(__dirname, 'data.db');

const db = new DatabaseSync(DB_PATH);

db.exec(`
  CREATE TABLE IF NOT EXISTS keys (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    key        TEXT UNIQUE NOT NULL,
    tier       TEXT NOT NULL DEFAULT 'premium',
    expiry_ts  INTEGER NOT NULL DEFAULT 0,
    used_by    TEXT,
    created_at INTEGER NOT NULL,
    revoked    INTEGER NOT NULL DEFAULT 0
  );

  CREATE TABLE IF NOT EXISTS devices (
    device_id TEXT PRIMARY KEY,
    last_seen INTEGER NOT NULL,
    version   TEXT,
    mlbb      TEXT,
    tier      TEXT DEFAULT 'free'
  );

  CREATE TABLE IF NOT EXISTS settings (
    k TEXT PRIMARY KEY,
    v TEXT
  );
`);

// ---------------------------------------------------------------------
// Settings helpers
// ---------------------------------------------------------------------

function getSetting(k, fallback) {
  const row = db.prepare('SELECT v FROM settings WHERE k = ?').get(k);
  return row ? row.v : fallback;
}

function setSetting(k, v) {
  db.prepare(
    'INSERT INTO settings (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v'
  ).run(k, v);
}

// ---------------------------------------------------------------------
// Keys
// ---------------------------------------------------------------------

const PERMANENT_EXPIRY = Number.MAX_SAFE_INTEGER;

function insertKey(key, tier, expiryTs) {
  db.prepare(
    'INSERT INTO keys (key, tier, expiry_ts, created_at) VALUES (?, ?, ?, ?)'
  ).run(key, tier, expiryTs, Date.now());
}

function getKey(key) {
  return db.prepare('SELECT * FROM keys WHERE key = ?').get(key);
}

function listKeys() {
  return db.prepare('SELECT * FROM keys ORDER BY id DESC').all();
}

function bindKey(id, deviceId) {
  db.prepare('UPDATE keys SET used_by = ? WHERE id = ?').run(deviceId, id);
}

function revokeKey(id) {
  db.prepare('UPDATE keys SET revoked = 1 WHERE id = ?').run(id);
}

function deleteKey(id) {
  db.prepare('DELETE FROM keys WHERE id = ?').run(id);
}

function keyExpired(row) {
  return row.expiry_ts > 0 && row.expiry_ts < Date.now();
}

// ---------------------------------------------------------------------
// Devices
// ---------------------------------------------------------------------

function upsertDevice(deviceId, version, mlbb, tier) {
  db.prepare(
    `INSERT INTO devices (device_id, last_seen, version, mlbb, tier)
     VALUES (?, ?, ?, ?, ?)
     ON CONFLICT(device_id) DO UPDATE SET
       last_seen = excluded.last_seen,
       version   = excluded.version,
       mlbb      = excluded.mlbb,
       tier      = excluded.tier`
  ).run(deviceId, Date.now(), version || '', mlbb || '', tier || 'free');
}

/** Best tier among the device's live bound keys (premium wins). */
function deviceTier(deviceId) {
  let tier = 'free';
  for (const k of listKeys()) {
    if (k.used_by === deviceId && !k.revoked && !keyExpired(k)) {
      if (k.tier === 'premium') return 'premium';
      tier = k.tier;
    }
  }
  return tier;
}

function listDevices() {
  return db.prepare('SELECT * FROM devices ORDER BY last_seen DESC').all();
}

// ---------------------------------------------------------------------
// Kill switch
// ---------------------------------------------------------------------

function isKillSwitchOn() {
  return getSetting('kill', '0') === '1';
}

function setKillSwitch(on) {
  setSetting('kill', on ? '1' : '0');
}

// ---------------------------------------------------------------------
// Offset DB (served to every heartbeat; hot-editable via admin)
// ---------------------------------------------------------------------

function getOffsets() {
  const raw = getSetting('offsets', null);
  if (raw) {
    try {
      return JSON.parse(raw);
    } catch (e) {
      return { versions: [] };
    }
  }
  // seed from bundled file if it exists (first run after migrating)
  const fs = require('fs');
  try {
    const bundled = fs.readFileSync(path.join(__dirname, 'offset_db.json'), 'utf8');
    const parsed = JSON.parse(bundled);
    setSetting('offsets', bundled);
    return parsed;
  } catch (e) {
    return { versions: [] };
  }
}

function setOffsets(jsonString) {
  JSON.parse(jsonString); // throw if invalid
  setSetting('offsets', jsonString);
}

// ---------------------------------------------------------------------
// Counters (admin dashboard)
// ---------------------------------------------------------------------

function stats() {
  const keys = db.prepare('SELECT COUNT(*) c FROM keys').get().c;
  const used = db.prepare('SELECT COUNT(*) c FROM keys WHERE used_by IS NOT NULL').get().c;
  const premium = db.prepare("SELECT COUNT(*) c FROM keys WHERE tier = 'premium'").get().c;
  const devices = db.prepare('SELECT COUNT(*) c FROM devices').get().c;
  const kill = isKillSwitchOn();
  return { keys, used, premium, devices, kill };
}

module.exports = {
  PERMANENT_EXPIRY,
  insertKey,
  getKey,
  listKeys,
  bindKey,
  revokeKey,
  deleteKey,
  keyExpired,
  upsertDevice,
  deviceTier,
  listDevices,
  isKillSwitchOn,
  setKillSwitch,
  getOffsets,
  setOffsets,
  stats,
};