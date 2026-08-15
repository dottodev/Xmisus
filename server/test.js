/**
 * End-to-end smoke test for the control server.
 * Spawns server.js on a test port with a temp DB, then:
 *   1. admin login + create keys (premium 1 day, free permanent)
 *   2. app /validate over the real encrypted envelope (device A → ok, device B → blocked)
 *   3. /heartbeat → kill switch, tier, offset config
 *   4. kill switch ON via admin → heartbeat reports kill
 *
 * RUN:  node server/test.js
 */

'use strict';

const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');
const os = require('os');

const PORT = 18080;
const BASE = `http://127.0.0.1:${PORT}`;
const DB = path.join(os.tmpdir(), `mlbb-test-${Date.now()}.db`);
const ADMIN_PASSWORD = 'test123';

// same crypto as server.js + app
function deviceKeys(deviceId) {
  const seed = crypto.createHash('sha256').update(String(deviceId)).digest();
  return { aes: seed.subarray(0, 32), hmac: seed.subarray(32, 64) };
}
function encrypt(keys, plaintext) {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', keys.aes, iv);
  const body = Buffer.concat([cipher.update(plaintext, 'utf8'), cipher.final()]);
  return Buffer.concat([iv, body, cipher.getAuthTag()]);
}
function decrypt(keys, data) {
  const iv = data.subarray(0, 12);
  const body = data.subarray(12, data.length - 16);
  const tag = data.subarray(data.length - 16);
  const d = crypto.createDecipheriv('aes-256-gcm', keys.aes, iv);
  d.setAuthTag(tag);
  return Buffer.concat([d.update(body), d.final()]).toString('utf8');
}
function hmac(keys, data) {
  return crypto.createHmac('sha256', keys.hmac).update(data).digest();
}

let failures = 0;
function check(name, cond, extra) {
  if (cond) {
    console.log(`  ok: ${name}`);
  } else {
    failures++;
    console.log(`  FAIL: ${name}${extra ? ' — ' + extra : ''}`);
  }
}

async function appPost(pathname, deviceId, bodyObj) {
  const keys = deviceKeys(deviceId);
  const plain = JSON.stringify(bodyObj);
  const enc = encrypt(keys, plain);
  const r = await fetch(BASE + pathname, {
    method: 'POST',
    headers: { 'Content-Type': 'application/octet-stream', 'X-Dev': deviceId },
    body: Buffer.concat([hmac(keys, enc), enc]),
  });
  const raw = Buffer.from(await r.arrayBuffer());
  if (raw.length < 32) throw new Error(`bad envelope (http ${r.status})`);
  const body = raw.subarray(32);
  const sig = raw.subarray(0, 32);
  if (!crypto.timingSafeEqual(hmac(keys, body), sig)) throw new Error('bad reply hmac');
  return JSON.parse(decrypt(keys, body));
}

