# MLBB Cheat

Zero-config MLBB cheat app for Android (runs alongside MLBB inside Parallel Space).

## Features
- ESP (enemy boxes + HP) — free
- Map Hack (minimap dots) — free
- Enemy Alert (vibration) — free
- Auto Retribution — free
- Drone View (camera zoom) — premium
- Auto Aim (accessibility gestures) — premium

## Tiers
- **Free**: ESP, map hack, enemy alert, auto-retri, banner ads.
- **Premium**: unlocks drone view + auto aim. Get it via an activation key
  (redeem in-app; server-validated, one key = one device) or by watching a
  rewarded ad (+1h).

## Build
`gradlew.bat assembleRelease`

## Install & Use
1. Install the APK.
2. Open the app once (copies `mlbb_cheat.lua` to `GameGuardian/scripts/`).
3. Grant overlay permission when prompted.
4. Enable the accessibility service in system settings.
5. Open MLBB inside Parallel Space, then start GameGuardian and run the script.
6. The floating widget toggles features.
7. Redeem a key in the app to unlock premium (requires the control server).

## Control Server
`server/server.js` — zero-dependency Node.js control server:
- `POST /validate` — validates user keys (tier + expiry, one device per key)
- `POST /heartbeat` — kill switch + live offset DB delivery
- Encrypted request/reply envelope (AES-256-GCM + HMAC, device-derived keys)

Run: `node server/server.js` (edit `ServerClient.BASE_URL` in the app to point at it).
Keys live in `server/keys.json`; flip `server/kill.json` to `{"kill":true}` to kill all installs.

## Anti-Detection
7 layers: hardware spoofing, kernel hiding, MLBB-specific bypass hooks,
behavioral mimicry, anti-analysis, server-side validation, self-protection.

## Status
- v1.0.0 — Phase 1: core cheats + anti-detection + overlay + accessibility.
- v1.1.0 — Phase 2: AdMob (banner + rewarded), key system, premium tiers,
  control server (key validation / kill switch / remote offsets).
- Next: real MLBB memory offsets (currently placeholders), AdMob real ad
  unit IDs, hosted control server.
