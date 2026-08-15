/**
 * MLBB Cheat — control server (reference implementation)
 *
 * Zero-dependency Node.js server for the cheat's control channel.
 * Implements:
 *   POST /validate   — user key validation (tier + expiry)
 *   POST /heartbeat  — device heartbeat (kill switch + offset DB delivery)
 *   POST /activate   — legacy key activation (same as /validate)
 *
 * Protocol: every request body is AES-256-GCM encrypted (key derived from
 * device id via SHA-256, same as the Android Crypto class), prefixed with a
 * 32-byte HMAC-SHA256 signature. Replies use the same envelope.
 *
 * Key store: server/keys.json
 *   {
 *     "keys": {
 *       "A1B2-C3D4-E5F6-0718": { "tier": "premium", "expiry": 0, "usedBy": null },
 *       "A1B2-C3D4-E5F6-1A2B": { "tier": "premium", "expiry": 1767225600000 }
 *     }
 *   }
 *   expiry: 0 = permanent. When a key is redeemed, usedBy is set to the
 *   device id so it cannot be shared (one key = one device).
 *
 * Offset DB: server/offset_db.json — same schema as the app's bundled file;
 * served in every heartbeat response so hot updates need no app release.
 *
 * Kill switch: server/kill.json { "kill": true } — set true to instantly
 * disable every install that heartbeats.
 *
 * RUN:  node server/server.js   (listens on :8080 by default)
 * ENV:  PORT=8080, HOST=0.0.0.0
 */

'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PORT = parseInt(process.env.PORT || '8080', 10);
const HOST = process.env.HOST || '0.0.0.0';

const DATA_DIR = __dirname;

function loadJson(name, fallback) {
  try {
    const raw = fs.readFileSync(path.join(DATA_DIR, name), 'utf8');
    return JSON.parse(raw);
  } catch (e) {
    return fallback;
  }
}

function saveJson(name, data) {
  fs.writeFileSync(path.join(DATA_DIR, name), JSON.stringify(data, null, 2));
}

// ---------------------------------------------------------------------
// Crypto envelope (mirror of app's Crypto.java)
// ---------------------------------------------------------------------

function deviceKeys(deviceId) {
  const seed = crypto.createHash('sha256').update(deviceId).digest();
  return {
    aes: seed.subarray(0, 32),   // AES-256-GCM key
    hmac: seed.subarray(32, 64)  // HMAC-SHA256 key
  };
}

function encrypt(keys, plaintext) {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', keys.aes, iv);
  const body = Buffer.concat([cipher.update(plaintext, 'utf8'), cipher.final()]);
  return Buffer.concat([iv, body, cipher.getAuthTag()]);
}

function decrypt(keys, data) {
  try {
    const iv = data.subarray(0, 12);
    const body = data.subarray(12, data.length - 16);
    const tag = data.subarray(data.length - 16);
    const decipher = crypto.createDecipheriv('aes-256-gcm', keys.aes, iv);
    decipher.setAuthTag(tag);
    return Buffer.concat([decipher.update(body), decipher.final()]).toString('utf8');
  } catch (e) {
    return null;
  }
}

// ---------------------------------------------------------------------
// Request handling
// ---------------------------------------------------------------------

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

async function handle(req, res) {
  res.setHeader('Content-Type', 'application/octet-stream');
  res.setHeader('Access-Control-Allow-Origin', '*');

  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const pathname = url.pathname;

  if (req.method !== 'POST' && pathname !== '/') {
    res.statusCode = 405;
    res.end();
    return;
  }
  if (pathname === '/') {
    res.setHeader('Content-Type', 'application/json');
    res.end(JSON.stringify({ name: 'mlbb-cheat-server', ok: true }));
    return;
  }

  const raw = await readBody(req);
  if (raw.length < 32) {
    res.statusCode = 400;
    res.end();
    return;
  }

  const sig = raw.subarray(0, 32);
  const encrypted = raw.subarray(32);
  const deviceId = (req.headers['x-dev'] || '').toString();
  const keys = deviceKeys(deviceId);

  // verify HMAC before touching the ciphertext
  const expected = crypto.createHmac('sha256', keys.hmac).update(encrypted).digest();
  if (!crypto.timingSafeEqual(expected, sig)) {
    res.statusCode = 401;
    res.end();
    return;
  }

  const plain = decrypt(keys, encrypted);
  if (plain === null) {
    res.statusCode = 400;
    res.end();
    return;
  }

  let body;
  try {
    body = JSON.parse(plain);
  } catch (e) {
    res.statusCode = 400;
    res.end();
    return;
  }

  let reply;
  if (pathname === '/validate' || pathname === '/activate') {
    reply = validateKey(body, deviceId);
  } else if (pathname === '/heartbeat') {
    reply = heartbeat(body, deviceId);
  } else {
    res.statusCode = 404;
    res.end();
    return;
  }

  const replyPlain = JSON.stringify(reply);
  const replyEnc = encrypt(keys, replyPlain);
  res.end(Buffer.concat([crypto.createHmac('sha256', keys.hmac).update(replyEnc).digest(), replyEnc]));
}

// ---------------------------------------------------------------------
// Endpoint logic
// ---------------------------------------------------------------------

function validateKey(body, deviceId) {
  const db = loadJson('keys.json', { keys: {} });
  const key = (body.key || '').trim().toUpperCase();

  if (!db.keys[key]) {
    return { ok: false, msg: 'invalid key' };
  }
  const entry = db.keys[key];

  // one key = one device
  if (entry.usedBy && entry.usedBy !== deviceId) {
    return { ok: false, msg: 'key already in use on another device' };
  }

  // expiry check
  const now = Date.now();
  if (entry.expiry > 0 && entry.expiry < now) {
    return { ok: false, msg: 'key expired' };
  }

  entry.usedBy = deviceId;
  db.keys[key] = entry;
  saveJson('keys.json', db);

  return {
    ok: true,
    tier: entry.tier || 'premium',
    expiry: entry.expiry || Number.MAX_SAFE_INTEGER,
    msg: 'activated'
  };
}

function heartbeat(body, deviceId) {
  const kill = loadJson('kill.json', { kill: false });
  const offsetDb = loadJson('offset_db.json', { versions: [] });

  const reply = { kill: !!kill.kill, ts: Date.now() };
  if (offsetDb.versions && offsetDb.versions.length > 0) {
    reply.config = offsetDb;
  }
  return reply;
}

http.createServer(handle).listen(PORT, HOST, () => {
  console.log(`[mlbb-cheat-server] listening on http://${HOST}:${PORT}`);
  console.log('  endpoints: /validate  /activate  /heartbeat');
  const sample = loadJson('keys.json', null);
  if (!sample) {
    saveJson('keys.json', {
      keys: {
        'A1B2-C3D4-E5F6-0718': { tier: 'premium', expiry: 0, usedBy: null }
      }
    });
    console.log('  created keys.json with a sample permanent premium key: A1B2-C3D4-E5F6-0718');
  }
  if (!fs.existsSync(path.join(DATA_DIR, 'kill.json'))) {
    saveJson('kill.json', { kill: false });
  }
  console.log('  key store:   ' + path.join(DATA_DIR, 'keys.json'));
  console.log('  kill switch: ' + path.join(DATA_DIR, 'kill.json'));
});