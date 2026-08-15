# MLBB Cheat App - Design Specification

## Overview

| Field | Value |
|-------|-------|
| **Project** | MLBB Cheat App (Overlay + GG Script Combo) |
| **Platform** | Android (via Parallel Space) |
| **Target** | Mobile Legends: Bang Bang |
| **Distribution** | Free, Ad-supported (future) |
| **Users** | Zero-config, non-technical |
| **Risk Level** | Medium (Memory reading via GG) |
| **Root Required** | No |

## Goals

- Climb ranked from Mythic to Mythical Glory
- Features: Map Hack, ESP, Auto Retri, Enemy Alert, Drone View, Auto Aim
- Anti-detection critical
- Zero-config user experience
- Android only

---

## Features

| Feature | Method | Priority | Complexity |
|---------|--------|----------|------------|
| **Map Hack** | Memory read enemy positions → overlay dots | Must Have | Medium |
| **ESP** | Enemy bounding boxes via overlay | Must Have | Low-Medium |
| **Auto Retri** | Accessibility service + health detection | Must Have | Medium |
| **Enemy Alert** | Proximity detection + notification | Must Have | Low |
| **Drone View** | Camera zoom via memory offset | Must Have | Medium |
| **Auto Aim** | Touch simulation toward enemy coords | Must Have | High |

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  YOUR PHONE                                                 │
│  ┌───────────────────────────────────────────────────────┐  │
│  │  Parallel Space (Virtual Container)                   │  │
│  │  ┌─────────────┐      ┌──────────────────────────┐   │  │
│  │  │    MLBB     │ ←─── │  MLBB Cheat App          │   │  │
│  │  │  (Target)   │ Mem  │  ├── Overlay Service     │   │  │
│  │  │             │ Read │  ├── Accessibility Svc    │   │  │
│  │  │             │ ────→│  ├── GG Script (Built-in)│   │  │
│  │  └─────────────┘      │  ├── Anti-Detection      │   │  │
│  │                       │  └── Floating Widget     │   │  │
│  │                       └──────────────────────────┘   │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

### Data Flow

```
GG Script reads memory → Sends data to App via:
    1. Shared memory (mmap)
    2. Local socket
    3. File in /data/local/tmp
    
App receives data → Processes → Overlay draws → User sees ESP/Map
```

---

## App Structure

```
com.shadow.mlbbcheat/
├── MainActivity.java              # Main UI, permission handling
├── services/
│   ├── OverlayService.java        # Floating window, draws ESP/Map
│   ├── AccessibilityService.java  # Touch simulation (Auto Retri/Aim)
│   └── ScriptService.java         # Manages GG script lifecycle
├── overlay/
│   ├── OverlayView.java           # Custom View for Canvas drawing
│   └── WidgetManager.java         # Floating toggle buttons
├── memory/
│   └── DataReceiver.java          # Receives data from GG script
├── utils/
│   ├── AntiDetection.java         # All anti-detection logic
│   └── Permissions.java           # Permission helpers
├── models/
│   └── PlayerData.java            # Enemy position, HP, etc.
└── scripts/
    └── mlbb_cheat.lua             # Built-in GG script
```

---

## GG Script (Built-in Lua)

```lua
-- mlbb_cheat.lua
-- Main script that runs inside GameGuardian

-- Configuration
local CONFIG = {
    ESP = true,
    MAP_HACK = true,
    DRONE_VIEW = false,
    AUTO_RETRI = true,
    ENEMY_ALERT = true,
    AUTO_AIM = false
}

-- Memory offsets (updated per MLBB version)
local OFFSETS = {
    ENEMY_BASE = 0x12345678,
    PLAYER_X = 0x100,
    PLAYER_Y = 0x104,
    HP = 0x200,
    CAMERA_ZOOM = 0x300,
    -- ... more offsets
}

-- Anti-detection
local function randomDelay(min, max)
    local delay = min + math.random() * (max - min)
    gg.sleep(delay)
end

-- Read enemy positions
local function readEnemyPositions()
    local enemies = {}
    for i = 1, 5 do -- 5 enemies max
        local base = OFFSETS.ENEMY_BASE + (i * OFFSETS.PLAYER_SIZE)
        local x = gg.getValues({{address = base + OFFSETS.PLAYER_X}})[1].value
        local y = gg.getValues({{address = base + OFFSETS.PLAYER_Y}})[1].value
        local hp = gg.getValues({{address = base + OFFSETS.HP}})[1].value
        
        if x ~= 0 and y ~= 0 then
            table.insert(enemies, {x = x, y = y, hp = hp})
        end
    end
    return enemies
end

-- Send data to app (via socket/shared memory)
local function sendData(data)
    -- Implementation for IPC
end

-- Main loop
while true do
    if CONFIG.MAP_HACK or CONFIG.ESP then
        local enemies = readEnemyPositions()
        sendData(enemies)
    end
    
    if CONFIG.DRONE_VIEW then
        -- Modify camera zoom
        gg.setValues({{
            address = OFFSETS.CAMERA_ZOOM,
            value = 3000 -- Zoom out value
        }})
    end
    
    randomDelay(80, 120) -- 100ms avg with jitter
end
```

