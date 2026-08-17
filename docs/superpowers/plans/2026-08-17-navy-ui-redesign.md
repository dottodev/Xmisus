# v1.4.0 Navy/White UI Redesign — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restyle the overlay panel and main screen into a boxed, category-based, navy-blue + pure-white UI and fix the pill reopen bug.

**Architecture:** A new `NavyTheme` constants/helpers class is the single source of truth for the palette and drawable styles. `WidgetManager` is rewritten as a boxed card (72% screen width) with four purpose-based category sections and a circular pill that properly reopens via an `OnClickListener`. `MainActivity` is restyled with the same theme. No XML layouts, no new dependencies.

**Tech Stack:** Java, Android SDK (API 34), programmatic views (no XML), existing Robolectric unit test suite (145 tests), Gradle 8.5.

## Global Constraints

- Palette values (verbatim from spec): NAVY_BG `#0A1628`, NAVY_PANEL `#0D1B2E`, NAVY_SURFACE `#14263F`, NAVY_BORDER `#1E3A5F`, WHITE `#FFFFFF`, TEXT_MUTED `#A8B8CC`, TEXT_DIM `#6B7F99`.
- No XML layouts, no new dependencies, no Material Components.
- No changes to module behavior, Lua bridge, bypass logic, persistence keys, or the `ToggleListener`/`SettingsListener` interfaces (OverlayService consumes them).
- Existing 145 unit tests must stay green.
- Version: `versionCode 7`, `versionName "1.4.0"` (also MainActivity fallback string).
- APKs must be copied to `dist/Xmisus-v1.4.0-debug.apk` and `dist/Xmisus-v1.4.0-release.apk`.
- Commit per task; final task pushes and publishes `gh release v1.4.0`.
- Build/test commands run on Windows PowerShell from repo root `C:\Users\mitra\OneDrive\Desktop\projects\Xmisus` via `Start-Process .\gradlew.bat -ArgumentList "<task>","--console=plain","-q" -RedirectStandardOutput <log> -RedirectStandardError <log> -Wait -NoNewWindow` with logs in `C:\Users\mitra\AppData\Local\Temp\opencode\`.

---

### Task 1: NavyTheme class

**Files:**
- Create: `app/src/main/java/com/shadow/mlbbcheat/overlay/NavyTheme.java`

**Interfaces:**
- Produces: `NavyTheme` with constants `NAVY_BG`, `NAVY_PANEL`, `NAVY_SURFACE`, `NAVY_BORDER`, `WHITE`, `TEXT_MUTED`, `TEXT_DIM` (all `int`), and static helpers `rounded(Context, int color, int radiusDp)`, `bordered(Context, int fill, int radiusDp, int borderColor)`, `navyGradient(int top, int bottom)`, `dp(Context, int)`. Used by Tasks 2 and 3.

- [ ] **Step 1: Create the file**

```java
package com.shadow.mlbbcheat.overlay;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;

/** Navy-blue + pure-white theme shared by the overlay and the launcher. */
public final class NavyTheme {

    public static final int NAVY_BG = Color.parseColor("#0A1628");
    public static final int NAVY_PANEL = Color.parseColor("#0D1B2E");
    public static final int NAVY_SURFACE = Color.parseColor("#14263F");
    public static final int NAVY_BORDER = Color.parseColor("#1E3A5F");
    public static final int WHITE = Color.parseColor("#FFFFFF");
    public static final int TEXT_MUTED = Color.parseColor("#A8B8CC");
    public static final int TEXT_DIM = Color.parseColor("#6B7F99");

    private NavyTheme() {
    }

