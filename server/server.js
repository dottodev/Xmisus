/**
 * MLBB Cheat — control server + admin site + key management
 *
 * Zero-dependency Node.js (>= 22.5, uses built-in node:sqlite).
 *
 * Serves:
 *   POST /validate   — app key validation (tier + expiry + one-device binding)
 *   POST /activate   — legacy alias of /validate
 *   POST /heartbeat  — app heartbeat: kill switch + live offset DB + device stats
 *   GET  /           — admin site (server/public/admin.html)
 *   /api/*           — admin JSON API (password-protected)
 *
 * App protocol: request bodies are AES-256-GCM encrypted (key derived from
 * device id via SHA-256, mirroring the Android Crypto class), prefixed with
 * a 32-byte HMAC-SHA256 signature. Replies use the same envelope.
 *
 * Admin login: password = ADMIN_PASSWORD env var. If not set, a random one
 * is generated and printed on first start.
 *
 * Database: server/data.db (SQLite, created automatically).
 * Key formats: XXXX-XXXX-XXXX-XXXX (hex). Duration chosen by admin
 * (minutes/hours/days/months or permanent). Tier: free or premium.
 *
 * RUN:  node server/server.js
 * ENV:  PORT=8080 HOST=0.0.0.0 ADMIN_PASSWORD=...
 */

'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const db = require('./db.js');

const PORT = parseInt(process.env.PORT || '8080', 10);
const HOST = process.env.HOST || '0.0.0.0';
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || generatePassword();

const PUBLIC_DIR = path.join(__dirname, 'public');
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
};

function generatePassword() {
  return crypto.randomBytes(4).toString('hex'); // 8 chars
}

// ---------------------------------------------------------------------
// Crypto envelope (mirror of app's Crypto.java)
// ---------------------------------------------------------------------

function deviceKeys(deviceId) {
  const seed = crypto.createHash('sha256').update(String(deviceId)).digest();
  return {
    aes: seed.subarray(0, 32),
    hmac: seed.subarray(32, 64),
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

function hmac(keys, data) {
  return crypto.createHmac('sha256', keys.hmac).update(data).digest();
}

// ---------------------------------------------------------------------
// Sessions (admin)
// ---------------------------------------------------------------------

const sessions = new Map(); // token -> expiry ts

function createSession() {
  const token = crypto.randomBytes(32).toString('hex');
  sessions.set(token, Date.now() + 24 * 3600 * 1000);
  return token;
}

function validSession(req) {
  const cookie = parseCookies(req).auth;
  if (!cookie) return false;
  const exp = sessions.get(cookie);
  if (!exp) return false;
  if (exp < Date.now()) {
    sessions.delete(cookie);
    return false;
  }
  return true;
}

function parseCookies(req) {
  const out = {};
  const raw = req.headers.cookie || '';
  for (const part of raw.split(';')) {
    const i = part.indexOf('=');
    if (i > 0) out[part.slice(0, i).trim()] = part.slice(i + 1).trim();
  }
  return out;
}

// ---------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

function sendJson(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
  });
  res.end(body);
}

function sendText(res, code, text, type) {
  res.writeHead(code, { 'Content-Type': type || 'text/plain; charset=utf-8' });
  res.end(text);
}

// ---------------------------------------------------------------------
// App endpoints (encrypted envelope)
// ---------------------------------------------------------------------

function appEnvelope(keys, plain) {
  const enc = encrypt(keys, plain);
  return Buffer.concat([hmac(keys, enc), enc]);
}

function validateKey(body, deviceId) {
  const key = String(body.key || '').trim().toUpperCase();
  const row = db.getKey(key);

  if (!row || row.revoked) {
    return { ok: false, msg: 'invalid key' };
  }
  if (db.keyExpired(row)) {
    return { ok: false, msg: 'key expired' };
  }
  if (row.used_by && row.used_by !== deviceId) {
    return { ok: false, msg: 'key already in use on another device' };
  }

  db.bindKey(row.id, deviceId);
  return {
    ok: true,
    tier: row.tier || 'premium',
    expiry: row.expiry_ts || db.PERMANENT_EXPIRY,
    msg: 'activated',
  };
}

