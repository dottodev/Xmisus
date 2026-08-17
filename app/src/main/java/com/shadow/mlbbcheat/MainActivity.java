package com.shadow.mlbbcheat;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.shadow.mlbbcheat.license.PremiumManager;
import com.shadow.mlbbcheat.overlay.NavyTheme;
import com.shadow.mlbbcheat.services.OverlayService;
import com.shadow.mlbbcheat.services.ScriptService;
import com.shadow.mlbbcheat.utils.CrashLog;
import com.shadow.mlbbcheat.utils.PermissionsHelper;

/**
 * Xmisus launcher.
 *
 * Minimal by design: START / STOP the cheat stack and brand info.
 * START requests the overlay + accessibility permissions it needs, then
 * boots both services. Buttons are gated on the running state.
 */
public class MainActivity extends Activity {

    private Button startButton;
    private Button stopButton;
    private TextView status;
    private TextView tierLine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashLog.init(this);
        try {
            setContentView(buildUi());
            copyAssets();
            gatePermissions();
        } catch (Throwable t) {
            CrashLog.log("MainActivity.onCreate failed: " + t);
            finish();
        }
    }

    private static boolean overlayAsked;

    /**
     * Ask for the overlay permission right after the app opens (before the
     * user can start the stack). Skipped silently when the grant is already
     * present — e.g. a parallel app that already carries the permission.
     * Accessibility is intentionally NOT requested (aim assist only runs
     * when the user enables it manually).
     */
    private void gatePermissions() {
        if (!PermissionsHelper.hasOverlay(this)) {
            if (!overlayAsked) {
                overlayAsked = true;
                Toast.makeText(this, "Xmisus needs overlay permission - granting now",
                        Toast.LENGTH_LONG).show();
                PermissionsHelper.requestOverlay(this);
            }
        }
    }

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

    // ------------------------------------------------------------------
    // Stack control
    // ------------------------------------------------------------------

    private void startStack() {
        if (!PermissionsHelper.hasOverlay(this)) {
            Toast.makeText(this, "Grant overlay permission first", Toast.LENGTH_LONG).show();
            PermissionsHelper.requestOverlay(this);
            return;
        }
        startService(new Intent(this, ScriptService.class));
        startService(new Intent(this, OverlayService.class));
        Toast.makeText(this, "Xmisus stack active", Toast.LENGTH_SHORT).show();
    }

    private void stopStack() {
        stopService(new Intent(this, ScriptService.class));
        stopService(new Intent(this, OverlayService.class));
        Toast.makeText(this, "Xmisus stack stopped", Toast.LENGTH_SHORT).show();
        refreshState();
    }

    private boolean stackRunning() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am == null) return false;
            java.util.List<ActivityManager.RunningServiceInfo> services =
                    am.getRunningServices(256);
            if (services == null) return false;
            for (ActivityManager.RunningServiceInfo s : services) {
                if (s.service.getClassName().equals(ScriptService.class.getName())
                        || s.service.getClassName().equals(OverlayService.class.getName())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Status
    // ------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
    }

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

    // ------------------------------------------------------------------
    // Assets (Lua bridge script + offset DB, no UI)
    // ------------------------------------------------------------------

    private void copyAssets() {
        copyAssetToGameGuardian();
        copyAssetToFiles("offset_db.json");
    }

    private void copyAssetToGameGuardian() {
        try {
            java.io.File dir = new java.io.File(
                    android.os.Environment.getExternalStorageDirectory(),
                    "GameGuardian/scripts");
            if (!dir.exists() && !dir.mkdirs()) {
                fallbackCopy();
                return;
            }
            java.io.File target = new java.io.File(dir, "mlbb_cheat.lua");
            try (java.io.InputStream in = getAssets().open("scripts/mlbb_cheat.lua");
                 java.io.OutputStream out = new java.io.FileOutputStream(target)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
        } catch (Exception e) {
            fallbackCopy();
        }
    }

    private void fallbackCopy() {
        try {
            java.io.File dir = new java.io.File(getExternalFilesDir(null), "gg");
            if (!dir.exists() && !dir.mkdirs()) return;
            java.io.File target = new java.io.File(dir, "mlbb_cheat.lua");
            try (java.io.InputStream in = getAssets().open("scripts/mlbb_cheat.lua");
                 java.io.OutputStream out = new java.io.FileOutputStream(target)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
        } catch (Exception ignored) {
        }
    }

    private void copyAssetToFiles(String asset) {
        try {
            java.io.File target = new java.io.File(getFilesDir(), asset);
            try (java.io.InputStream in = getAssets().open(asset);
                 java.io.OutputStream out = new java.io.FileOutputStream(target)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
        } catch (Exception ignored) {
        }
    }
}
