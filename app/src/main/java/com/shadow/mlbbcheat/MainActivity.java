package com.shadow.mlbbcheat;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;
import com.shadow.mlbbcheat.license.KeyManager;
import com.shadow.mlbbcheat.license.PremiumManager;
import com.shadow.mlbbcheat.memory.OffsetRepository;
import com.shadow.mlbbcheat.net.ServerClient;
import com.shadow.mlbbcheat.services.OverlayService;
import com.shadow.mlbbcheat.services.ScriptService;
import com.shadow.mlbbcheat.utils.Crypto;
import com.shadow.mlbbcheat.utils.PermissionsHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Launcher + permission hub + key redemption + ads.
 *
 * Free tier: ESP, map hack, enemy alert, auto-retri.
 * Premium (key or ad-rewarded): drone view + aim assist.
 */
public class MainActivity extends Activity {

    // TEST ad unit IDs — replace with real ones before release
    private static final String BANNER_UNIT_ID = "ca-app-pub-3940256099942544/6300978111";
    private static final String REWARDED_UNIT_ID = "ca-app-pub-3940256099942544/5224354917";
    private static final long AD_PREMIUM_MS = 60L * 60 * 1000; // 1h per ad

    private TextView status;
    private TextView premiumStatus;
    private EditText keyInput;
    private AdView banner;
    private RewardedAd rewardedAd;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        copyAssets();
        initAds();
    }

    private LinearLayout buildUi() {
        ScrollView scroll = new ScrollView(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setGravity(Gravity.CENTER);
        status.setText("—");
        root.addView(status);

        premiumStatus = new TextView(this);
        premiumStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        premiumStatus.setGravity(Gravity.CENTER);
        root.addView(premiumStatus);

        // ---- Key redemption -------------------------------------------
        TextView keyLabel = new TextView(this);
        keyLabel.setText("Enter activation key");
        root.addView(keyLabel);

        keyInput = new EditText(this);
        keyInput.setHint("XXXX-XXXX-XXXX-XXXX");
        keyInput.setSingleLine(true);
        root.addView(keyInput);

        root.addView(button("Redeem key", this::redeemKey));

        // ---- Permissions ----------------------------------------------
        root.addView(button("1. Overlay permission",
                () -> PermissionsHelper.requestOverlay(this)));
        root.addView(button("2. Accessibility (auto retri/aim)",
                () -> PermissionsHelper.requestAccessibility(this)));
        root.addView(button("Open parallel-app permissions",
                () -> PermissionsHelper.openContainerPermissions(this)));

        // ---- Launch ---------------------------------------------------
        root.addView(button("LAUNCH CHEAT", this::launchCheat));
        root.addView(button("Open MLBB (parallel)", this::openMlbb));

        // ---- Ads ------------------------------------------------------
        banner = new AdView(this);
        banner.setAdUnitId(BANNER_UNIT_ID);
        banner.setAdSize(AdSize.BANNER);
        LinearLayout.LayoutParams adLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        adLp.topMargin = dp(16);
        adLp.gravity = Gravity.CENTER_HORIZONTAL;
        banner.setLayoutParams(adLp);
        root.addView(banner);

        root.addView(button("Watch ad → 1h premium", this::showRewardedAd));

        scroll.addView(root);
        return root;
    }

    private Button button(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    // ------------------------------------------------------------------
    // Key redemption
    // ------------------------------------------------------------------

    private void redeemKey() {
        String raw = keyInput.getText().toString().trim();
        if (!KeyManager.looksValid(raw)) {
            Toast.makeText(this, "Key format invalid", Toast.LENGTH_LONG).show();
            return;
        }
        String key = KeyManager.normalize(raw);
        new Thread(() -> {
            Crypto crypto = new Crypto(ServerClient.deviceId(this).getBytes());
            ServerClient client = new ServerClient(this, crypto);
            try {
                ServerClient.KeyResult result = client.validateKey(key);
                runOnUiThread(() -> handleKeyResult(result));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Network error: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void handleKeyResult(ServerClient.KeyResult result) {
        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show();
        if (result.ok) {
            KeyManager.storeGrant(this, result.tier, result.expiryTs);
        }
        refreshStatus();
    }

    // ------------------------------------------------------------------
    // Ads
    // ------------------------------------------------------------------

    private void initAds() {
        MobileAds.initialize(this, initializationStatus -> {
            AdRequest request = new AdRequest.Builder().build();
            banner.loadAd(request);
            loadRewarded();
        });
    }

    private void loadRewarded() {
        RewardedAd.load(this, REWARDED_UNIT_ID, new AdRequest.Builder().build(),
                new RewardedAdLoadCallback() {
                    @Override
                    public void onAdLoaded(RewardedAd ad) {
                        rewardedAd = ad;
                    }
                });
    }

    private void showRewardedAd() {
        if (rewardedAd == null) {
            Toast.makeText(this, "Ad not ready yet", Toast.LENGTH_SHORT).show();
            loadRewarded();
            return;
        }
        rewardedAd.show(this, rewardItem -> {
            PremiumManager.grantTemporaryPremium(this, AD_PREMIUM_MS);
            runOnUiThread(() -> {
                Toast.makeText(this, "+1h premium unlocked", Toast.LENGTH_LONG).show();
                refreshStatus();
            });
        });
        rewardedAd = null;
    }

    // ------------------------------------------------------------------
    // Launch
    // ------------------------------------------------------------------

    private void launchCheat() {
        if (!PermissionsHelper.hasOverlay(this)) {
            Toast.makeText(this, "Grant overlay permission first", Toast.LENGTH_LONG).show();
            PermissionsHelper.requestOverlay(this);
            return;
        }
        if (!PermissionsHelper.isAccessibilityEnabled(this)) {
            Toast.makeText(this, "Enable accessibility service first", Toast.LENGTH_LONG).show();
            PermissionsHelper.requestAccessibility(this);
            return;
        }
        startService(new Intent(this, ScriptService.class));
        startService(new Intent(this, OverlayService.class));
        Toast.makeText(this,
                "Cheat stack active. Run mlbb_cheat.lua in GameGuardian (inside the parallel app).",
                Toast.LENGTH_LONG).show();
    }

    private void openMlbb() {
        String[] pkgs = {
            "com.mobilelegends.mlbb.booyah",
            "com.moonton.mlbb",
            "com.mobilelegends.mlbb",
            "com.mobile.legends"
        };
        for (String pkg : pkgs) {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) {
                startActivity(i);
                return;
            }
        }
        Toast.makeText(this, "MLBB not found — open it inside your parallel app",
                Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------
    // Status + assets
    // ------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("Overlay: ").append(PermissionsHelper.hasOverlay(this) ? "OK" : "MISSING").append('\n');
        sb.append("Accessibility: ").append(PermissionsHelper.isAccessibilityEnabled(this) ? "OK" : "MISSING").append('\n');
        sb.append("In parallel container: ").append(PermissionsHelper.runningInContainer(this) ? "YES" : "no").append('\n');
        sb.append("MLBB fingerprint: ").append(OffsetRepository.fingerprint(this)).append('\n');
        sb.append("Offset DB: ").append(new OffsetRepository(this).getActive().version);
        status.setText(sb.toString());

        if (PremiumManager.isPremiumActive(this)) {
            long ms = PremiumManager.temporaryPremiumMs(this);
            String temp = ms > 0
                    ? " (+ ad premium " + (ms / 60000) + "m)"
                    : "";
            premiumStatus.setText("Tier: PREMIUM" + temp);
        } else {
            premiumStatus.setText("Tier: FREE — redeem a key or watch an ad");
        }
    }

    private void copyAssets() {
        copyAssetToGameGuardian();
        copyAssetToFiles("offset_db.json");
    }

    private void copyAssetToGameGuardian() {
        try {
            File dir = new File(Environment.getExternalStorageDirectory(),
                    "GameGuardian/scripts");
            if (!dir.exists() && !dir.mkdirs()) {
                fallbackCopy();
                return;
            }
            File target = new File(dir, "mlbb_cheat.lua");
            try (InputStream in = getAssets().open("scripts/mlbb_cheat.lua");
                 OutputStream out = new FileOutputStream(target)) {
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
            File dir = new File(getExternalFilesDir(null), "gg");
            if (!dir.exists() && !dir.mkdirs()) return;
            File target = new File(dir, "mlbb_cheat.lua");
            try (InputStream in = getAssets().open("scripts/mlbb_cheat.lua");
                 OutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            Toast.makeText(this,
                    "Script copied to app files (Android 10+). Move it to GameGuardian/scripts.",
                    Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {
        }
    }

    private void copyAssetToFiles(String asset) {
        try {
            File target = new File(getFilesDir(), asset);
            try (InputStream in = getAssets().open(asset);
                 OutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
        } catch (Exception ignored) {
        }
    }
}