function heartbeat(body, deviceId) {
  const kill = db.isKillSwitchOn();
  const reply = { kill, ts: Date.now() };

  const tier = db.deviceTier(deviceId);
  db.upsertDevice(deviceId, body.version, body.mlbb, tier);
  reply.tier = tier;

  const offsets = db.getOffsets();
  if (offsets.versions && offsets.versions.length > 0) {
    reply.config = offsets;
  }
  return reply;
}

// ---------------------------------------------------------------------
// Admin endpoints
// ---------------------------------------------------------------------

function genKey() {
  const bytes = crypto.randomBytes(8).toString('hex').toUpperCase();
  return bytes.replace(/(....)/g, '$1-').slice(0, -1);
}

function adminCreateKeys(body) {
  const count = Math.min(Math.max(parseInt(body.count || '1', 10) || 1, 1), 50);
  const tier = body.tier === 'free' ? 'free' : 'premium';
  const unit = body.unit; // m | h | d | mo | perm
  const amount = parseInt(body.amount || '0', 10) || 0;

  let expiry = 0; // permanent
  if (unit !== 'perm') {
    const mult = { m: 60000, h: 3600000, d: 86400000, mo: 30 * 86400000 }[unit] || 3600000;
    expiry = Date.now() + amount * mult;
  }

  const created = [];
  for (let i = 0; i < count; i++) {
    let key;
    do {
      key = genKey();
    } while (db.getKey(key));
    db.insertKey(key, tier, expiry);
    created.push({ key, tier, expiry });
  }
  return { ok: true, created };
}

// ---------------------------------------------------------------------
// HTTP routing
// ---------------------------------------------------------------------

function handleStatic(res, pathname) {
  let file = pathname === '/' ? 'index.html' : pathname.slice(1);
  const full = path.resolve(PUBLIC_DIR, file);
  if (!full.startsWith(PUBLIC_DIR)) {
    res.writeHead(403);
    res.end();
    return;
  }
  fs.readFile(full, (err, data) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('not found');
      return;
    }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(full)] || 'application/octet-stream' });
    res.end(data);
  });
}

async function handleAdmin(req, res, url) {
  const p = url.pathname;

  if (p === '/api/login' && req.method === 'POST') {
    const body = JSON.parse((await readBody(req)).toString('utf8') || '{}');
    if (body.password === ADMIN_PASSWORD) {
      const token = createSession();
      res.writeHead(200, {
        'Content-Type': 'application/json',
        'Set-Cookie': `auth=${token}; Path=/; HttpOnly; SameSite=Lax; Max-Age=86400`,
      });
      res.end(JSON.stringify({ ok: true }));
      return;
    }
    return sendJson(res, 401, { ok: false, msg: 'wrong password' });
  }

  if (!validSession(req)) {
    return sendJson(res, 401, { ok: false, msg: 'unauthorized' });
  }

  if (p === '/api/stats' && req.method === 'GET') {
    return sendJson(res, 200, db.stats());
  }

  if (p === '/api/keys' && req.method === 'GET') {
    const now = Date.now();
    const rows = db.listKeys().map((k) => ({
      id: k.id,
      key: k.key,
      tier: k.tier,
      expiry_ts: k.expiry_ts,
      permanent: k.expiry_ts === 0,
      expired: k.expiry_ts > 0 && k.expiry_ts < now,
      used_by: k.used_by,
      revoked: !!k.revoked,
      created_at: k.created_at,
    }));
    return sendJson(res, 200, { ok: true, keys: rows });
  }

  if (p === '/api/keys' && req.method === 'POST') {
    const body = JSON.parse((await readBody(req)).toString('utf8') || '{}');
    try {
      return sendJson(res, 200, adminCreateKeys(body));
    } catch (e) {
      return sendJson(res, 400, { ok: false, msg: e.message });
    }
  }

  if (p.startsWith('/api/keys/')) {
    const seg = p.split('/');
    const id = parseInt(seg[3], 10);
    const action = seg[4];

    if (action === 'revoke' && req.method === 'POST') {
      db.revokeKey(id);
      return sendJson(res, 200, { ok: true });
    }
    if (action === 'delete' && req.method === 'POST') {
      db.deleteKey(id);
      return sendJson(res, 200, { ok: true });
    }
  }

  if (p === '/api/kill' && req.method === 'POST') {
    const body = JSON.parse((await readBody(req)).toString('utf8') || '{}');
    db.setKillSwitch(!!body.kill);
    return sendJson(res, 200, { ok: true, kill: db.isKillSwitchOn() });
  }

  if (p === '/api/devices' && req.method === 'GET') {
    return sendJson(res, 200, { ok: true, devices: db.listDevices() });
  }

  if (p === '/api/offsets' && req.method === 'GET') {
    return sendJson(res, 200, { ok: true, offsets: db.getOffsets() });
  }

  if (p === '/api/offsets' && req.method === 'POST') {
    const body = JSON.parse((await readBody(req)).toString('utf8') || '{}');
    try {
      db.setOffsets(JSON.stringify(body.offsets));
      return sendJson(res, 200, { ok: true });
    } catch (e) {
      return sendJson(res, 400, { ok: false, msg: 'invalid JSON: ' + e.message });
    }
  }

  return sendJson(res, 404, { ok: false, msg: 'no such admin route' });
}