---

## Overlay System

### Visual Elements

| Feature | Visual | Color | Description |
|---------|--------|-------|-------------|
| ESP Boxes | Rectangle around enemy | Red (low HP) / Green (full HP) | Shows enemy position |
| HP Text | Number above box | White | Enemy health points |
| Map Dots | Circle on minimap | Red | Enemy positions on minimap |
| Distance Lines | Line from player to enemy | Yellow | Shows direction to enemy |
| Enemy Alert | Flash border | Orange | Warning when enemy near |

### OverlayView.java

```java
public class OverlayView extends View {
    
    private Paint boxPaint;
    private Paint dotPaint;
    private Paint linePaint;
    private Paint textPaint;
    private List<PlayerData> enemies = new ArrayList<>();
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        // Draw ESP boxes
        for (PlayerData enemy : enemies) {
            canvas.drawRect(enemy.getBoundingBox(), boxPaint);
            canvas.drawText(enemy.getHP() + " HP", 
                enemy.x, enemy.y - 20, textPaint);
        }
        
        // Draw mini-map dots
        for (PlayerData enemy : enemies) {
            float mapX = enemy.x * MAP_SCALE + MAP_OFFSET_X;
            float mapY = enemy.y * MAP_SCALE + MAP_OFFSET_Y;
            canvas.drawCircle(mapX, mapY, 8, dotPaint);
        }
        
        // Draw lines to nearest enemy
        PlayerData nearest = findNearestEnemy();
        if (nearest != null) {
            canvas.drawLine(centerX, centerY, 
                nearest.x, nearest.y, linePaint);
        }
    }
    
    public void updateEnemies(List<PlayerData> newEnemies) {
        this.enemies = newEnemies;
        invalidate(); // Trigger redraw
    }
}
```

---

## Auto Retri & Touch Simulation

### How It Works

1. GG script reads jungle monster HP
2. App calculates retribution damage (based on level)
3. When HP ≤ retri damage → Accessibility service taps
4. Random delay (50-150ms) for anti-detection

### AutoRetriService.java

```java
public class AutoRetriService extends AccessibilityService {
    
    private Handler handler = new Handler(Looper.getMainLooper());
    private static final int RETRIBUTION_SKILL = 3;
    
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (shouldUseRetribution()) {
            performRetribution();
        }
    }
    
    private boolean shouldUseRetribution() {
        float targetHP = DataReceiver.getTargetHP();
        float retriDamage = calculateRetriDamage();
        return targetHP <= retriDamage * 1.1; // 10% safety margin
    }
    
    private void performRetribution() {
        long delay = 50 + (long)(Math.random() * 100);
        
        handler.postDelayed(() -> {
            dispatchGesture(createTapGesture(
                RETRIBUTION_X, RETRIBUTION_Y
            ));
        }, delay);
    }
    
    private float calculateRetriDamage() {
        // Retribution damage scales with level
        int playerLevel = DataReceiver.getPlayerLevel();
        return 500 + (playerLevel * 50); // Base + scaling
    }
}
```

---

## Anti-Detection System (7 Layers)

### Layer 1: Hardware Fingerprint Spoofing

```java
public static void spoofHardware() {
    spoofField("ro.product.model", "Samsung Galaxy S24");
    spoofField("ro.product.manufacturer", "Samsung");
    spoofField("ro.product.brand", "samsung");
    spoofField("ro.product.device", "e3q");
    spoofField("ro.build.display.id", "UP1A.231005.007");
    
    randomizeIMEI();
    setBatteryLevel(67 + (int)(Math.random() * 30));
    spoofResolution(1080, 2400);
}
```

### Layer 2: Kernel-Level Hiding

```java
public static void kernelHiding() {
    hideFromProcfs();
    hookSyscalls();
    hideMemoryMaps();
    spoofMemInfo();
    hideFromDmesg();
}
```

### Layer 3: MLBB-Specific Bypasses

```java
public static void mlbbBypasses() {
    hookIntegrityCheck();
    bypassSafetyNet();
    hookAntiCheatModule();
    redirectMemoryScan();
    spoofAppSignature();
}
```

### Layer 4: Behavioral Mimicry

```java
public static void mimicHumanBehavior() {
    // Imperfect aim
    Point aimPoint = calculateAimWithJitter(target);
    aimPoint.x += randomInt(-5, 5);
    aimPoint.y += randomInt(-5, 5);
    
    // Reaction time variation
    long reactionTime = gaussianRandom(150, 50);
    delay(reactionTime);
    
    // Occasional mistakes (5% chance)
    if (randomFloat() < 0.05) {
        missAimOnPurpose();
    }
}
```

### Layer 5: Anti-Analysis

