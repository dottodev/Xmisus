# v1.4.0 — Navy/White UI Redesign (Overlay v4 + Main Screen v2)

Date: 2026-08-17
Status: Approved (user)

## Goal

Make the Xmisus UI look dramatically better: a box-shaped, medium-large
overlay panel with purpose-based categories, a pure navy-blue + pure-white
theme across both the overlay and the main screen, and a working
minimize/restore cycle.

## Root cause being fixed

The minimized pill never registered an `OnClickListener`. `handleDrag()`
calls `v.performClick()` on `ACTION_UP`, but the pill had only a touch
listener (`WidgetManager.java` buildPill), so taps did nothing and the
panel could never be reopened.

## Design

### 1. Shared theme: `NavyTheme` (new class, `overlay/` package)

Single source of truth for the navy/white look, used by both
`WidgetManager` and `MainActivity`.

Palette constants:

| Token          | Value    | Use                                   |
| -------------- | -------- | ------------------------------------- |
| NAVY_BG        | #0A1628  | Main screen background                |
| NAVY_PANEL     | #0D1B2E  | Overlay panel / cards                 |
| NAVY_SURFACE   | #14263F  | Chips off, sliders, inner surfaces    |
| NAVY_BORDER    | #1E3A5F  | Strokes / dividers                    |
| WHITE          | #FFFFFF  | Titles, ON chips, primary buttons     |
| TEXT_MUTED     | #A8B8CC  | Secondary text, labels                |
| TEXT_DIM       | #6B7F99  | Version text, disabled states         |

Helpers (static):

- `rounded(colorHex, radiusDp)` — GradientDrawable with solid color
- `bordered(colorHex, radiusDp, borderColorHex)` — drawable with stroke
- `navyGradient(top, bottom)` — vertical gradient drawable
- `chip(on)` / `chip(on, border)` — button background styles (ON = white
  fill, navy text; OFF = navy surface, white text, navy border)
- `textSizeSp(...)` helpers where convenient

### 2. Overlay panel: `WidgetManager` v4

- Panel width ~72% of screen (`MATCH_PARENT` inside a fixed-width window
  param using screen width), wrap-content height, all content visible.
- Rounded 20dp card, `NAVY_PANEL` fill, 1.5dp `NAVY_BORDER` stroke.
- Header bar:
  - "XMISUS" — white, bold, letterspaced, 15sp
  - Status dot (white, small circle) when stack is visible
  - Minimize button "—" — navy surface, white text, bordered
- Four category sections, each with an uppercase label header + divider
  line (2dp `NAVY_BORDER` strip):
  - **VISION** → ESP
  - **UTILITY** → DRONE, AIM
  - **DEFENSE** → SAFE
  - **OFFENSE** → LAG
- Module row: 48dp tall chip (toggle) + 40dp ⚙ gear button.
  - Chip ON: white fill, navy bold text
  - Chip OFF: navy surface, white text, navy border
- Settings rows (gear opens them inline as today):
  - ESP view distance slider (50–500m)
  - DRONE camera zoom slider (1000–9000)
  - AIM sensitivity slider (0.5–2.0x)
  - SAFE info note
  - LAG intensity slider (1–10) + mode cycle button (STUTTER/FREEZE/RUBBER)
  - Restyled: navy surface, white values, muted labels
- Pill (minimized state): 56dp circular navy button with white "X",
  navy border. **Fix**: `pill.setOnClickListener(v -> expand())`.
- Drag: whole panel / pill draggable (existing 8dp threshold logic),
  position persisted to prefs; pill opens at the panel position and vice
  versa.
- Animations kept: overshoot expand (~260ms), accelerate minimize.

### 3. Main screen: `MainActivity` v2

- Vertical navy gradient background (`NAVY_SURFACE` → `NAVY_BG`).
- Header: "XMISUS" 34sp white bold letterspaced; tagline in `TEXT_MUTED`.
- Status card: navy card, bordered, white status text, tier line
  (PREMIUM white / FREE muted).
- START button: white fill, navy bold text, 56dp tall, rounded 18dp.
- STOP button: navy surface fill, white text, navy border, 52dp tall.
  Disabled states use `TEXT_DIM` / dimmed fills.
- About card: navy, bordered, white title, `TEXT_MUTED` body.
- Version text bottom in `TEXT_DIM`.
- Behavior unchanged: overlay permission gate, start/stop stack, status
  refresh on resume.

## Non-goals

- No XML layouts, no new dependencies, no Material Components.
- No changes to module behavior, Lua bridge, or bypass logic.

## Testing

- Existing 145 unit tests must stay green (no production logic changes).
- Build debug + release APKs, copy to `dist/Xmisus-v1.4.0-*.apk`.
- Manual: minimize → tap pill → panel reopens at pill position.

## Delivery

- Version bump to 1.4.0 (versionCode 7).
- Commit, push, `gh release v1.4.0` with both APKs.