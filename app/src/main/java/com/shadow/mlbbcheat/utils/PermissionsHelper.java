package com.shadow.mlbbcheat.utils;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Toast;

/**
 * Permission orchestration for the overlay + accessibility stack.
 *
 * KEY BEHAVIOR: when the app runs INSIDE a Parallel-Space-style container
 * (its process is hosted by the container package), the container is what
 * the system sees — so overlay/accessibility grants must be requested from
 * the container's identity. We detect the container by reading our own
 * process name, then:
 *   - request the overlay permission via the container's package URI
 *   - deep-link the accessibility settings into the container's settings
 *   - if the user launched the app OUTSIDE the container, show a targeted
 *     instruction and offer to open the container's permission screen.
 */
public final class PermissionsHelper {

    public static final String PKG_PARALLEL_SPACE = "com.lbe.parallel.space";
    public static final String PKG_PARALLEL_SPACE_2 = "com.lbe.parallel.intl";

    private PermissionsHelper() {}

    /** True if our process is hosted by a virtual container. */
    public static boolean runningInContainer(Context context) {
        String process = currentProcessName();
        return !TextUtils.isEmpty(process)
                && !process.equals(context.getPackageName())
                && (process.contains(context.getPackageName())
                        || process.contains("parallel")
                        || process.contains("space")
                        || process.contains("dual")
                        || process.contains("vmos")
                        || process.contains("x8"));
    }

    /** Best-effort name of the host package (the container, if any). */
    public static String hostPackageName(Context context) {
        String process = currentProcessName();
        if (TextUtils.isEmpty(process)) return context.getPackageName();
        int slash = process.indexOf(':');
        String base = slash > 0 ? process.substring(0, slash) : process;
        return base.length() > 0 ? base : context.getPackageName();
    }

    /**
     * Request overlay permission. When inside a container, the URI must be
     * built with the HOST package so the grant lands on the container.
     */
    public static void requestOverlay(Activity activity) {
        String pkg = hostPackageName(activity);
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + pkg));
            activity.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (ActivityNotFoundException e2) {
                Toast.makeText(activity, "Overlay settings unavailable",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    public static boolean hasOverlay(Context context) {
        return Settings.canDrawOverlays(context);
    }

    /** Open the accessibility settings, deep-linked to our service. */
    public static void requestAccessibility(Activity activity) {
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, "Accessibility settings unavailable",
                    Toast.LENGTH_LONG).show();
        }
    }

    public static boolean isAccessibilityEnabled(Context context) {
        String enabled = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        ComponentName expected = new ComponentName(context,
                "com.shadow.mlbbcheat.services.AutoRetriService");
        return enabled.contains(expected.flattenToString())
                || enabled.contains(context.getPackageName());
    }

    /** Open the container app's own permission manager. */
    public static void openContainerPermissions(Context context) {
        String host = hostPackageName(context);
        if (host.equals(context.getPackageName())) {
            return; // not in a container
        }
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(host);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launch);
            Toast.makeText(context,
                    "In " + host + ": long-press Xmisus → permissions → overlay/accessibility",
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(context, "Open your parallel app manually", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * One-call entry used by the launch button. Handles both the
     * container-hosted and standalone cases with an explanation toast.
     */
    public static void ensureAll(Activity activity) {
        if (runningInContainer(activity)) {
            Toast.makeText(activity,
                    "Running inside container: granting overlay+accessibility here.",
                    Toast.LENGTH_SHORT).show();
        }
        if (!hasOverlay(activity)) {
            requestOverlay(activity);
            return;
        }
        if (!isAccessibilityEnabled(activity)) {
            requestAccessibility(activity);
            return;
        }
        Toast.makeText(activity, "All permissions ready. Launch the cheat.",
                Toast.LENGTH_SHORT).show();
    }

    private static String currentProcessName() {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                String name = android.app.Application.getProcessName();
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Throwable ignored) {
        }
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader("/proc/self/cmdline"))) {
            String line = r.readLine();
            if (line != null) {
                int nul = line.indexOf('\0');
                return nul > 0 ? line.substring(0, nul) : line;
            }
        } catch (Exception ignored) {
        }
        return "";
    }
}
