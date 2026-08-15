# MLBB Cheat

Zero-config MLBB cheat app for Android (runs alongside MLBB inside Parallel Space).

## Features
- ESP (enemy boxes + HP)
- Map Hack (minimap dots)
- Drone View (camera zoom)
- Auto Retribution
- Enemy Alert (vibration)
- Auto Aim (accessibility gestures)

## Build
`gradlew.bat assembleRelease`

## Install & Use
1. Install the APK.
2. Open the app once (copies `mlbb_cheat.lua` to `GameGuardian/scripts/`).
3. Grant overlay permission when prompted.
4. Enable the accessibility service in system settings.
5. Open MLBB inside Parallel Space, then start GameGuardian and run the script.
6. The floating widget toggles features.

## Anti-Detection
7 layers: hardware spoofing, kernel hiding, MLBB-specific bypass hooks,
behavioral mimicry, anti-analysis, server-side validation (Phase 2), self-protection.

## Phase 2
- AdMob integration
- Server-side offset updates
- User accounts