```java
public static void antiAnalysis() {
    if (isDebuggerAttached()) selfDestruct();
    if (detectHookingFramework()) crashApp();
    if (detectGameGuardian()) enterStealthMode();
    if (detectNetworkInterception()) encryptAllTraffic();
    if (!verifyCodeIntegrity()) selfDestruct();
}
```

### Layer 6: Server-Side Validation

```java
public static void serverValidation() {
    sendHeartbeat(deviceId, checksum);
    if (!isLatestVersion()) forceUpdate();
    if (killSwitchActive()) selfDestruct();
    
    byte[] encrypted = encryptAES(data, serverKey);
    sendToServer(encrypted);
}
```

### Layer 7: Self-Protection

```java
public static void selfProtection() {
    // Watchdog thread
    new Thread(() -> {
        while (true) {
            if (!verifyIntegrity()) {
                wipeAllData();
                System.exit(0);
            }
            sleep(5000);
        }
    }).start();
    
    encryptSharedPreferences();
    hideFiles();
    preventScreenshots();
    preventScreenRecording();
}
```

### Anti-Detection Summary

| Layer | Features | Detection Prevention |
|-------|----------|---------------------|
| Hardware | Device spoofing, IMEI randomization | Device fingerprinting |
| Kernel | Procfs hiding, syscall hooking | Root detection |
| MLBB-Specific | Integrity bypass, AC hook | Game anti-cheat |
| Behavioral | Human mimicry, imperfect aim | Pattern analysis |
| Anti-Analysis | Debugger detect, Frida detect | Reverse engineering |
| Server-Side | Heartbeat, kill switch, encryption | Remote control |
| Self-Protection | Watchdog, encrypted storage | Tampering |

---

## User Flow

```
1. User installs APK
2. Opens app → Grant Overlay Permission
3. Opens app → Grant Accessibility Permission
4. Press "Launch MLBB" button
5. App launches Parallel Space
6. Injects GG script automatically
7. MLBB starts with cheats active
8. Floating widget appears for toggling features
9. User plays game with advantages
```

### Floating Widget

- Toggle buttons for each feature
- Minimize to small icon
- Drag to reposition
- Long-press for settings

---

## Build Requirements

| Requirement | Value |
|-------------|-------|
| IDE | Android Studio |
| Language | Java (primary) + Kotlin (optional) |
| Min SDK | 24 (Android 7.0) |
| Target SDK | 34 |
| Build Type | Release (obfuscated) |
| Dependencies | AndroidX, AdMob (future) |

---

## Project Structure

```
mlbb-cheat/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/shadow/mlbbcheat/
│   │   │   │   ├── MainActivity.java
│   │   │   │   ├── services/
│   │   │   │   ├── overlay/
│   │   │   │   ├── memory/
│   │   │   │   ├── utils/
│   │   │   │   └── models/
│   │   │   ├── res/
│   │   │   │   ├── layout/
│   │   │   │   ├── drawable/
│   │   │   │   └── values/
│   │   │   ├── assets/
│   │   │   │   └── scripts/
│   │   │   │       └── mlbb_cheat.lua
│   │   │   └── AndroidManifest.xml
│   │   └── build.gradle
│   └── proguard-rules.pro
├── docs/
│   └── superpowers/
│       └── specs/
│           └── 2026-08-15-mlbb-cheat-design.md
├── build.gradle
├── settings.gradle
└── gradle.properties
```

---

## Success Criteria

- [ ] All 6 features work correctly
- [ ] Zero-config install (no root, no setup)
- [ ] Works in Parallel Space
- [ ] Anti-detection passes MLBB checks
- [ ] Smooth 60fps overlay
- [ ] No crashes or memory leaks
- [ ] Floating widget is responsive

---

## Out of Scope (Phase 1)

- iOS support
- Server infrastructure
- Advanced analytics
- Multi-language support
- Root-required features

---

## Future Enhancements (Phase 2)

- AdMob integration
- Server-side memory offset updates
- User accounts and statistics
- Premium features
- iOS support via jailbreak

---

## Notes

- Memory offsets need updating per MLBB version
- GG script is bundled in APK (zero-config)
- All communications encrypted
- Self-destruct if tampered with
- Random delays on all operations
- **ALWAYS copy the built APKs into the repo** after every successful build:
  - Source: `C:\Users\mitra\AppData\Local\Temp\opencode\mlbb-build\app\outputs\apk\debug\app-debug.apk`
  - Source: `C:\Users\mitra\AppData\Local\Temp\opencode\mlbb-build\app\outputs\apk\release\app-release-unsigned.apk`
  - Destination: `Xmisus\dist\` (create if missing), e.g. `dist\app-debug.apk` and `dist\app-release-unsigned.apk`
  - This keeps the latest APKs inside the Xmisus repo so they are always accessible and versioned with git.

---

**Document Version:** 1.0
**Created:** 2026-08-15
**Status:** Design Approved, Ready for Implementation
