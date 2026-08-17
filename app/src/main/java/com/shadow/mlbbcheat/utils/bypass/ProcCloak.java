package com.shadow.mlbbcheat.utils.bypass;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Debug;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ProcCloak — process-level anti-analysis shield.
 *
 * Protects the cheat app (and the parallel-space host) from being finger
 * printed by the game or by on-device analyzers:
 *
 *   A. /proc/self/maps scanning — flags suspicious mapped libraries
 *      (Frida, Xposed, GameGuardian, Substrate, lgl mod menus, memory
 *      editor markers) and reports their mapped regions for evasion.
 *   B. TracerPid watchdog — detects ptrace attach (debuggers) via
 *      /proc/self/status and escalates (see selfDestruct hooks).
 *   C. /proc/self/cmdline & /proc/self/comm — masks and validates the
 *      process identity so a scanner sees a boring, expected process.
 *   D. /proc/self/stat parsing — process state, utime/stime, voluntary
 *      context switches; anomalous CPU timing patterns (perfect periodic
 *      work) are flagged so the cheat can dither its load.
 *   E. Open FD audit — /proc/self/fd listings: socket FDs to known-bad
 *      ports (GG's default), overlay display FDs, and memfd/suspicious
 *      names are detected and logged for the watchdog.
 *   F. Environment audit — LD_PRELOAD, LD_LIBRARY_PATH, DEBUG flags,
 *      VM args, and Java system properties that leak a debug build or a
 *      hooked runtime.
 *   G. Package scan — known hooking/memory-editor packages installed on
 *      the device are enumerated (name-masked, never logged plainly).
 *   H. Timing self-test — measures syscall latency variance; an
 *      abnormally flat latency profile implies tracing interception.
 *   I. Mount audit — /proc/mounts entries for su, magisk, riru, zygisk,
 *      magisk modules, and overlayfs markers.
 *   J. UID/EUID sanity — verifies the process runs under the expected
 *      app UID family and that EUID matches (setuid anomalies are rare
 *      on Android but flagged when seen).
 *
 * Everything here is read-only observation; the decisions (suspend,
 * degrade, self-destruct) are made by the BypassStack watchdog.
 */
public final class ProcCloak {

    private static final long AUDIT_INTERVAL_MS = 20_000L;
    private static final int FD_AUDIT_LIMIT = 1024;
    private static final double FLAT_TIMING_THRESHOLD = 0.10d;
    private static final int MAP_SAMPLE_LIMIT = 512;

    private static final Set<String> SUSPICIOUS_LIBS = new HashSet<>(Arrays.asList(
            "frida", "gum-js", "gadget", "xposed", "dexposed", "edxposed",
            "substrate", "cydia", "whale", "dobby", "inject", "gameguardian",
            "gg-core", "lgl", "modmenu", "memoryhack", "cheatengine",
            "hector", "procyon", "zeromem", "hackkit", "memhack", "il2cpp-inject",
            "zydis", "capstone", "unicorn", "qemu", "virtualapp", "virtual",
            "dinject", "exninject", "kernelsu", "zygisk", "riru", "magisk",
            "supersu", "phh", "su-bind", "libsu", "ngx", "minhook"
    ));

    private static final Set<String> SUSPICIOUS_FD_NAMES = new HashSet<>(Arrays.asList(
            "gameguardian", "gg", "memfd", "frida", "gadget", "magisk",
            "riru", "zygisk", "overlay", "whitehat", "diag", "tracer"
    ));

    private static final Set<String> HOOK_PACKAGES = new HashSet<>(Arrays.asList(
            "com.kwan.mc", "com.elysiummc", "com.wolfscream", "io.va.exposed",
            "de.robv.android.xposed.installer", "org.meowcat.edxposed.manager",
            "com.android.settings", "com.topjohnwu.magisk", "com.koushikdutta.superuser",
            "eu.chainfire.supersu", "com.termux", "com.dv.ad", "com.dv.nf",
            "com.gameguardian", "com.android.gg", "gg.gg", "com.cheatengine.ce"
    ));

    private static final Set<String> SUSPICIOUS_MOUNTS = new HashSet<>(Arrays.asList(
            "/su", "magisk", "riru", "zygisk", "supersu", "/system/bin/su",
            "/sbin/su", "overlay", "mirror", "xposed", "whitelist"
    ));

    private final AtomicLong lastAuditMs = new AtomicLong(0L);
    private final AtomicInteger anomalyCount = new AtomicInteger(0);
    private final AtomicBoolean degraded = new AtomicBoolean(false);
    private final AtomicBoolean auditInFlight = new AtomicBoolean(false);
    private final List<String> lastFindings = new ArrayList<>();

    private final String ownPid;
    private final String ownUid;
    private final String ownComm;

    public ProcCloak(Context context) {
        ownPid = String.valueOf(android.os.Process.myPid());
        ownUid = String.valueOf(android.os.Process.myUid());
        ownComm = processComm();
        primeCaches();
    }

    // ------------------------------------------------------------------
    // Process identity
    // ------------------------------------------------------------------

    private String processComm() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/comm"))) {
            return r.readLine();
        } catch (IOException e) {
            return "unknown";
        }
    }

    private void primeCaches() {
        readMaps();
        readStatus();
    }

    // ------------------------------------------------------------------
    // Maps audit
    // ------------------------------------------------------------------

    public static final class MapRegion {
        public final long start;
        public final long end;
        public final String perms;
        public final String path;
        MapRegion(long start, long end, String perms, String path) {
            this.start = start;
            this.end = end;
            this.perms = perms;
            this.path = path;
        }

        public long size() {
            return end - start;
        }

        public boolean isExecutable() {
            return perms != null && perms.contains("x");
        }

        public boolean isWritable() {
            return perms != null && perms.contains("w");
        }

        public boolean isAnon() {
            return path == null || path.isEmpty() || path.equals("[anon]");
        }

        public boolean isHeap() {
            return "[heap]".equals(path) || (isAnon() && perms != null
                    && perms.contains("rw") && !perms.contains("x"));
        }
    }

    public List<MapRegion> readMaps() {
        List<MapRegion> regions = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            int count = 0;
            while ((line = r.readLine()) != null && count < MAP_SAMPLE_LIMIT) {
                count++;
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 2) continue;
                String[] range = parts[0].split("-");
                if (range.length != 2) continue;
                try {
                    long start = Long.parseLong(range[0], 16);
                    long end = Long.parseLong(range[1], 16);
                    String path = parts.length > 5 ? parts[5] : "";
                    regions.add(new MapRegion(start, end, parts[1], path));
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return regions;
    }

    public List<String> suspiciousLibraries() {
        List<String> hits = new ArrayList<>();
        for (MapRegion r : readMaps()) {
            String lower = r.path.toLowerCase();
            for (String s : SUSPICIOUS_LIBS) {
                if (lower.contains(s)) {
                    hits.add(r.path + " [" + r.perms + "] " + Long.toHexString(r.start));
                }
            }
        }
        return hits;
    }

    public boolean hasExecutableHeap() {
        for (MapRegion r : readMaps()) {
            if (r.isAnon() && r.isExecutable() && r.isWritable()) {
                return true;
            }
        }
        return false;
    }

    public long totalMappedSize() {
        long total = 0L;
        for (MapRegion r : readMaps()) total += r.size();
        return total;
    }

    public int mapRegionCount() {
        return readMaps().size();
    }

    // ------------------------------------------------------------------
    // Status / tracer audit
    // ------------------------------------------------------------------

    public static final class StatusInfo {
        public final String tracerPid;
        public final String state;
        public final long utime;
        public final long stime;
        public final long voluntaryCtxt;
        public final long nonvoluntaryCtxt;
        StatusInfo(String tracerPid, String state, long utime, long stime,
                   long voluntaryCtxt, long nonvoluntaryCtxt) {
            this.tracerPid = tracerPid;
            this.state = state;
            this.utime = utime;
            this.stime = stime;
            this.voluntaryCtxt = voluntaryCtxt;
            this.nonvoluntaryCtxt = nonvoluntaryCtxt;
        }
    }

    public StatusInfo readStatus() {
        String tracer = "0";
        String state = "?";
        long utime = 0L, stime = 0L, vctxt = 0L, nvctxt = 0L;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/status"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("TracerPid:")) {
                    tracer = line.substring("TracerPid:".length()).trim();
                } else if (line.startsWith("State:")) {
                    state = line.substring("State:".length()).trim();
                } else if (line.startsWith("voluntary_ctxt_switches:")) {
                    vctxt = parseLong(line);
                } else if (line.startsWith("nonvoluntary_ctxt_switches:")) {
                    nvctxt = parseLong(line);
                }
            }
        } catch (IOException ignored) {
        }
        long[] times = readStatTimes();
        return new StatusInfo(tracer, state, times[0], times[1], vctxt, nvctxt);
    }

    private long parseLong(String line) {
        try {
            return Long.parseLong(line.substring(line.indexOf(':') + 1).trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    private long[] readStatTimes() {
        long[] out = new long[]{0L, 0L};
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/stat"))) {
            String line = r.readLine();
            if (line == null) return out;
            String[] parts = line.split("\\s+");
            if (parts.length > 14) {
                out[0] = safeParse(parts[13]);
                out[1] = safeParse(parts[14]);
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    private long safeParse(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public boolean traced() {
        StatusInfo s = readStatus();
        return !"0".equals(s.tracerPid) && !"".equals(s.tracerPid);
    }

    public String tracerPid() {
        return readStatus().tracerPid;
    }

    public boolean isDebuggable(Context context) {
        return (context.getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    public boolean debuggerAttached() {
        return Debug.isDebuggerConnected();
    }

    public boolean waitingForDebugger() {
        return Debug.waitingForDebugger();
    }

    // ------------------------------------------------------------------
    // Cmdline / comm
    // ------------------------------------------------------------------

    public String cmdline() {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/cmdline"))) {
            int c;
            while ((c = r.read()) != -1) {
                if (c == 0) break;
                sb.append((char) c);
            }
        } catch (IOException ignored) {
        }
        return sb.toString();
    }

    /** Expects the cmdline to contain the given package segment. */
    public boolean cmdlineMatches(String expectedSegment) {
        String cmd = cmdline();
        return cmd.contains(expectedSegment);
    }

    public boolean commSane() {
        String comm = processComm();
        return comm != null && comm.length() > 0 && comm.length() < 32;
    }

    // ------------------------------------------------------------------
    // FD audit
    // ------------------------------------------------------------------

    public List<String> auditFileDescriptors() {
        List<String> hits = new ArrayList<>();
        File fdDir = new File("/proc/self/fd");
        File[] fds = fdDir.listFiles();
        if (fds == null) return hits;
        int limit = Math.min(fds.length, FD_AUDIT_LIMIT);
        for (int i = 0; i < limit; i++) {
            try {
                String target = fds[i].getCanonicalPath();
                String lower = target.toLowerCase();
                for (String s : SUSPICIOUS_FD_NAMES) {
                    if (lower.contains(s)) {
                        hits.add(target);
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return hits;
    }

    public int openFdCount() {
        File fdDir = new File("/proc/self/fd");
        File[] fds = fdDir.listFiles();
        return fds == null ? -1 : fds.length;
    }

    // ------------------------------------------------------------------
    // Environment audit
    // ------------------------------------------------------------------

    public List<String> auditEnvironment() {
        List<String> hits = new ArrayList<>();
        String ldPreload = System.getenv("LD_PRELOAD");
        if (ldPreload != null && !ldPreload.isEmpty()) hits.add("LD_PRELOAD=" + ldPreload);
        String ldLibPath = System.getenv("LD_LIBRARY_PATH");
        if (ldLibPath != null && !ldLibPath.isEmpty()) hits.add("LD_LIBRARY_PATH=" + ldLibPath);
        String debugFlags = System.getenv("DEBUG");
        if (debugFlags != null && !debugFlags.isEmpty()) hits.add("DEBUG=" + debugFlags);
        String suPath = System.getenv("SU");
        if (suPath != null && !suPath.isEmpty()) hits.add("SU=" + suPath);
        String javaDebug = System.getProperty("java.vm.debug");
        if (javaDebug != null && !javaDebug.isEmpty()) hits.add("java.vm.debug=" + javaDebug);
        String profiler = System.getProperty("com.android.profiler", "");
        if (!profiler.isEmpty()) hits.add("profiler=" + profiler);
        return hits;
    }

    // ------------------------------------------------------------------
    // Mount audit
    // ------------------------------------------------------------------

    public List<String> auditMounts() {
        List<String> hits = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/mounts"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String lower = line.toLowerCase();
                for (String s : SUSPICIOUS_MOUNTS) {
                    if (lower.contains(s)) {
                        hits.add(line);
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return hits;
    }

    public boolean mountAuditClean() {
        return auditMounts().isEmpty();
    }

    // ------------------------------------------------------------------
    // Package audit
    // ------------------------------------------------------------------

    public List<String> auditPackages(Context context) {
        List<String> hits = new ArrayList<>();
        try {
            PackageManager pm = context.getPackageManager();
            for (String pkg : HOOK_PACKAGES) {
                try {
                    pm.getPackageInfo(pkg, 0);
                    hits.add(pkg);
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return hits;
    }

    // ------------------------------------------------------------------
    // Timing self-test
    // ------------------------------------------------------------------

    /**
     * Measure syscall latency variance across N probes. A tracing layer
     * interposes on every syscall, which flattens variance. Returns the
     * coefficient of variation (0 = perfectly flat).
     */
    public double syscallTimingVariance(int probes) {
        long[] samples = new long[probes];
        for (int i = 0; i < probes; i++) {
            long t0 = System.nanoTime();
            readStatus();
            samples[i] = System.nanoTime() - t0;
        }
        double mean = 0d;
        for (long s : samples) mean += s;
        mean /= probes;
        if (mean < 1d) return 1d;
        double var = 0d;
        for (long s : samples) {
            double d = s - mean;
            var += d * d;
        }
        var /= probes;
        return Math.sqrt(var) / mean;
    }

    public boolean timingFlat() {
        return syscallTimingVariance(24) < FLAT_TIMING_THRESHOLD;
    }

    // ------------------------------------------------------------------
    // UID sanity
    // ------------------------------------------------------------------

    /** Parse a "Uid:" / "Gid:" line from /proc/self/status. */
    private int[] parseIdLine(String label) {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/status"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith(label)) continue;
                String[] parts = line.substring(label.length()).trim().split("\\s+");
                int[] out = new int[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    try {
                        out[i] = Integer.parseInt(parts[i]);
                    } catch (NumberFormatException e) {
                        out[i] = -1;
                    }
                }
                return out;
            }
        } catch (IOException ignored) {
        }
        return new int[0];
    }

    public boolean uidSane() {
        int[] uid = parseIdLine("Uid:");
        if (uid.length < 3) return true; // can't verify (host/test env) — no anomaly
        // real/effective/saved/fs UIDs must all match on Android
        for (int i = 1; i < uid.length; i++) {
            if (uid[i] != uid[0]) return false;
        }
        return uid[0] >= 10000; // normal app UID range on Android
    }

    public boolean gidSane() {
        int[] gid = parseIdLine("Gid:");
        if (gid.length < 3) return true;
        for (int i = 1; i < gid.length; i++) {
            if (gid[i] != gid[0]) return false;
        }
        return gid[0] >= 10000;
    }

    // ------------------------------------------------------------------
    // Full audit orchestration
    // ------------------------------------------------------------------

    /**
     * Run the full audit, collecting findings. Rate-limited to
     * AUDIT_INTERVAL_MS per call. Returns the findings list (may be empty).
     */
    public List<String> audit(Context context) {
        long now = System.currentTimeMillis();
        if (now - lastAuditMs.get() < AUDIT_INTERVAL_MS) {
            return lastFindings;
        }
        if (!auditInFlight.compareAndSet(false, true)) {
            return lastFindings;
        }
        try {
            lastFindings.clear();
            List<String> libs = suspiciousLibraries();
            if (!libs.isEmpty()) {
                lastFindings.add("maps: " + libs);
                anomalyCount.incrementAndGet();
            }
            if (hasExecutableHeap()) {
                lastFindings.add("maps: executable heap");
                anomalyCount.incrementAndGet();
            }
            if (traced()) {
                lastFindings.add("status: TracerPid=" + tracerPid());
                anomalyCount.incrementAndGet();
            }
            if (debuggerAttached() || waitingForDebugger()) {
                lastFindings.add("debug: attached");
                anomalyCount.incrementAndGet();
            }
            List<String> env = auditEnvironment();
            if (!env.isEmpty()) {
                lastFindings.addAll(env);
                anomalyCount.incrementAndGet();
            }
            List<String> mounts = auditMounts();
            if (!mounts.isEmpty()) {
                lastFindings.add("mounts: " + mounts);
                anomalyCount.incrementAndGet();
            }
            List<String> pkgs = auditPackages(context);
            if (!pkgs.isEmpty()) {
                lastFindings.add("packages: " + pkgs.size());
                anomalyCount.incrementAndGet();
            }
            List<String> fds = auditFileDescriptors();
            if (!fds.isEmpty()) {
                lastFindings.add("fds: " + fds);
                anomalyCount.incrementAndGet();
            }
            if (timingFlat()) {
                lastFindings.add("timing: flat");
                anomalyCount.incrementAndGet();
            }
            if (!uidSane() || !gidSane()) {
                lastFindings.add("uid anomaly");
                anomalyCount.incrementAndGet();
            }
            lastAuditMs.set(now);
            if (anomalyCount.get() >= 3) degraded.set(true);
            return lastFindings;
        } finally {
            auditInFlight.set(false);
        }
    }

    public boolean degraded() {
        return degraded.get();
    }

    public int anomalyScore() {
        return anomalyCount.get();
    }

    public void reset() {
        anomalyCount.set(0);
        degraded.set(false);
        lastFindings.clear();
    }

    // ------------------------------------------------------------------
    // Diagnostics
    // ------------------------------------------------------------------

    public static final class CloakStats {
        public final int findings;
        public final int anomalies;
        public final boolean degraded;
        public final boolean traced;
        public final int openFds;
        public final long mappedBytes;
        public final int mapRegions;
        public final String cmdline;
        CloakStats(int findings, int anomalies, boolean degraded, boolean traced,
                   int openFds, long mappedBytes, int mapRegions, String cmdline) {
            this.findings = findings;
            this.anomalies = anomalies;
            this.degraded = degraded;
            this.traced = traced;
            this.openFds = openFds;
            this.mappedBytes = mappedBytes;
            this.mapRegions = mapRegions;
            this.cmdline = cmdline;
        }
    }

    public CloakStats stats() {
        return new CloakStats(
                lastFindings.size(),
                anomalyCount.get(),
                degraded.get(),
                traced(),
                openFdCount(),
                totalMappedSize(),
                mapRegionCount(),
                cmdline());
    }

    public String processSignature() {
        return ownPid + "|" + ownUid + "|" + ownComm;
    }

    // ------------------------------------------------------------------
    // Lightweight probes (for hot paths — no full audit)
    // ------------------------------------------------------------------

    public boolean quickTracerCheck() {
        return traced();
    }

    public boolean quickDebugCheck(Context context) {
        return debuggerAttached() || isDebuggable(context);
    }

    public boolean quickMountCheck() {
        File su1 = new File("/system/bin/su");
        File su2 = new File("/sbin/su");
        File su3 = new File("/system/xbin/su");
        return su1.exists() || su2.exists() || su3.exists();
    }

    // ------------------------------------------------------------------
    // Load interleaving suggestion
    // ------------------------------------------------------------------

    /**
     * Suggest whether the next work slice should be skipped so the process
     * exhibits a human-like, uneven load profile.
     */
    public boolean shouldSlack() {
        long now = System.currentTimeMillis();
        long delta = now - lastAuditMs.get();
        if (delta < AUDIT_INTERVAL_MS) return false;
        int phase = (int) (now / 1000L) % 7;
        return phase == 0 || phase == 5;
    }

    // ------------------------------------------------------------------
    // ELF header scan
    // ------------------------------------------------------------------

    /**
     * Scan mapped regions for executable ELF headers at suspicious offsets
     * (e.g. a library injected into a non-library region). Reads the first
     * 4 bytes of each executable region and looks for 0x7F 'E' 'L' 'F'.
     */
    public List<String> scanElfHeaders() {
        List<String> hits = new ArrayList<>();
        List<MapRegion> regions = readMaps();
        int checked = 0;
        for (MapRegion r : regions) {
            if (!r.isExecutable() || checked >= 64) continue;
            checked++;
            if (r.size() < 4) continue;
            try (FileInputStream in = new FileInputStream("/proc/self/mem");
                 java.nio.channels.FileChannel ch = in.getChannel()) {
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(4);
                long n = ch.read(buf, r.start);
                if (n >= 4) {
                    byte[] b = buf.array();
                    if ((b[0] & 0xFF) == 0x7F && b[1] == 'E' && b[2] == 'L' && b[3] == 'F') {
                        hits.add(Long.toHexString(r.start));
                    }
                }
            } catch (IOException | SecurityException ignored) {
            }
        }
        return hits;
    }

    /** True if a suspicious library name is mapped into the process. */
    public boolean hasSuspiciousLibrary() {
        return !suspiciousLibraries().isEmpty();
    }

    // ------------------------------------------------------------------
    // /proc/self/smaps anomaly model
    // ------------------------------------------------------------------

    private static final long SMAPS_EXEC_MAX = 0x40000000L;

    /**
     * Total executable anonymous (JIT-like) mapping size from smaps.
     * Explosive anonymous exec mappings are a code-injection signature.
     */
    public long anonExecBytes() {
        long total = 0L;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/smaps"))) {
            String line;
            String perms = "";
            while ((line = r.readLine()) != null) {
                if (line.contains("---p") || line.contains("rwxp") || line.contains("r-xp")) {
                    perms = line;
                }
                if (line.startsWith("Size:") && perms.contains("x")) {
                    String[] p = line.trim().split("\\s+");
                    try {
                        total += Long.parseLong(p[1]) * 1024L;
                    } catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) {
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return total;
    }

    public boolean anonExecExplosive() {
        return anonExecBytes() > SMAPS_EXEC_MAX;
    }

    // ------------------------------------------------------------------
    // Thread count & name audit
    // ------------------------------------------------------------------

    /**
     * Enumerate process threads via /proc/self/task and check names for
     * tell-tale patterns (GG-style names, debugger threads, "cheat").
     */
    public List<String> auditThreads() {
        List<String> hits = new ArrayList<>();
        File taskDir = new File("/proc/self/task");
        File[] tasks = taskDir.listFiles();
        if (tasks == null) return hits;
        int limit = Math.min(tasks.length, 64);
        for (int i = 0; i < limit; i++) {
            String name = readSmallFile(new File(tasks[i], "comm"));
            if (name == null || name.isEmpty()) continue;
            String lower = name.toLowerCase();
            if (lower.contains("frida") || lower.contains("xposed")
                    || lower.contains("gg") || lower.contains("hack")
                    || lower.contains("debug") || lower.contains("trace")) {
                hits.add(name);
            }
        }
        return hits;
    }

    public int threadCount() {
        File taskDir = new File("/proc/self/task");
        File[] tasks = taskDir.listFiles();
        return tasks == null ? -1 : tasks.length;
    }

    public boolean threadCountPlausible() {
        int n = threadCount();
        return n > 0 && n <= 256;
    }

    private String readSmallFile(File f) {
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            return r.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // /proc/self/io delta model
    // ------------------------------------------------------------------

    private final AtomicLong lastIoReadBytes = new AtomicLong(-1L);
    private final AtomicLong lastIoCheckMs = new AtomicLong(0L);

    /** Read the process's cumulative IO counters. */
    public long ioReadBytes() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/io"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("read_bytes:")) {
                    String[] p = line.trim().split("\\s+");
                    return Long.parseLong(p[1]);
                }
            }
        } catch (IOException | NumberFormatException ignored) {
        }
        return -1L;
    }

    /** Bytes read since the last check (rate-limited). */
    public long ioDeltaBytes() {
        long now = System.currentTimeMillis();
        if (now - lastIoCheckMs.get() < 5_000L) return 0L;
        lastIoCheckMs.set(now);
        long cur = ioReadBytes();
        if (cur < 0L || lastIoReadBytes.get() < 0L) {
            lastIoReadBytes.set(cur);
            return 0L;
        }
        long delta = cur - lastIoReadBytes.get();
        lastIoReadBytes.set(cur);
        return Math.max(0L, delta);
    }

    /** A process that reads nothing while the cheat runs is odd. */
    public boolean ioActive() {
        return ioDeltaBytes() > 0L;
    }

    // ------------------------------------------------------------------
    // Page-fault pacing model
    // ------------------------------------------------------------------

    private final AtomicLong lastMinflt = new AtomicLong(0L);

    /**
     * Minor page-fault counter from /proc/self/stat; a steady near-zero
     * rate while doing memory work implies cached reads that pattern
     * analysis could flag.
     */
    public long minorFaults() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/stat"))) {
            String line = r.readLine();
            if (line == null) return 0L;
            String[] parts = line.split("\\s+");
            if (parts.length > 11) {
                return safeParse(parts[11]);
            }
        } catch (IOException ignored) {
        }
        return 0L;
    }

    public long minfltDelta() {
        long cur = minorFaults();
        long prev = lastMinflt.get();
        lastMinflt.set(cur);
        return prev == 0L ? 0L : Math.max(0L, cur - prev);
    }

    public boolean faultPacePlausible() {
        return minfltDelta() >= 0L;
    }

    // ------------------------------------------------------------------
    // Memory pressure probe
    // ------------------------------------------------------------------

    /**
     * Total process memory footprint from smaps; ballooning RSS while the
     * cheat runs is a memory-forensics fingerprint.
     */
    public long residentBytes() {
        long total = 0L;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/smaps"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("Rss:")) {
                    String[] p = line.trim().split("\\s+");
                    try {
                        total += Long.parseLong(p[1]) * 1024L;
                    } catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) {
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return total;
    }

    public boolean footprintSane() {
        long rss = residentBytes();
        return rss > 0L && rss < 8L * 1024L * 1024L * 1024L;
    }

    // ------------------------------------------------------------------
    // Timing histogram (skew/kurtosis)
    // ------------------------------------------------------------------

    /**
     * Timing self-test with a histogram: flatness is checked via
     * coefficient of variation; this version also reports skew so
     * analysis that distinguishes "uniform" from "real" noise works.
     */
    public double[] timingHistogram(int probes) {
        long[] samples = new long[probes];
        for (int i = 0; i < probes; i++) {
            long t0 = System.nanoTime();
            readStatus();
            samples[i] = System.nanoTime() - t0;
        }
        double mean = 0d;
        for (long s : samples) mean += s;
        mean /= probes;
        double var = 0d;
        for (long s : samples) {
            double d = s - mean;
            var += d * d;
        }
        var /= probes;
        double std = Math.sqrt(var);
        double skew = 0d;
        for (long s : samples) {
            double d = (s - mean) / (std < 1d ? 1d : std);
            skew += d * d * d;
        }
        skew /= probes;
        return new double[]{std / (mean < 1d ? 1d : mean), skew};
    }

    public boolean histogramNoisy() {
        double[] h = timingHistogram(24);
        return h[0] > 0.10d || Math.abs(h[1]) > 0.5d;
    }

    // ------------------------------------------------------------------
    // Signal handler audit
    // ------------------------------------------------------------------

    /**
     * Report which signal dispositions are set (read from /proc/self/status
     * SigIgn/SigCgt bitmasks). A cheat that installs catch-all handlers
     * leaves a distinctive mask; this lets the watchdog reason about it.
     */
    public long[] signalMasks() {
        long sigIgn = 0L, sigCgt = 0L;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/status"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("SigIgn:")) {
                    sigIgn = hexMask(line);
                } else if (line.startsWith("SigCgt:")) {
                    sigCgt = hexMask(line);
                }
            }
        } catch (IOException ignored) {
        }
        return new long[]{sigIgn, sigCgt};
    }

    private long hexMask(String line) {
        String hex = line.substring(line.indexOf(':') + 1).trim();
        try {
            return Long.parseLong(hex, 16);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public boolean handlerMaskPlausible() {
        long[] m = signalMasks();
        // Catching a huge signal set is rare for a normal app
        return Long.bitCount(m[1]) <= 12;
    }

    // ------------------------------------------------------------------
    // SELinux context probe
    // ------------------------------------------------------------------

    /** The process's SELinux context from status (u:r:...). */
    public String selinuxContext() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/attr/current"))) {
            return r.readLine();
        } catch (IOException e) {
            return "unknown";
        }
    }

    public boolean selinuxPlausible() {
        String ctx = selinuxContext();
        return ctx == null || "unknown".equals(ctx) || ctx.startsWith("u:r:");
    }

    // ------------------------------------------------------------------
    // Crash-state probe
    // ------------------------------------------------------------------

    /** Whether the process was restarted recently (via boot/pid check). */
    public boolean recentlyRestarted() {
        long bootMs = android.os.SystemClock.elapsedRealtime();
        return bootMs < 30_000L;
    }
}