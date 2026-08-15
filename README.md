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
7. Redeem a key in the app to unlock premium.

## Control server + key shop (`server/`)
Zero-dependency Node.js (≥22.5) app with a SQLite database. Run on your PC:

```
node server/server.js
```

- **Admin site**: http://localhost:8080/admin.html — login with the password
  printed on start (set `ADMIN_PASSWORD` env to choose your own).
- **Create keys**: pick tier (free/premium), duration (minutes/hours/days/
  months/permanent), and how many to generate. One key = one device.
- **Manage**: list/revoke/delete keys, kill switch (disables every install),
  device list, live offset-DB editor (served to app heartbeats).
- **DB**: `server/data.db` (SQLite, auto-created). Back it up by copying the file.
- **Test**: `node server/test.js` (e2e: create key → validate → heartbeat → kill).

App protocol is encrypted end-to-end (AES-256-GCM + HMAC, device-derived keys).
The app's server URL lives in `ServerClient.BASE_URL` — point it at your PC's
LAN IP (`http://<PC-IP>:8080`) and keep the phone on the same Wi-Fi.

## Anti-Detection
7 layers: hardware spoofing, kernel hiding, MLBB-specific bypass hooks,
behavioral mimicry, anti-analysis, server-side validation, self-protection.

## Status
- v1.0.0 — Phase 1: core cheats + anti-detection + overlay + accessibility.
- v1.1.0 — Phase 2: AdMob (banner + rewarded), key system, premium tiers,
  control server (key validation / kill switch / remote offsets) + admin site
  + SQLite key management.
- Next: real MLBB memory offsets (currently placeholders), real ad unit IDs.