async function main() {
  const server = spawn(process.execPath, [path.join(__dirname, 'server.js')], {
    env: {
      ...process.env,
      PORT: String(PORT),
      HOST: '127.0.0.1',
      DB_PATH: DB,
      ADMIN_PASSWORD,
    },
    stdio: ['pipe', 'ignore', 'ignore'],
  });

  // wait for listen
  for (let i = 0; i < 50; i++) {
    try {
      await fetch(`${BASE}/`);
      break;
    } catch (e) {
      await new Promise((r) => setTimeout(r, 200));
    }
  }

  try {
    // --- admin: login ---
    const login = await fetch(`${BASE}/api/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ password: ADMIN_PASSWORD }),
    });
    const setCookie = login.headers.get('set-cookie') || '';
    check('admin login works', login.status === 200);
    const badLogin = await fetch(`${BASE}/api/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ password: 'nope' }),
    });
    check('admin login rejects wrong password', badLogin.status === 401);

    const cookie = setCookie.split(';')[0];
    const authed = { 'Content-Type': 'application/json', Cookie: cookie };

    // --- admin: create premium 1-day key ---
    let j = await (await fetch(`${BASE}/api/keys`, {
      method: 'POST', headers: authed,
      body: JSON.stringify({ tier: 'premium', unit: 'd', amount: 1, count: 1 }),
    })).json();
    check('create premium 1-day key', j.ok === true && j.created.length === 1);
    const premiumKey = j.created[0].key;
    check('key format is dashed hex', /^[A-F0-9]{4}(-[A-F0-9]{4}){3}$/.test(premiumKey));
    const premiumExpiry = j.created[0].expiry;

    // --- admin: create free permanent key ---
    j = await (await fetch(`${BASE}/api/keys`, {
      method: 'POST', headers: authed,
      body: JSON.stringify({ tier: 'free', unit: 'perm', amount: 0, count: 2 }),
    })).json();
    check('create 2 free permanent keys', j.ok === true && j.created.length === 2);
    const freeKey = j.created[0].key;

    // --- app: validate premium key from device A ---
    let v = await appPost('/validate', 'device-A', { key: premiumKey, ts: Date.now() });
    check('validate premium key ok', v.ok === true);
    check('validate returns premium tier', v.tier === 'premium');
    check('validate returns 1-day expiry', Math.abs(v.expiry - premiumExpiry) < 1000,
      `got ${v.expiry}`);

    // --- app: same key on device B → blocked (one key = one device) ---
    v = await appPost('/validate', 'device-B', { key: premiumKey, ts: Date.now() });
    check('same key blocked on second device', v.ok === false);

    // --- app: validate free key ---
    v = await appPost('/validate', 'device-A', { key: freeKey, ts: Date.now() });
    check('validate free key ok', v.ok === true && v.tier === 'free');

    // --- app: bogus key rejected ---
    v = await appPost('/validate', 'device-A', { key: 'BEEF-BEEF-BEEF-BEEF', ts: Date.now() });
    check('bogus key rejected', v.ok === false);

    // --- app: heartbeat reports tier + config ---
    const hb = await appPost('/heartbeat', 'device-A', { version: '1.1.0', mlbb: 'mlbb-1.8.0' });
    check('heartbeat kill off by default', hb.kill === false);
    check('heartbeat reports premium tier', hb.tier === 'premium');
    check('heartbeat delivers offset config', hb.config && Array.isArray(hb.config.versions));

    // --- admin: devices list ---
    j = await (await fetch(`${BASE}/api/devices`, { headers: authed })).json();
    check('device A registered in devices list',
      j.devices.some((d) => d.device_id === 'device-A' && d.tier === 'premium'));

    // --- admin: kill switch ON → heartbeat kill ---
    await fetch(`${BASE}/api/kill`, {
      method: 'POST', headers: authed, body: JSON.stringify({ kill: true }),
    });
    const hbKill = await appPost('/heartbeat', 'device-A', { version: '1.1.0' });
    check('kill switch propagates to heartbeat', hbKill.kill === true);
    await fetch(`${BASE}/api/kill`, {
      method: 'POST', headers: authed, body: JSON.stringify({ kill: false }),
    });

    // --- admin: revoke premium key → validation fails ---
    j = await (await fetch(`${BASE}/api/keys`, { headers: authed })).json();
    const row = j.keys.find((k) => k.key === premiumKey);
    await fetch(`${BASE}/api/keys/${row.id}/revoke`, {
      method: 'POST', headers: authed, body: '{}',
    });
    v = await appPost('/validate', 'device-A', { key: premiumKey, ts: Date.now() });
    check('revoked key rejected', v.ok === false);

    // --- admin: stats ---
    j = await (await fetch(`${BASE}/api/stats`, { headers: authed })).json();
    check('stats endpoint reports key counts', j.keys === 3 && j.used >= 1, JSON.stringify(j));

    // --- unauthenticated admin access blocked ---
    const anon = await fetch(`${BASE}/api/keys`);
    check('admin API blocks anonymous access', anon.status === 401);
  } finally {
    server.stdin.end(); // graceful shutdown
    await new Promise((resolve) => {
      const timer = setTimeout(() => server.kill(), 3000);
      server.on('exit', () => {
        clearTimeout(timer);
        resolve();
      });
    });
    fs.rmSync(DB, { force: true, maxRetries: 5, retryDelay: 200 });
  }

  console.log(failures === 0 ? '\nALL TESTS PASSED' : `\n${failures} TEST(S) FAILED`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('test crashed:', e);
  process.exit(1);
});