    public static GradientDrawable rounded(Context c, int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    public static GradientDrawable bordered(Context c, int fill, int radiusDp, int borderColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        g.setStroke(dp(c, 1), borderColor);
        return g;
    }

    public static GradientDrawable navyGradient(int top, int bottom) {
        return new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
    }

    public static int dp(Context c, int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `Start-Process .\gradlew.bat -ArgumentList "compileDebugJavaWithJavac","--console=plain","-q"` with logs redirected as described in Global Constraints.
Expected: BUILD SUCCESSFUL, no errors.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/shadow/mlbbcheat/overlay/NavyTheme.java
git commit -m "feat: NavyTheme - shared navy/white palette + drawable helpers"
```

---

### Task 2: WidgetManager v4 (boxed panel, categories, pill reopen fix)

**Files:**
- Rewrite: `app/src/main/java/com/shadow/mlbbcheat/overlay/WidgetManager.java`

**Interfaces:**
- Consumes: `NavyTheme` (Task 1).
- Consumes (unchanged, defined by OverlayService): `ToggleListener.onToggle(String feature, boolean enabled)`, `SettingsListener.onSetting(String feature, String key, float value)`.
- Produces: same public surface as v3: constructor `WidgetManager(Context, ToggleListener, SettingsListener)`, `show()`, `hide()`, `isVisible()`. Persistence keys unchanged (`xmisus_widget`, `pos_x`, `pos_y`, `esp_on`, `drone_on`, `aim_on`, `safe_on`, `lag_on`, `esp_distance`, `drone_zoom`, `aim_sensitivity`, `lag_intensity`, `lag_mode`).

- [ ] **Step 1: Replace the file with the full v4 implementation**

```java
package com.shadow.mlbbcheat.overlay;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Xmisus floating control panel (v4).
 *
 * Boxed, medium-large card (~72% screen width) with a navy/white theme,
 * four purpose-based category sections (VISION / UTILITY / DEFENSE /
 * OFFENSE), per-module settings rows, animated minimize to a circular
 * pill that reopens via a proper OnClickListener, drag anywhere, and full
 * persistence.
 */
public class WidgetManager {

    private static final String PREFS = "xmisus_widget";
    private static final String KEY_POS_X = "pos_x";
    private static final String KEY_POS_Y = "pos_y";
    private static final String KEY_ESP = "esp_on";
    private static final String KEY_DRONE = "drone_on";
    private static final String KEY_AIM = "aim_on";
    private static final String KEY_SAFE = "safe_on";
    private static final String KEY_LAG = "lag_on";
    private static final String KEY_ESP_DIST = "esp_distance";
    private static final String KEY_DRONE_ZOOM = "drone_zoom";
    private static final String KEY_AIM_SENS = "aim_sensitivity";
    private static final String KEY_LAG_INT = "lag_intensity";
    private static final String KEY_LAG_MODE = "lag_mode";

    private static final float PANEL_WIDTH_FRACTION = 0.72f;
    private static final int PANEL_MIN_X = 8;
    private static final int PANEL_MIN_Y = 60;
    private static final long ANIM_MS = 260L;

    private final WindowManager windowManager;
    private final ToggleListener toggleListener;
    private final SettingsListener settingsListener;
    private final SharedPreferences prefs;

    private LinearLayout panel;
    private View pill;
    private WindowManager.LayoutParams panelParams;
    private WindowManager.LayoutParams pillParams;
    private boolean visible = false;
    private boolean minimized = false;

    // --- module state --------------------------------------------------
    private boolean espOn = true;
    private boolean droneOn = false;
    private boolean aimOn = false;
    private boolean safeOn = false;
    private boolean lagOn = false;

    private float espDistance = 300f;
    private int droneZoom = 3000;
    private float aimSensitivity = 1.0f;
    private int lagIntensity = 5;
    private int lagMode = 0; // 0 stutter, 1 freeze, 2 rubber

    private Button espChip, droneChip, aimChip, safeChip, lagChip;
    private View espSettings, droneSettings, aimSettings, safeSettings, lagSettings;
    private Button lagModeBtn;
    private TextView espValue, droneValue, aimValue, lagValue;

    // --- drag state -----------------------------------------------------
    private float downRawX;
    private float downRawY;
    private int startX;
    private int startY;
    private boolean dragging = false;

    public interface ToggleListener {
        void onToggle(String feature, boolean enabled);
    }

    public interface SettingsListener {
        void onSetting(String feature, String key, float value);
    }

    public WidgetManager(Context context, ToggleListener toggleListener,
                         SettingsListener settingsListener) {
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.toggleListener = toggleListener;
        this.settingsListener = settingsListener;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        loadState();
        initParams(context);
        buildPanel(context);
        buildPill(context);
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private void loadState() {
        espOn = prefs.getBoolean(KEY_ESP, true);
        droneOn = prefs.getBoolean(KEY_DRONE, false);
        aimOn = prefs.getBoolean(KEY_AIM, false);
        safeOn = prefs.getBoolean(KEY_SAFE, false);
        lagOn = prefs.getBoolean(KEY_LAG, false);
        espDistance = prefs.getFloat(KEY_ESP_DIST, 300f);
        droneZoom = prefs.getInt(KEY_DRONE_ZOOM, 3000);
        aimSensitivity = prefs.getFloat(KEY_AIM_SENS, 1.0f);
        lagIntensity = prefs.getInt(KEY_LAG_INT, 5);
        lagMode = prefs.getInt(KEY_LAG_MODE, 0);
    }

    private void savePos() {
        prefs.edit()
                .putInt(KEY_POS_X, panelParams.x)
                .putInt(KEY_POS_Y, panelParams.y)
                .apply();
    }

    // ------------------------------------------------------------------
    // Layout params
    // ------------------------------------------------------------------

    private void initParams(Context context) {
        int width = Math.round(context.getResources().getDisplayMetrics().widthPixels
                * PANEL_WIDTH_FRACTION);
        panelParams = new WindowManager.LayoutParams(
                width, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.TOP | Gravity.START;
        panelParams.x = prefs.getInt(KEY_POS_X, 16);
        panelParams.y = prefs.getInt(KEY_POS_Y, 120);

        pillParams = new WindowManager.LayoutParams(
                dp(context, 56), dp(context, 56),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT);
        pillParams.gravity = Gravity.TOP | Gravity.START;
        pillParams.x = panelParams.x;
        pillParams.y = panelParams.y;
    }

    // ------------------------------------------------------------------
    // Panel
    // ------------------------------------------------------------------

    private void buildPanel(Context context) {
        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(NavyTheme.bordered(context, NavyTheme.NAVY_PANEL, 20, NavyTheme.NAVY_BORDER));
        panel.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 12));

        panel.addView(buildHeader(context));

        panel.addView(sectionHeader(context, "VISION"));
        espChip = chip(context, "ESP");
        espSettings = buildEspSettings(context);
        panel.addView(moduleRow(context, espChip, espSettings));

        panel.addView(sectionHeader(context, "UTILITY"));
        droneChip = chip(context, "DRONE");
        droneSettings = buildDroneSettings(context);
        panel.addView(moduleRow(context, droneChip, droneSettings));
        aimChip = chip(context, "AIM");
        aimSettings = buildAimSettings(context);
        panel.addView(moduleRow(context, aimChip, aimSettings));

        panel.addView(sectionHeader(context, "DEFENSE"));
        safeChip = chip(context, "SAFE");
        safeSettings = buildSafeSettings(context);
        panel.addView(moduleRow(context, safeChip, safeSettings));

        panel.addView(sectionHeader(context, "OFFENSE"));
        lagChip = chip(context, "LAG");
        lagSettings = buildLagSettings(context);
        panel.addView(moduleRow(context, lagChip, lagSettings));

        refreshChips();
        panel.setOnTouchListener(handleDrag());
    }

    private LinearLayout buildHeader(Context context) {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(context, 4), 0, 0, 0);

        View dot = new View(context);
        dot.setBackground(NavyTheme.rounded(context, NavyTheme.WHITE, 4));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                dp(context, 8), dp(context, 8));
        dotLp.rightMargin = dp(context, 8);
        dot.setLayoutParams(dotLp);
        header.addView(dot);

        TextView title = new TextView(context);
        title.setText("XMISUS");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        title.setTextColor(NavyTheme.WHITE);
        title.setLetterSpacing(0.12f);
        title.setPadding(0, dp(context, 6), 0, dp(context, 6));
        header.addView(title);

        LinearLayout spacer = new LinearLayout(context);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        header.addView(spacer);

        Button minBtn = new Button(context);
        minBtn.setText("—");
        minBtn.setAllCaps(false);
        minBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        minBtn.setTextColor(NavyTheme.WHITE);
        minBtn.setBackground(NavyTheme.bordered(context, NavyTheme.NAVY_SURFACE, 10, NavyTheme.NAVY_BORDER));
        LinearLayout.LayoutParams minLp = new LinearLayout.LayoutParams(
                dp(context, 40), dp(context, 34));
        minLp.rightMargin = dp(context, 2);
        minBtn.setLayoutParams(minLp);
        minBtn.setOnClickListener(v -> minimize());
        header.addView(minBtn);
        return header;
    }

    /** Category header: uppercase label + divider line. */
    private View sectionHeader(Context context, String label) {
        LinearLayout sec = new LinearLayout(context);
        sec.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(context);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(NavyTheme.TEXT_MUTED);
        t.setLetterSpacing(0.18f);
        t.setPadding(0, dp(context, 10), 0, dp(context, 4));
        sec.addView(t);
        View line = new View(context);
        line.setBackgroundColor(NavyTheme.NAVY_BORDER);
        line.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)));
        sec.addView(line);
        return sec;
    }

    /** One module row: chip + gear, with settings strip below. */
    private LinearLayout moduleRow(Context context, Button chip, View settings) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(context, 7);
        row.setLayoutParams(rowLp);

        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                0, dp(context, 48), 1f);
        chip.setLayoutParams(chipLp);
        row.addView(chip);

        Button gear = new Button(context);
        gear.setText("⚙");
        gear.setAllCaps(false);
        gear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        gear.setTextColor(NavyTheme.TEXT_MUTED);
        gear.setBackground(NavyTheme.bordered(context, NavyTheme.NAVY_SURFACE, 12, NavyTheme.NAVY_BORDER));
        LinearLayout.LayoutParams gearLp = new LinearLayout.LayoutParams(
                dp(context, 44), dp(context, 48));
        gearLp.leftMargin = dp(context, 5);
        gear.setLayoutParams(gearLp);
        gear.setOnClickListener(v -> toggleSettings(settings));
        row.addView(gear);

        LinearLayout wrap = new LinearLayout(context);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wrapLp.topMargin = dp(context, 5);
        wrap.setLayoutParams(wrapLp);
        wrap.addView(row);
        wrap.addView(settings);
        return wrap;
    }

    private Button chip(Context context, String label) {
        Button b = new Button(context);
        b.setText(label);
        b.setAllCaps(true);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setOnClickListener(v -> toggleChip(b, label.toLowerCase()));
        return b;
    }

    // ------------------------------------------------------------------
    // Settings rows
    // ------------------------------------------------------------------

    private LinearLayout buildSettingsBase(Context context) {
        LinearLayout s = new LinearLayout(context);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setBackground(NavyTheme.bordered(context, NavyTheme.NAVY_SURFACE, 14, NavyTheme.NAVY_BORDER));
        s.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(context, 6);
        s.setLayoutParams(lp);
        s.setVisibility(View.GONE);
        return s;
    }

    private TextView settingsLabel(Context context, String text) {
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(NavyTheme.TEXT_MUTED);
        t.setPadding(0, dp(context, 2), 0, dp(context, 2));
        return t;
    }

    private TextView settingsValue(Context context) {
        TextView t = new TextView(context);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(NavyTheme.WHITE);
        t.setGravity(Gravity.END);
        return t;
    }

    private View buildEspSettings(Context context) {
        LinearLayout s = buildSettingsBase(context);
        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = settingsLabel(context, "VIEW DISTANCE");
        label.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        espValue = settingsValue(context);
        head.addView(label);
        head.addView(espValue);
        s.addView(head);

        SeekBar bar = new SeekBar(context);
        bar.setMax(45);
        bar.setProgress(Math.round((espDistance - 50f) / 10f));
        bar.setOnSeekBarChangeListener(slider((progress, fromUser) -> {
            espDistance = 50f + progress * 10f;
            espValue.setText(Math.round(espDistance) + "m");
            if (fromUser) {
                prefs.edit().putFloat(KEY_ESP_DIST, espDistance).apply();
                settingsListener.onSetting("esp", "distance", espDistance);
            }
        }));
        s.addView(bar);
        return s;
    }

    private View buildDroneSettings(Context context) {
        LinearLayout s = buildSettingsBase(context);
        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = settingsLabel(context, "CAMERA ZOOM");
        label.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        droneValue = settingsValue(context);
        head.addView(label);
        head.addView(droneValue);
        s.addView(head);

        SeekBar bar = new SeekBar(context);
        bar.setMax(80);
        bar.setProgress(Math.max(0, Math.min(80, (droneZoom - 1000) / 100)));
        bar.setOnSeekBarChangeListener(slider((progress, fromUser) -> {
            droneZoom = 1000 + progress * 100;
            droneValue.setText(droneZoom + "");
            if (fromUser) {
                prefs.edit().putInt(KEY_DRONE_ZOOM, droneZoom).apply();
                settingsListener.onSetting("drone", "zoom", droneZoom);
            }
        }));
        s.addView(bar);
        return s;
    }

    private View buildAimSettings(Context context) {
        LinearLayout s = buildSettingsBase(context);
        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = settingsLabel(context, "DRAG SENSITIVITY");
        label.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        aimValue = settingsValue(context);
        head.addView(label);
        head.addView(aimValue);
        s.addView(head);

        SeekBar bar = new SeekBar(context);
        bar.setMax(150);
        bar.setProgress(Math.round(aimSensitivity * 100f) - 50);
        bar.setOnSeekBarChangeListener(slider((progress, fromUser) -> {
            aimSensitivity = (50 + progress) / 100f;
            aimValue.setText(String.format(java.util.Locale.US, "%.2fx", aimSensitivity));
            if (fromUser) {
                prefs.edit().putFloat(KEY_AIM_SENS, aimSensitivity).apply();
                settingsListener.onSetting("aim", "sensitivity", aimSensitivity);
            }
        }));
        s.addView(bar);
        return s;
    }

    private View buildSafeSettings(Context context) {
        LinearLayout s = buildSettingsBase(context);
        TextView note = settingsLabel(context,
                "Forces stealth: no boxes, no vibration. Overrides ESP.");
        s.addView(note);
        return s;
    }

    private View buildLagSettings(Context context) {
        LinearLayout s = buildSettingsBase(context);

        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = settingsLabel(context, "INTENSITY");
        label.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        lagValue = settingsValue(context);
        head.addView(label);
        head.addView(lagValue);
        s.addView(head);

        SeekBar bar = new SeekBar(context);
        bar.setMax(9);
        bar.setProgress(Math.max(0, Math.min(9, lagIntensity - 1)));
        bar.setOnSeekBarChangeListener(slider((progress, fromUser) -> {
            lagIntensity = progress + 1;
            lagValue.setText(lagIntensity + "/10");
            if (fromUser) {
                prefs.edit().putInt(KEY_LAG_INT, lagIntensity).apply();
                settingsListener.onSetting("lag", "intensity", lagIntensity);
            }
        }));
        s.addView(bar);

        lagModeBtn = new Button(context);
        lagModeBtn.setAllCaps(true);
        lagModeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        lagModeBtn.setTypeface(Typeface.DEFAULT_BOLD);
        refreshLagModeBtn();
        LinearLayout.LayoutParams modeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 40));
        modeLp.topMargin = dp(context, 6);
        lagModeBtn.setLayoutParams(modeLp);
        lagModeBtn.setOnClickListener(v -> {
            lagMode = (lagMode + 1) % 3;
            prefs.edit().putInt(KEY_LAG_MODE, lagMode).apply();
            refreshLagModeBtn();
            settingsListener.onSetting("lag", "mode", lagMode);
        });
        s.addView(lagModeBtn);
        return s;
    }

    private void refreshLagModeBtn() {
        if (lagModeBtn == null) return;
        String[] modes = {"MODE: STUTTER", "MODE: FREEZE", "MODE: RUBBER"};
        lagModeBtn.setText(modes[lagMode]);
        lagModeBtn.setTextColor(NavyTheme.WHITE);
        lagModeBtn.setBackground(NavyTheme.bordered(lagModeBtn.getContext(),
                NavyTheme.NAVY_SURFACE, 10, NavyTheme.NAVY_BORDER));
    }

    // ------------------------------------------------------------------
    // Slider helper
    // ------------------------------------------------------------------

    private SeekBar.OnSeekBarChangeListener slider(SliderChange change) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                change.onChange(progress, fromUser);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        };
    }

    private interface SliderChange {
        void onChange(int progress, boolean fromUser);
    }

    // ------------------------------------------------------------------
    // Chip toggling
    // ------------------------------------------------------------------

    private void toggleChip(Button chip, String feature) {
        switch (feature) {
            case "esp":
                espOn = !espOn;
                prefs.edit().putBoolean(KEY_ESP, espOn).apply();
                toggleListener.onToggle("esp", espOn);
                break;
            case "drone":
                droneOn = !droneOn;
                prefs.edit().putBoolean(KEY_DRONE, droneOn).apply();
                toggleListener.onToggle("drone", droneOn);
                break;
            case "aim":
                aimOn = !aimOn;
                prefs.edit().putBoolean(KEY_AIM, aimOn).apply();
                toggleListener.onToggle("aim", aimOn);
                break;
            case "safe":
                safeOn = !safeOn;
                prefs.edit().putBoolean(KEY_SAFE, safeOn).apply();
                toggleListener.onToggle("safe", safeOn);
                break;
            case "lag":
                lagOn = !lagOn;
                prefs.edit().putBoolean(KEY_LAG, lagOn).apply();
                toggleListener.onToggle("lag", lagOn);
                break;
            default:
                return;
        }
        refreshChips();
    }

    private void refreshChips() {
        applyChipStyle(espChip, espOn);
        applyChipStyle(droneChip, droneOn);
        applyChipStyle(aimChip, aimOn);
        applyChipStyle(safeChip, safeOn);
        applyChipStyle(lagChip, lagOn);
        espValue.setText(Math.round(espDistance) + "m");
        droneValue.setText(droneZoom + "");
        aimValue.setText(String.format(java.util.Locale.US, "%.2fx", aimSensitivity));
        lagValue.setText(lagIntensity + "/10");
    }

    private void applyChipStyle(Button b, boolean on) {
        if (b == null) return;
        if (on) {
            b.setTextColor(NavyTheme.NAVY_PANEL);
            b.setBackground(NavyTheme.rounded(b.getContext(), NavyTheme.WHITE, 12));
        } else {
            b.setTextColor(NavyTheme.WHITE);
            b.setBackground(NavyTheme.bordered(b.getContext(),
                    NavyTheme.NAVY_SURFACE, 12, NavyTheme.NAVY_BORDER));
        }
    }

    private void toggleSettings(View settings) {
        if (settings == null) return;
        boolean open = settings.getVisibility() == View.VISIBLE;
        settings.setVisibility(open ? View.GONE : View.VISIBLE);
        if (settings.getVisibility() == View.VISIBLE) {
            settings.setAlpha(0f);
            settings.animate().alpha(1f).setDuration(160L).start();
        }
    }

    // ------------------------------------------------------------------
    // Pill (minimized state)
    // ------------------------------------------------------------------

    private void buildPill(Context context) {
        LinearLayout inner = new LinearLayout(context);
        inner.setGravity(Gravity.CENTER);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setBackground(NavyTheme.bordered(context, NavyTheme.NAVY_PANEL, 28, NavyTheme.NAVY_BORDER));
        TextView x = new TextView(context);
        x.setText("X");
        x.setTextColor(NavyTheme.WHITE);
        x.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        x.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        x.setGravity(Gravity.CENTER);
        inner.addView(x);
        pill = inner;
        pill.setOnTouchListener(handleDrag());
        pill.setOnClickListener(v -> expand());
        pill.setClickable(true);
    }

    // ------------------------------------------------------------------
    // Drag (with click forwarding — pill restore via performClick)
    // ------------------------------------------------------------------

    private View.OnTouchListener handleDrag() {
        return (v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    if (minimized) {
                        startX = pillParams.x;
                        startY = pillParams.y;
                    } else {
                        startX = panelParams.x;
                        startY = panelParams.y;
                    }
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downRawX;
                    float dy = event.getRawY() - downRawY;
                    if (Math.abs(dx) > dp(v.getContext(), 8)
                            || Math.abs(dy) > dp(v.getContext(), 8)) {
                        dragging = true;
                    }
                    if (dragging) {
                        if (minimized) {
                            pillParams.x = Math.max(PANEL_MIN_X, startX + (int) dx);
                            pillParams.y = Math.max(PANEL_MIN_Y, startY + (int) dy);
                            windowManager.updateViewLayout(pill, pillParams);
                        } else {
                            panelParams.x = Math.max(PANEL_MIN_X, startX + (int) dx);
                            panelParams.y = Math.max(PANEL_MIN_Y, startY + (int) dy);
                            windowManager.updateViewLayout(panel, panelParams);
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragging) {
                        v.performClick();
                    } else {
                        savePos();
                    }
                    dragging = false;
                    return true;
                default:
                    return false;
            }
        };
    }

    // ------------------------------------------------------------------
    // Show / hide / minimize / expand (animated)
    // ------------------------------------------------------------------

    public void show() {
        if (visible) return;
        visible = true;
        minimized = false;
        windowManager.addView(panel, panelParams);
        animateExpand(panel);
    }

    public void hide() {
        if (!visible) return;
        visible = false;
        try {
            windowManager.removeView(panel);
        } catch (Exception ignored) {
        }
        try {
            windowManager.removeView(pill);
        } catch (Exception ignored) {
        }
    }

    public boolean isVisible() {
        return visible;
    }

    private void minimize() {
        if (minimized) return;
        minimized = true;
        // Sync the pill to the panel position so it never jumps.
        pillParams.x = panelParams.x;
        pillParams.y = panelParams.y;
        animateToPill(panel, () -> {
            try {
                windowManager.removeView(panel);
            } catch (Exception ignored) {
            }
            if (visible) {
                windowManager.addView(pill, pillParams);
                animateExpand(pill);
            }
        });
    }

    private void expand() {
        if (!minimized) return;
        minimized = false;
        // Reopen where the pill was dragged to.
        panelParams.x = pillParams.x;
        panelParams.y = pillParams.y;
        windowManager.addView(panel, panelParams);
        animateExpand(panel);
        try {
            windowManager.removeView(pill);
        } catch (Exception ignored) {
        }
    }

    private void animateExpand(View v) {
        v.setAlpha(0f);
        v.setScaleX(0.6f);
        v.setScaleY(0.6f);
        v.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(ANIM_MS)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.15f))
                .start();
    }

    private void animateToPill(View v, Runnable done) {
        v.animate()
                .alpha(0f).scaleX(0.4f).scaleY(0.4f)
                .setDuration(ANIM_MS)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        done.run();
                    }
                })
                .start();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static int dp(Context context, int v) {
        return NavyTheme.dp(context, v);
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `compileDebugJavaWithJavac` via the Start-Process pattern.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/shadow/mlbbcheat/overlay/WidgetManager.java
git commit -m "feat: overlay v4 - boxed navy panel, 4 category sections, pill reopen fix"
```

---

### Task 3: MainActivity v2 (navy gradient + white cards)

**Files:**
- Rewrite: `app/src/main/java/com/shadow/mlbbcheat/MainActivity.java`

**Interfaces:**
- Consumes: `NavyTheme` (Task 1).
- Produces: same behavior as today — `buildUi()`, `card()`, `button()`, `rounded()`, `dp()` replaced by `NavyTheme`-based versions. All service start/stop, permission gating, status refresh, asset copy logic unchanged.

- [ ] **Step 1: Replace the UI-building methods with the v2 versions**

Keep the entire file except replace the `UI` section (buildUi, card, button, rounded, dp) and the two color references in `refreshState()` with:

```java
    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private ScrollView buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(NavyTheme.navyGradient(NavyTheme.NAVY_SURFACE, NavyTheme.NAVY_BG));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(48), dp(24), dp(24));

        TextView logo = new TextView(this);
        logo.setText("X M I S U S");
        logo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        logo.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        logo.setTextColor(NavyTheme.WHITE);
        logo.setLetterSpacing(0.12f);
        root.addView(logo);

        TextView tagline = new TextView(this);
        tagline.setText(R.string.tagline);
        tagline.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tagline.setTextColor(NavyTheme.TEXT_MUTED);
        tagline.setGravity(Gravity.CENTER);
        tagline.setPadding(0, dp(6), 0, dp(28));
        root.addView(tagline);

        // ---- Status card ----------------------------------------------
        LinearLayout card = card();
        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setTextColor(NavyTheme.WHITE);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.addView(status);

        tierLine = new TextView(this);
        tierLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tierLine.setTextColor(NavyTheme.TEXT_MUTED);
        tierLine.setGravity(Gravity.CENTER);
        tierLine.setPadding(dp(12), 0, dp(12), dp(10));
        card.addView(tierLine);
        root.addView(card);

        // ---- Controls ---------------------------------------------------
        startButton = button("START", true);
        startButton.setTextColor(NavyTheme.NAVY_PANEL);
        startButton.setBackground(rounded(NavyTheme.WHITE, 18));
        startButton.setOnClickListener(v -> startStack());
        root.addView(startButton);

        stopButton = button("STOP", false);
        stopButton.setTextColor(NavyTheme.WHITE);
        stopButton.setBackground(rounded(NavyTheme.NAVY_SURFACE, 18));
        stopButton.setOnClickListener(v -> stopStack());
        root.addView(stopButton);

        // ---- About ------------------------------------------------------
        LinearLayout aboutCard = card();
        TextView aboutTitle = new TextView(this);
        aboutTitle.setText("ABOUT XMISUS");
        aboutTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        aboutTitle.setTypeface(Typeface.DEFAULT_BOLD);
        aboutTitle.setTextColor(NavyTheme.WHITE);
        aboutTitle.setPadding(dp(12), dp(12), dp(12), dp(4));
        aboutCard.addView(aboutTitle);

        TextView about = new TextView(this);
        about.setText(R.string.about_xmisus);
        about.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        about.setTextColor(NavyTheme.TEXT_MUTED);
        about.setLineSpacing(0f, 1.25f);
        about.setPadding(dp(12), 0, dp(12), dp(14));
        aboutCard.addView(about);
        root.addView(aboutCard);

        TextView version = new TextView(this);
        try {
            version.setText("v" + getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName);
        } catch (PackageManager.NameNotFoundException e) {
            version.setText("v1.4.0");
        }
        version.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        version.setTextColor(NavyTheme.TEXT_DIM);
        version.setPadding(0, dp(20), 0, 0);
        root.addView(version);

        scroll.addView(root);
        return scroll;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(NavyTheme.bordered(this, NavyTheme.NAVY_PANEL, 18, NavyTheme.NAVY_BORDER));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(6);
        card.setLayoutParams(lp);
        return card;
    }

    private Button button(String label, boolean big) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(true);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, big ? 16 : 15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(big ? 56 : 52));
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        return NavyTheme.rounded(this, color, radiusDp);
    }

    private int dp(int v) {
        return NavyTheme.dp(this, v);
    }
