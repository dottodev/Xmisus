package com.shadow.mlbbcheat.overlay;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
 * Xmisus floating control panel (v3).
 *
 * Medium-size branded panel with the full module list (ESP / DRONE / AIM /
 * SAFE / LAG), per-module settings rows (⚙), animated minimize to an "X"
 * pill that can be tapped to reopen, drag anywhere, and full persistence:
 * chip on/off states, slider values, and the panel position survive
 * restarts.
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

    private static final int PANEL_MIN_X = 8;
    private static final int PANEL_MIN_Y = 60;

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

    private static final long ANIM_MS = 260L;

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
        panelParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.TOP | Gravity.START;
        panelParams.x = prefs.getInt(KEY_POS_X, 16);
        panelParams.y = prefs.getInt(KEY_POS_Y, 180);

        pillParams = new WindowManager.LayoutParams(
                dp(context, 52), dp(context, 52),
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
        panel.setBackground(rounded(context, "#F0141420", 16));
        panel.setPadding(dp(context, 6), dp(context, 6), dp(context, 6), dp(context, 6));

        panel.addView(buildHeader(context));

        espChip = chip(context, "ESP");
        espSettings = buildEspSettings(context);
        panel.addView(moduleRow(context, espChip, espSettings));

        droneChip = chip(context, "DRONE");
        droneSettings = buildDroneSettings(context);
        panel.addView(moduleRow(context, droneChip, droneSettings));

        aimChip = chip(context, "AIM");
        aimSettings = buildAimSettings(context);
        panel.addView(moduleRow(context, aimChip, aimSettings));

        safeChip = chip(context, "SAFE");
        safeSettings = buildSafeSettings(context);
        panel.addView(moduleRow(context, safeChip, safeSettings));

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

        TextView title = new TextView(context);
        title.setText("X M I S U S");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        title.setTextColor(Color.parseColor("#FF4444"));
        title.setLetterSpacing(0.08f);
        title.setPadding(dp(context, 10), dp(context, 6), dp(context, 4), dp(context, 6));
        header.addView(title);

        LinearLayout spacer = new LinearLayout(context);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        header.addView(spacer);

        Button minBtn = new Button(context);
        minBtn.setText("—");
        minBtn.setAllCaps(false);
        minBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        minBtn.setTextColor(Color.WHITE);
        minBtn.setBackground(rounded(context, "#2A2A3E", 9));
        LinearLayout.LayoutParams minLp = new LinearLayout.LayoutParams(
                dp(context, 36), dp(context, 30));
        minLp.rightMargin = dp(context, 2);
        minBtn.setLayoutParams(minLp);
        minBtn.setOnClickListener(v -> minimize());
        header.addView(minBtn);
        return header;
    }

    /** One module row: chip + gear. */
    private LinearLayout moduleRow(Context context, Button chip, View settings) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(context, 5);
        row.setLayoutParams(rowLp);

        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                0, dp(context, 44), 1f);
        chip.setLayoutParams(chipLp);
        row.addView(chip);

        Button gear = new Button(context);
        gear.setText("⚙");
        gear.setAllCaps(false);
        gear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        gear.setTextColor(Color.parseColor("#B8B8CC"));
        gear.setBackground(rounded(context, "#1A1A28", 10));
        LinearLayout.LayoutParams gearLp = new LinearLayout.LayoutParams(
                dp(context, 40), dp(context, 44));
        gearLp.leftMargin = dp(context, 4);
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
        s.setBackground(rounded(context, "#10101C", 12));
        s.setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(context, 5);
        s.setLayoutParams(lp);
        s.setVisibility(View.GONE);
        return s;
    }

    private TextView settingsLabel(Context context, String text) {
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Color.parseColor("#8A8AA0"));
        t.setPadding(0, dp(context, 2), 0, dp(context, 2));
        return t;
    }

    private TextView settingsValue(Context context) {
        TextView t = new TextView(context);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setTextColor(Color.parseColor("#E4E4F0"));
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
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 38));
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
        lagModeBtn.setTextColor(Color.WHITE);
        lagModeBtn.setBackground(rounded(lagModeBtn.getContext(), "#2A2A3E", 9));
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
            b.setTextColor(Color.WHITE);
            b.setBackground(rounded(b.getContext(), "#FF4444", 11));
        } else {
            b.setTextColor(Color.parseColor("#8A8AA0"));
            b.setBackground(rounded(b.getContext(), "#1A1A28", 11));
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
    // Pill
    // ------------------------------------------------------------------

    private void buildPill(Context context) {
        LinearLayout inner = new LinearLayout(context);
        inner.setGravity(Gravity.CENTER);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setBackground(rounded(context, "#F0141420", 26));
        TextView x = new TextView(context);
        x.setText("X");
        x.setTextColor(Color.parseColor("#FF4444"));
        x.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        x.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        x.setGravity(Gravity.CENTER);
        inner.addView(x);
        pill = inner;
        pill.setOnTouchListener(handleDrag());
        pill.setClickable(true);
    }

    // ------------------------------------------------------------------
    // Drag (with click forwarding — fixes the minimize/reopen bug)
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

    private GradientDrawable rounded(Context context, String colorHex, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor(colorHex));
        g.setCornerRadius(dp(context, radiusDp));
        return g;
    }

    private static int dp(Context context, int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, context.getResources().getDisplayMetrics()));
    }
}
