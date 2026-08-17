# Xmisus v1.3.0 — GUI v3 + Enemy Lag Module

Date: 2026-08-17
Status: approved by user (2026-08-17)

## Goals

1. Overlay GUI v3: medium size, all modules visible, per-module settings,
   minimize/reopen fixed.
2. Remove auto-retri and the accessibility permission ask.
3. New Enemy Lag cheat module: 2000+ lines, bypass-woven, driven through the
   existing GameGuardian/Lua bridge.

## Context (architecture reminder)

- MLBB and Xmisus run inside a parallel app (e.g. Parallel Space).
- Lua script `assets/scripts/mlbb_cheat.lua` runs inside GameGuardian in the
  same container and pushes 17-byte rolling-XOR frames over loopback TCP
  48123 to the app (`DataReceiver`).
- `offset_db.json` is an all-zero placeholder; real offset values arrive
  later. All modules must stay defensive when offsets are invalid.
- Today the app has NO command channel to Lua (drone/aim are hardcoded in
  the script's CONFIG table).

## Design

### 1. GUI v3 (WidgetManager rewrite)

- Panel ~240dp wide (medium), header "XMISUS" + minimize (–), drag anywhere.
- Chips: ESP, DRONE, AIM, SAFE, LAG (auto-retri removed).
- Tap chip = toggle on/off (persisted). Each chip has a settings (⚙) row:
  - ESP: view distance (50-500m, default 300)
  - DRONE: camera zoom (1000-9000, default 3000)
  - AIM: drag sensitivity (0.5-2.0, default 1.0)
  - LAG: intensity (1-10, default 5) + mode (STUTTER/FREEZE/RUBBER, default STUTTER)
  - SAFE: no settings (forces stealth: no boxes, no vibration)
- Panel position persisted (SharedPreferences).
- Bug fixes:
  - pill click swallowed by drag listener → call `performClick()` on
    ACTION_UP when not dragging (pill and panel);
  - pill position must be synced from the panel position before minimizing
    (no jump on reopen).

### 2. Accessibility / auto-retri removal

- `AutoRetriService`: strip all retribution logic (constants, retri damage
  model, scheduleRetriTap, retri frame usage). Class becomes aim-only
  (kept for aim-assist gestures if the user enables accessibility manually).
- `MainActivity.gatePermissions()`: request ONLY overlay permission after
  app open; remove accessibility ask and toasts.
- Remove WidgetManager RETRI chip and OverlayService retri wiring.

### 3. Enemy Lag module

`app/src/main/java/com/shadow/mlbbcheat/utils/bypass/EnemyLag.java`
(2000+ lines) — bypass-woven lag controller:

- State machine: IDLE → ARMING → ACTIVE (with phase jitter) → COOLDOWN.
- Intensity ramp: fade in over randomized steps (no instant behavior jump).
- Modes: STUTTER (periodic stale-write bursts), FREEZE (hold stale values),
  RUBBER (alternating small deltas around stale values).
- Offset-validity gate: refuses to act while `enemy_base`/`player_size` are
  placeholder or zero (mirrors UpdateGuard semantics).
- Honeypot auto-suspend (HoneypotDetector verdict → throttle/abort).
- Self-cleansing: on stop/abort/kill-switch, restores original values.
- Pacing via BehaviorMimic; session budget; kill-switch aware;
  watchdog-observation friendly (never touches process identity).
- Public API (stable signatures): configure/start/stop/tick/isActive/
  intensity/mode; wired into `BypassStack` (e.g. `lag()` hooks, `tick()`
  integration, `hardStop` cooperation).

### 4. App → Lua command channel

- `DataReceiver`: keep the accepted Lua socket open (full-duplex);
  `sendCommand(byte[])` writes plaintext command frames.
- Command frame (17 bytes, NOT obfuscated, byte0 = 0xE0):
  - byte1: cmd id — 1=LAG SET, 2=LAG STOP, 3=LAG MODE
  - float @3: value (intensity 1-10 / mode enum)
  - float @7: duration ms (0 = until stopped)
  - float @11: seed (randomized per command)
- Lua side: each poll reads pending command frames; LAG engine applies
  per-target stutter/freeze/rubber writes to `enemy_base + i*player_size +
  x/y offset` with randomized intervals, dummy writes, and restore-on-stop.
  Applies only when offsets are valid (enemy_base > 0).

### 5. OverlayService wiring

- `onToggle("safe", on)`: forces `overlayView.setStealthMode(true)` +
  suppress vibration; off → revert to honeypot-driven stealth.
- `onToggle("lag", on)`: start/stop EnemyLag via BypassStack.
- Settings sliders apply via BypassStack → EnemyLag / AdvancedEsp /
  camera-zoom command / AimEngine sensitivity.

### 6. Tests

- New `EnemyLagTest`: state machine transitions, ramp bounds, frame builder
  bytes, restore-on-stop, offset-validity gate, honeypot suspend.
- Existing 129 tests stay green (retri tests in AimEngine untouched;
  AutoRetriService unit tests removed if they cover retri only).

### 7. Shipping

- versionCode 6, versionName "1.3.0".
- Run testDebugUnitTest, assembleDebug+Release, copy to
  `dist/Xmisus-v1.3.0-*.apk`, commit, push origin main, GitHub release v1.3.0.
