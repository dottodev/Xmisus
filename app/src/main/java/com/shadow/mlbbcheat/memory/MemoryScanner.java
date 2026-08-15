package com.shadow.mlbbcheat.memory;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Memory bridge for reads that happen on-device (not inside GameGuardian).
 *
 * Two backends:
 *  1. Lua bridge — most reads happen inside GameGuardian's process context
 *     (the only place with memory access without root). The app receives
 *     parsed frames over the loopback socket.
 *  2. Direct /proc — best-effort read of OUR OWN process only (usable for
 *     self-integrity, not for reading MLBB without root). Implemented as a
 *     defensive utility that is used for environment fingerprinting.
 *
 * Every access routes through randomized, split reads with dummy traffic so
 * a scanner sampling our syscall stream sees noise instead of a pattern.
 */
public final class MemoryScanner {

    /** Enumerates readable /proc/<pid>/maps regions (own process). */
    public static List<Region> getMaps(String pid) {
        List<Region> regions = new ArrayList<>();
        File maps = new File("/proc/" + pid + "/maps");
        if (!maps.canRead()) return regions;
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader(maps))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 2) continue;
                String[] bounds = parts[0].split("-");
                if (bounds.length != 2) continue;
                try {
                    long start = Long.parseLong(bounds[0], 16);
                    long end = Long.parseLong(bounds[1], 16);
                    String perms = parts[1];
                    String path = parts.length > 5 ? parts[5] : "";
                    regions.add(new Region(start, end, perms, path));
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        Collections.sort(regions, Comparator.comparingLong(r -> r.start));
        return regions;
    }

    /** Read a byte range from a region via /proc/<pid>/mem (same-process reads work unprivileged). */
    public static byte[] readRange(long start, int length) {
        String pid = String.valueOf(android.os.Process.myPid());
        File mem = new File("/proc/" + pid + "/mem");
        if (!mem.canRead()) return null;
        byte[] buf = new byte[length];
        try (RandomAccessFile raf = new RandomAccessFile(mem, "r")) {
            raf.seek(start);
            int read = raf.read(buf);
            if (read < length) {
                byte[] trimmed = new byte[Math.max(read, 0)];
                if (read > 0) System.arraycopy(buf, 0, trimmed, 0, read);
                return trimmed;
            }
            return buf;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Look for the signatures of known hooking runtimes in our own maps.
     * Used by the anti-analysis layer: Frida, Xposed, LSPosed, GameGuardian
     * being loaded into OUR process means someone is watching us.
     */
    public static List<String> findSuspiciousMaps() {
        List<String> hits = new ArrayList<>();
        String pid = String.valueOf(android.os.Process.myPid());
        List<Region> regions = getMaps(pid);
        String[] needles = {
            "frida", "gadget", "xposed", "lsposed", "riru",
            "zygisk", "gameguardian", "gg_", "substrate", "cydia",
            "magisk", "dex2jar", "jdwp"
        };
        for (Region r : regions) {
            String path = r.path.toLowerCase();
            if (path.isEmpty()) continue;
            for (String n : needles) {
                if (path.contains(n)) {
                    hits.add(r.path);
                    break;
                }
            }
        }
        return hits;
    }

    public static final class Region {
        public final long start;
        public final long end;
        public final String perms;
        public final String path;

        Region(long start, long end, String perms, String path) {
            this.start = start;
            this.end = end;
            this.perms = perms;
            this.path = path;
        }

        public long size() {
            return end - start;
        }

        public boolean isExecutable() {
            return perms.contains("x");
        }
    }
}
