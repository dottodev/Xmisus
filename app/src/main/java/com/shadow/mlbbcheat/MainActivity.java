package com.shadow.mlbbcheat;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
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
        } catch (Throwable t) {
            CrashLog.log("MainActivity.onCreate failed: " + t);
            finish();
        }
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private LinearLayout buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#12121C"));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(48), dp(24), dp(24));

        TextView logo = new TextView(this);
        logo.setText("X M I S U S");
        logo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        logo.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        logo.setTextColor(Color.parseColor("#FF4444"));
        logo.setLetterSpacing(0.12f);
        root.addView(logo);

        TextView tagline = new TextView(this);
        tagline.setText(R.string.tagline);
        tagline.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tagline.setTextColor(Color.parseColor("#8A8AA0"));
        tagline.setGravity(Gravity.CENTER);
        tagline.setPadding(0, dp(6), 0, dp(28));
        root.addView(tagline);

        // ---- Status card ----------------------------------------------
        LinearLayout card = card(0.92f);
        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setTextColor(Color.parseColor("#E4E4F0"));
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.addView(status);

        tierLine = new TextView(this);
        tierLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tierLine.setTextColor(Color.parseColor("#8A8AA0"));
        tierLine.setGravity(Gravity.CENTER);
        tierLine.setPadding(dp(12), 0, dp(12), dp(10));
        card.addView(tierLine);
        root.addView(card);

        // ---- Controls ---------------------------------------------------
        startButton = button("START", true);
        startButton.setTextColor(Color.WHITE);
        startButton.setBackground(rounded("#3DDC84", 18));
        startButton.setOnClickListener(v -> startStack());
        root.addView(startButton);

        stopButton = button("STOP", false);
        stopButton.setTextColor(Color.WHITE);
        stopButton.setBackground(rounded("#FF4444", 18));
        stopButton.setOnClickListener(v -> stopStack());
        root.addView(stopButton);

        // ---- About ------------------------------------------------------
        LinearLayout aboutCard = card(0.92f);
        TextView aboutTitle = new TextView(this);
        aboutTitle.setText("ABOUT XMISUS");
        aboutTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        aboutTitle.setTypeface(Typeface.DEFAULT_BOLD);
        aboutTitle.setTextColor(Color.parseColor("#FF4444"));
        aboutTitle.setPadding(dp(12), dp(12), dp(12), dp(4));
        aboutCard.addView(aboutTitle);

        TextView about = new TextView(this);
        about.setText(R.string.about_xmisus);
        about.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        about.setTextColor(Color.parseColor("#B8B8CC"));
        about.setLineSpacing(0f, 1.25f);
        about.setPadding(dp(12), 0, dp(12), dp(14));
        aboutCard.addView(about);
        root.addView(aboutCard);

        TextView version = new TextView(this);
        try {
            version.setText("v" + getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName);
        } catch (PackageManager.NameNotFoundException e) {
            version.setText("v1.2.1");
        }
        version.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        version.setTextColor(Color.parseColor("#5A5A70"));
        version.setPadding(0, dp(20), 0, 0);
        root.addView(version);

        scroll.addView(root);
        return root;
    }

    private LinearLayout card(float weight) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded("#1E1E2E", 16));
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
                ViewGroup.LayoutParams.MATCH_PARENT, dp(big ? 52 : 48));
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    private GradientDrawable rounded(String colorHex, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor(colorHex));
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
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
        if (!PermissionsHelper.isAccessibilityEnabled(this)) {
            Toast.makeText(this, "Enable Xmisus accessibility service", Toast.LENGTH_LONG).show();
            PermissionsHelper.requestAccessibility(this);
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

        StringBuilder sb = new StringBuilder();
        sb.append("Overlay permission: ")
                .append(PermissionsHelper.hasOverlay(this) ? "OK" : "MISSING").append('\n');
        sb.append("Accessibility: ")
                .append(PermissionsHelper.isAccessibilityEnabled(this) ? "OK" : "MISSING").append('\n');
        sb.append("Service: ").append(running ? "RUNNING" : "stopped");
        status.setText(sb.toString());

        if (PremiumManager.isPremiumActive(this)) {
            tierLine.setText("Tier: PREMIUM");
            tierLine.setTextColor(Color.parseColor("#3DDC84"));
        } else {
            tierLine.setText("Tier: FREE");
            tierLine.setTextColor(Color.parseColor("#8A8AA0"));
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
