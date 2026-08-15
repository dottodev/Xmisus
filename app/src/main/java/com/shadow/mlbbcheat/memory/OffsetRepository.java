package com.shadow.mlbbcheat.memory;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Version-aware offset repository.
 *
 * Resolution order:
 *  1. Server-delivered cache on disk (hot update, survives restart)
 *  2. Bundled assets/offset_db.json
 *  3. Built-in placeholder set
 *
 * The Lua bridge writes its fingerprint to the app via the socket; the app
 * resolves the matching {@link GameOffsets.OffsetSet} and persists it for
 * the script to re-read on every launch.
 */
public final class OffsetRepository {

    private static final String CACHE_FILE = "offset_db.json";

    private final Map<String, GameOffsets.OffsetSet> sets = new ConcurrentHashMap<>();
    private GameOffsets.OffsetSet active;

    public OffsetRepository(Context context) {
        if (!loadFromDisk(context)) {
            loadFromAssets(context);
        }
        active = resolveActive(context);
    }

    /** Called by the socket handler when the Lua script reports its fingerprint. */
    public synchronized GameOffsets.OffsetSet resolve(String fingerprint) {
        GameOffsets.OffsetSet s = sets.get(fingerprint);
        if (s != null) {
            active = s;
        }
        return active;
    }

    public synchronized GameOffsets.OffsetSet getActive() {
        return active;
    }

    public synchronized void applyServerUpdate(Context context, String json) {
        try {
            parseInto(json);
            persist(context);
            active = resolveActive(context);
        } catch (JSONException e) {
            throw new IllegalArgumentException("Invalid offset DB payload", e);
        }
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    private void parseInto(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray arr = root.optJSONArray("versions");
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            GameOffsets.OffsetSet s = fromJson(o);
            sets.put(s.version, s);
        }
    }

    private static GameOffsets.OffsetSet fromJson(JSONObject o) throws JSONException {
        return new GameOffsets.OffsetSet(
                o.getString("version"),
                o.getLong("enemy_base"),
                o.getInt("player_size"),
                o.getInt("player_x_off"),
                o.getInt("player_y_off"),
                o.getInt("player_hp_off"),
                o.optInt("player_mana_off", 0x204),
                o.optInt("player_team_off", 0x208),
                o.optInt("player_level_off", 0x20C),
                o.getLong("camera_zoom_addr"),
                o.optLong("camera_pitch_addr", 0),
                o.optLong("camera_yaw_addr", 0),
                o.optLong("minimap_origin_x_addr", 0),
                o.optLong("minimap_origin_y_addr", 0),
                o.optLong("minimap_scale_addr", 0),
                o.optLong("game_state_addr", 0),
                o.optInt("ai_move_speed_addr", 0x300),
                o.optInt("retri_cd_addr", 0x304));
    }

    private boolean loadFromAssets(Context context) {
        try (InputStream in = context.getAssets().open("offset_db.json");
             BufferedReader r = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            parseInto(sb.toString());
            return true;
        } catch (IOException | JSONException e) {
            return false;
        }
    }

    private boolean loadFromDisk(Context context) {
        File f = new File(context.getFilesDir(), CACHE_FILE);
        if (!f.exists()) return false;
        try (FileInputStream in = new FileInputStream(f);
             BufferedReader r = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            parseInto(sb.toString());
            return true;
        } catch (IOException | JSONException e) {
            return false;
        }
    }

    private void persist(Context context) {
        File f = new File(context.getFilesDir(), CACHE_FILE);
        try (FileOutputStream out = new FileOutputStream(f)) {
            StringBuilder sb = new StringBuilder("{\"versions\":[");
            boolean first = true;
            for (GameOffsets.OffsetSet s : sets.values()) {
                if (!first) sb.append(',');
                first = false;
                Map<String, Object> m = s.toMap();
                sb.append("{");
                boolean f2 = true;
                for (Map.Entry<String, Object> e : m.entrySet()) {
                    if (!f2) sb.append(',');
                    f2 = false;
                    sb.append('"').append(e.getKey()).append('"')
                      .append(':').append(e.getValue());
                }
                sb.append("}");
            }
            sb.append("]}");
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    private GameOffsets.OffsetSet resolveActive(Context context) {
        String fp = fingerprint(context);
        GameOffsets.OffsetSet s = sets.get(fp);
        return s != null ? s : GameOffsets.getPlaceholder();
    }

    /** Stable fingerprint of the installed MLBB build. */
    public static String fingerprint(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            for (String pkg : MLBB_PACKAGES) {
                PackageInfo info = pm.getPackageInfo(pkg, 0);
                if (info != null) {
                    long firstInstall = info.firstInstallTime;
                    return pkg + "@" + info.versionCode + "#" + (firstInstall % 1000000);
                }
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return "unknown";
    }

    private static final String[] MLBB_PACKAGES = {
        "com.mobilelegends.mlbb.booyah",
        "com.moonton.mlbb",
        "com.mobilelegends.mlbb",
        "com.mobile.legends"
    };
}
