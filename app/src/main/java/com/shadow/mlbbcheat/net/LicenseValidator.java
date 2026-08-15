package com.shadow.mlbbcheat.net;

import android.content.Context;

import com.shadow.mlbbcheat.utils.Crypto;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

/**
 * License / activation state with offline grace.
 *
 * Once activated, the device keeps a signed proof file. If the network is
 * unavailable the app still runs for the grace window (default 7 days) then
 * pauses until re-activation. Ban flags from the server persist locally and
 * force the cheat stack off.
 */
public final class LicenseValidator {

    private static final String PROOF_FILE = "license.proof";
    private static final long GRACE_MS = 7L * 24 * 60 * 60 * 1000;

    private final Context context;
    private final ServerClient server;

    public LicenseValidator(Context context, ServerClient server) {
        this.context = context.getApplicationContext();
        this.server = server;
    }

    public static final class Status {
        public final boolean valid;
        public final boolean grace;
        public final String message;

        Status(boolean valid, boolean grace, String message) {
            this.valid = valid;
            this.grace = grace;
            this.message = message;
        }
    }

    /** True while we have a valid local proof, regardless of grace. */
    public boolean hasLocalProof() {
        return proofFile().exists() && proofFile().length() > 0;
    }

    /** True if the grace window has expired. */
    public boolean graceExpired() {
        if (!hasLocalProof()) return false;
        long last = readProofTimestamp();
        return System.currentTimeMillis() - last > GRACE_MS;
    }

    public Status validate(String license) {
        if (ServerClient.isBanned(context)) {
            return new Status(false, false, "Device banned");
        }
        if (!hasLocalProof()) {
            boolean ok = activateAndStore(license);
            return new Status(ok, false, ok ? "Activated" : "Activation failed");
        }
        // Existing proof: refresh in background, honor grace on failure
        try {
            ServerClient.HeartbeatResult r = server.heartbeat("1.0", "unknown");
            if (r != null && r.killSwitch) {
                ServerClient.setBanned(context, true);
                return new Status(false, false, "Service discontinued");
            }
            touchProof();
            return new Status(true, false, "License valid");
        } catch (Exception e) {
            if (graceExpired()) {
                return new Status(false, false, "Offline grace expired");
            }
            return new Status(true, true, "Offline grace");
        }
    }

    public void revoke() {
        proofFile().delete();
    }

    // ------------------------------------------------------------------

    private boolean activateAndStore(String license) {
        if (license == null || license.isEmpty()) return false;
        try {
            if (server.activate(license)) {
                writeProof();
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private File proofFile() {
        return new File(context.getFilesDir(), PROOF_FILE);
    }

    private void writeProof() {
        try {
            Properties p = new Properties();
            p.setProperty("device", ServerClient.deviceId(context));
            p.setProperty("ts", String.valueOf(System.currentTimeMillis()));
            p.setProperty("sig", Crypto.sha256Hex(
                    ServerClient.deviceId(context) + "|" + System.currentTimeMillis()));
            try (FileOutputStream out = new FileOutputStream(proofFile())) {
                p.store(out, "license proof");
            }
        } catch (Exception ignored) {
        }
    }

    private long readProofTimestamp() {
        try {
            Properties p = new Properties();
            try (FileInputStream in = new FileInputStream(proofFile())) {
                p.load(in);
            }
            return Long.parseLong(p.getProperty("ts", "0"));
        } catch (Exception e) {
            return 0L;
        }
    }

    private void touchProof() {
        try (FileOutputStream out = new FileOutputStream(proofFile(), true)) {
            out.write(new byte[0]);
        } catch (Exception ignored) {
        }
    }
}