```

- [ ] **Step 2: Update imports**

Add `import com.shadow.mlbbcheat.overlay.NavyTheme;` and `import android.graphics.drawable.GradientDrawable;` (already present). Remove `import android.graphics.Color;` if it becomes unused — `Color` is still referenced in `refreshState()`; replace those two uses:

```java
        if (PremiumManager.isPremiumActive(this)) {
            tierLine.setText("Tier: PREMIUM");
            tierLine.setTextColor(NavyTheme.WHITE);
        } else {
            tierLine.setText("Tier: FREE");
            tierLine.setTextColor(NavyTheme.TEXT_MUTED);
        }
```

After this, `android.graphics.Color` is no longer referenced — remove the import.

- [ ] **Step 3: Update the disabled-state styling in refreshState**

```java
    private void refreshState() {
        boolean running = stackRunning();
        startButton.setEnabled(!running);
        stopButton.setEnabled(running);
        startButton.setAlpha(running ? 0.45f : 1f);
        stopButton.setAlpha(running ? 1f : 0.45f);

        StringBuilder sb = new StringBuilder();
        sb.append("Overlay permission: ")
                .append(PermissionsHelper.hasOverlay(this) ? "OK" : "MISSING").append('\n');
        sb.append("Service: ").append(running ? "RUNNING" : "stopped");
        status.setText(sb.toString());

        if (PremiumManager.isPremiumActive(this)) {
            tierLine.setText("Tier: PREMIUM");
            tierLine.setTextColor(NavyTheme.WHITE);
        } else {
            tierLine.setText("Tier: FREE");
            tierLine.setTextColor(NavyTheme.TEXT_MUTED);
        }
    }
