package com.shadow.mlbbcheat.overlay;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
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
import android.widget.TextView;

/**
 * Xmisus floating control panel.
 *
 * Simple by design: a branded header ("XMISUS"), three feature toggles
 * (ESP / Drone / Aim) and a minimize (-) button. Animations:
 *   - expand: scale 0.6 -> 1.0 + fade in (spring-ish overshoot)
 *   - minimize: panel shrinks/fades into a small circular "X" pill
 *   - pill tap: expands back to the full panel
 * The whole panel is draggable like the old widget.
 */
public class WidgetManager {

    private final WindowManager windowManager;
    private final ToggleListener listener;

    private LinearLayout panel;
    private View pill;
    private WindowManager.LayoutParams panelParams;
    private WindowManager.LayoutParams pillParams;
    private boolean visible = false;
    private boolean minimized = false;

    private Button espBtn;
    private Button droneBtn;
    private Button aimBtn;
    private boolean espOn = true;
    private boolean droneOn = false;
    private boolean aimOn = false;

    private float downRawX;
    private float downRawY;
    private int startX;
    private int startY;
    private boolean dragging = false;

    public interface ToggleListener {
        void onToggle(String feature, boolean enabled);
    }

    private static final long ANIM_MS = 240L;

    public WidgetManager(Context context, ToggleListener listener) {
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.listener = listener;

        panelParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.TOP | Gravity.START;
        panelParams.x = 16;
        panelParams.y = 180;

        pillParams = new WindowManager.LayoutParams(
                dp(context, 46), dp(context, 46),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT);
        pillParams.gravity = Gravity.TOP | Gravity.START;
        pillParams.x = panelParams.x;
        pillParams.y = panelParams.y;

        buildPanel(context);
        buildPill(context);
    }

    // ------------------------------------------------------------------
    // Views
    // ------------------------------------------------------------------

    private void buildPanel(Context context) {
        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(rounded(context, "#F0141420", 14));
        panel.setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4));

        // Header row: XMISUS + minimize button
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(context);
        title.setText("XMISUS");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        title.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        title.setTextColor(Color.parseColor("#FF4444"));
        title.setPadding(dp(context, 8), dp(context, 4), dp(context, 4), dp(context, 4));
        title.setLetterSpacing(0.1f);

        Button minBtn = new Button(context);
        minBtn.setText("—");
        minBtn.setAllCaps(false);
        minBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        minBtn.setTextColor(Color.WHITE);
        minBtn.setBackground(rounded(context, "#2A2A3E", 8));
        LinearLayout.LayoutParams minLp = new LinearLayout.LayoutParams(
                dp(context, 30), dp(context, 26));
        minBtn.setLayoutParams(minLp);
        minBtn.setOnClickListener(v -> minimize());

        header.addView(title);
        LinearLayout spacer = new LinearLayout(context);
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(0, 1, 1f);
        spacer.setLayoutParams(spLp);
        header.addView(spacer);
        header.addView(minBtn);
        panel.addView(header);

        // Toggle chips
        espBtn = chip(context, "ESP", espOn, () -> toggleFeature(espBtn, "esp", false));
        droneBtn = chip(context, "DRONE", droneOn, () -> toggleFeature(droneBtn, "drone", false));
        aimBtn = chip(context, "AIM", aimOn, () -> toggleFeature(aimBtn, "aim", false));
        panel.addView(espBtn);
        panel.addView(droneBtn);
        panel.addView(aimBtn);

        panel.setOnTouchListener(handleDrag());
    }

    private void buildPill(Context context) {
        LinearLayout inner = new LinearLayout(context);
        inner.setGravity(Gravity.CENTER);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setBackground(rounded(context, "#F0141420", 23));
        TextView x = new TextView(context);
        x.setText("X");
        x.setTextColor(Color.parseColor("#FF4444"));
        x.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        x.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        x.setGravity(Gravity.CENTER);
        inner.addView(x);
        pill = inner;
        pill.setOnClickListener(v -> expand());
        pill.setOnTouchListener(handleDrag());
        pill.setClickable(true);
    }

    private Button chip(Context context, String label, boolean on, Runnable toggle) {
        Button b = new Button(context);
        b.setText(label);
        b.setAllCaps(true);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 34));
        lp.topMargin = dp(context, 3);
        b.setLayoutParams(lp);
        applyChipStyle(b, on);
        b.setOnClickListener(v -> toggle.run());
        return b;
    }

    private void applyChipStyle(Button b, boolean on) {
        if (on) {
            b.setTextColor(Color.WHITE);
            b.setBackground(rounded(b.getContext(), "#FF4444", 9));
        } else {
            b.setTextColor(Color.parseColor("#8A8AA0"));
            b.setBackground(rounded(b.getContext(), "#1A1A28", 9));
        }
    }

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

    private void toggleFeature(Button btn, String feature, boolean ignored) {
        if (feature.equals("esp")) {
            espOn = !espOn;
            applyChipStyle(espBtn, espOn);
            listener.onToggle("esp", espOn);
        } else if (feature.equals("drone")) {
            droneOn = !droneOn;
            applyChipStyle(droneBtn, droneOn);
            listener.onToggle("drone", droneOn);
        } else if (feature.equals("aim")) {
            aimOn = !aimOn;
            applyChipStyle(aimBtn, aimOn);
            listener.onToggle("aim", aimOn);
        }
    }

    // ------------------------------------------------------------------
    // Drag
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
                    if (Math.abs(dx) > dp(v.getContext(), 6)
                            || Math.abs(dy) > dp(v.getContext(), 6)) {
                        dragging = true;
                    }
                    if (dragging) {
                        if (minimized) {
                            pillParams.x = startX + (int) dx;
                            pillParams.y = startY + (int) dy;
                            windowManager.updateViewLayout(pill, pillParams);
                        } else {
                            panelParams.x = startX + (int) dx;
                            panelParams.y = startY + (int) dy;
                            windowManager.updateViewLayout(panel, panelParams);
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    return dragging; // swallow the click if we dragged
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
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f))
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
}
