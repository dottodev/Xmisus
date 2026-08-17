package com.shadow.mlbbcheat.utils;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * CrashLog — last-resort evidence channel.
 *
 * Installs a global uncaught-exception handler that appends the full stack
 * trace to crash.log in both app-internal and external storage. If the app
 * ever dies, the file survives so the cause can be read from any file
 * manager — no adb / logcat needed.
 *
 * Also writes a startup marker so we can tell whether the process even
 * reached onCreate.
 */
public final class CrashLog {

    private static final String TAG = "XmisusCrash";
    private static volatile boolean installed = false;
    private static volatile Context appContext;

    private CrashLog() {
    }

    public static synchronized void init(Context context) {
        if (installed) return;
        installed = true;
        appContext = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            writeCrash(throwable);
            try {
                if (previous != null) previous.uncaughtException(thread, throwable);
            } catch (Throwable ignored) {
            }
        });
        markStartup();
    }

    /** Startup marker: proves the process reached the activity onCreate. */
    public static void markStartup() {
        append(ts() + " STARTUP " + Build.VERSION.RELEASE
                + "/" + Build.MODEL + " pid=" + android.os.Process.myPid());
    }

    public static void log(String line) {
        append(ts() + " " + line);
        Log.d(TAG, line);
    }

    private static void writeCrash(Throwable t) {
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            pw.println(ts() + " CRASH thread=" + Thread.currentThread().getName());
            t.printStackTrace(pw);
            append(sw.toString());
        } catch (Throwable ignored) {
        }
    }

    private static void append(String text) {
        if (appContext == null) return;
        try {
            byte[] data = (text + "\n").getBytes(StandardCharsets.UTF_8);
            File internal = new File(appContext.getFilesDir(), "crash.log");
            writeAppend(internal, data);
            File external = new File(appContext.getExternalFilesDir(null), "crash.log");
            writeAppend(external, data);
            Log.e(TAG, "crash log written to " + internal.getAbsolutePath());
        } catch (Throwable ignored) {
        }
    }

    private static void writeAppend(File f, byte[] data) {
        try (FileOutputStream out = new FileOutputStream(f, true)) {
            out.write(data);
            out.flush();
        } catch (Throwable ignored) {
        }
    }

    private static String ts() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date());
    }
}
