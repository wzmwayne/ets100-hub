package com.ets.unpack.repack;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class CrashGuard {
    public static final String PKG = "com.ets100.repackapp";
    private static final String TAG = "RepackCrash";
    private static volatile boolean installed = false;
    private static volatile Context appContext;

    private CrashGuard() {}

    public static void setContext(Context c) { appContext = c; }

    public static synchronized void install() {
        if (installed) return;
        installed = true;
        try {
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread t, Throwable e) {
                    log(t, e);
                    // 不交系统默认处理，阻止闪退
                }
            });
        } catch (Throwable ignored) {
        }
        try {
            Runtime.getRuntime().addShutdownHook(new Thread() {
                @Override
                public void run() {
                    write("shutdown hook fired");
                }
            });
        } catch (Throwable ignored) {
        }
        breadcrumb("CrashGuard installed");
    }

    public static void breadcrumb(String s) { write("[boot] " + s); }

    public static void log(Throwable e) { log(Thread.currentThread(), e); }

    public static void log(Thread t, Throwable e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("time=" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()));
        pw.println("thread=" + (t == null ? "?" : t.getName()));
        pw.println("pkg=" + PKG);
        if (e != null) e.printStackTrace(pw);
        pw.flush();
        try { Log.e(TAG, sw.toString()); } catch (Throwable ignored) {}
        write(sw.toString());
    }

    private static synchronized void write(String text) {
        List<File> list = candidates();
        for (File f : list) {
            try {
                File parent = f.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                FileWriter fw = new FileWriter(f, true);
                fw.write(text);
                fw.write("\n----------------\n");
                fw.flush();
                fw.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static List<File> candidates() {
        List<File> out = new ArrayList<File>();
        File ext = null;
        try { ext = Environment.getExternalStorageDirectory(); } catch (Throwable ignored) {}
        out.add(new File("/sdcard/Android/media/" + PKG + "/crash.log"));
        out.add(new File("/sdcard/Android/data/" + PKG + "/files/crash.log"));
        out.add(new File("/data/data/" + PKG + "/files/crash.log"));
        out.add(new File("/data/user/0/" + PKG + "/files/crash.log"));
        if (ext != null) {
            out.add(new File(ext, "Android/media/" + PKG + "/crash.log"));
            out.add(new File(ext, "Android/data/" + PKG + "/files/crash.log"));
        }
        Context c = appContext;
        if (c != null) {
            try {
                File[] m = c.getExternalMediaDirs();
                if (m != null && m.length > 0) out.add(new File(m[0], "crash.log"));
            } catch (Throwable ignored) {}
            try {
                File d = c.getExternalFilesDir(null);
                if (d != null) out.add(new File(d, "crash.log"));
            } catch (Throwable ignored) {}
            try { out.add(new File(c.getFilesDir(), "crash.log")); } catch (Throwable ignored) {}
        }
        return out;
    }
}