```

- [ ] **Step 4: Verify it compiles**

Run: `compileDebugJavaWithJavac` via the Start-Process pattern.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/shadow/mlbbcheat/MainActivity.java
git commit -m "feat: launcher v2 - navy gradient, white cards, restyled buttons"
```

---

### Task 4: Version bump to 1.4.0

**Files:**
- Modify: `app/build.gradle` (versionCode/versionName block)

- [ ] **Step 1: Bump the version**

Replace:

```groovy
        versionCode 6
        versionName "1.3.0"
```

with:

```groovy
        versionCode 7
        versionName "1.4.0"
```

- [ ] **Step 2: Commit**

```bash
git add app/build.gradle
git commit -m "build: v1.4.0 (versionCode 7)"
```

---

### Task 5: Suite green, build APKs, dist, publish

**Files:**
- Verify: `C:\Users\mitra\AppData\Local\Temp\opencode\mlbb-build\app\test-results\testDebugUnitTest\*.xml`
- Produce: `dist/Xmisus-v1.4.0-debug.apk`, `dist/Xmisus-v1.4.0-release.apk`

- [ ] **Step 1: Run the full unit test suite**

Run (PowerShell, repo root):
```powershell
Start-Process -FilePath ".\gradlew.bat" -ArgumentList "testDebugUnitTest","--console=plain","-q" -RedirectStandardOutput "C:\Users\mitra\AppData\Local\Temp\opencode\test140.out.log" -RedirectStandardError "C:\Users\mitra\AppData\Local\Temp\opencode\test140.err.log" -Wait -NoNewWindow
```
Then inspect logs for `FAILED` or "There were failing tests". Expected: BUILD SUCCESSFUL, no failures. Confirm count via:
```powershell
$t = 0; $f = 0; Get-ChildItem "C:\Users\mitra\AppData\Local\Temp\opencode\mlbb-build\app\test-results\testDebugUnitTest" -Filter *.xml | ForEach-Object { [xml]$x = Get-Content $_.FullName; $t += [int]$x.testsuite.tests; $f += [int]$x.testsuite.failures }; Write-Host "tests=$t failures=$f"
```
Expected: `tests=145 failures=0`.