async function handle(req, res) {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const p = url.pathname;

  if (p === '/validate' || p === '/activate' || p === '/heartbeat') {
    if (req.method !== 'POST') {
      res.writeHead(405);
      res.end();
      return;
    }
    const raw = await readBody(req);
    if (raw.length < 32) {
      res.writeHead(400);
      res.end();
      return;
    }
    const sig = raw.subarray(0, 32);
    const encrypted = raw.subarray(32);
    const deviceId = String(req.headers['x-dev'] || '');
    const keys = deviceKeys(deviceId);
    const expected = hmac(keys, encrypted);

    let ok = false;
    try {
      ok = crypto.timingSafeEqual(expected, sig);
    } catch (e) {
      ok = false;
    }
    if (!ok) {
      res.writeHead(401);
      res.end();
      return;
    }

    const plain = decrypt(keys, encrypted);
    if (plain === null) {
      res.writeHead(400);
      res.end();
      return;
    }

    let body;
    try {
      body = JSON.parse(plain);
    } catch (e) {
      res.writeHead(400);
      res.end();
      return;
    }

    const reply = p === '/heartbeat'
      ? heartbeat(body, deviceId)
      : validateKey(body, deviceId);

    res.writeHead(200, { 'Content-Type': 'application/octet-stream' });
    res.end(appEnvelope(keys, JSON.stringify(reply)));
    return;
  }

  if (p === '/api/' || p.startsWith('/api/')) {
    return handleAdmin(req, res, url);
  }

  return handleStatic(res, p);
}

// ---------------------------------------------------------------------

if (require.main === module) {
  const srv = http.createServer(handle).listen(PORT, HOST, () => {
    console.log(`[mlbb-cheat-server] listening on http://${HOST}:${PORT}`);
    console.log(`  admin site:  http://${HOST === '0.0.0.0' ? 'localhost' : HOST}:${PORT}/admin.html`);
    console.log(`  admin pass:  ${ADMIN_PASSWORD}   (set ADMIN_PASSWORD env to change)`);
    console.log(`  database:    ${path.join(__dirname, 'data.db')}`);
  });
  const shutdown = () => srv.close(() => process.exit(0));
  process.on('SIGTERM', shutdown);
  process.on('SIGINT', shutdown);
  process.stdin.on('end', shutdown); // graceful stop when stdin closes (test harness)
}

module.exports = { handle, deviceKeys, encrypt, decrypt, hmac, appEnvelope, ADMIN_PASSWORD };