- [ ] **Step 2: Build debug + release APKs**

Run: `assembleDebug assembleRelease` via the Start-Process pattern.
Expected: BUILD SUCCESSFUL; outputs at `C:\Users\mitra\AppData\Local\Temp\opencode\mlbb-build\app\outputs\apk\debug\app-debug.apk` and `...\release\app-release.apk`.

- [ ] **Step 3: Copy to dist**

```powershell
Copy-Item "C:\Users\mitra\AppData\Local\Temp\opencode\mlbb-build\app\outputs\apk\debug\app-debug.apk" "dist\Xmisus-v1.4.0-debug.apk" -Force
Copy-Item "C:\Users\mitra\AppData\Local\Temp\opencode\mlbb-build\app\outputs\apk\release\app-release.apk" "dist\Xmisus-v1.4.0-release.apk" -Force
```

- [ ] **Step 4: Commit dist**

```bash
git add dist/Xmisus-v1.4.0-debug.apk dist/Xmisus-v1.4.0-release.apk
git commit -m "build: v1.4.0 APKs"
```

- [ ] **Step 5: Push**

```bash
git push
```

- [ ] **Step 6: Publish release**

```bash
gh release create v1.4.0 "dist/Xmisus-v1.4.0-debug.apk" "dist/Xmisus-v1.4.0-release.apk" --title "Xmisus v1.4.0" --notes "Navy/white UI redesign: boxed overlay panel with VISION/UTILITY/DEFENSE/OFFENSE categories, medium-large size, fixed minimize/restore pill, restyled launcher. 145 tests green."
```
Expected: prints `https://github.com/dottodev/Xmisus/releases/tag/v1.4.0`.

---

## Self-Review Notes

- Spec coverage: palette → Task 1; overlay v4 (box, size, categories, chips, pill fix) → Task 2; launcher v2 → Task 3; version bump → Task 4; suite/build/dist/publish → Task 5. All spec sections covered.
- Placeholder scan: every step has full code or exact commands.
- Type consistency: `NavyTheme.rounded/bordered/navyGradient/dp` signatures used identically in Tasks 2–3; `ToggleListener`/`SettingsListener` interfaces untouched (OverlayService compatibility); persistence keys unchanged.