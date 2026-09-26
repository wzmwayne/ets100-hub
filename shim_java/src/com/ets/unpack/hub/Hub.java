package com.ets.unpack.hub;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import dalvik.system.DexClassLoader;
import dalvik.system.DexFile;

/** EtsHub：通用扩展内核（浮球面板 + 反射工具 + 调试 HTTP API + 外部插件）。 */
public final class Hub implements Application.ActivityLifecycleCallbacks {

    public interface Feature {
        String title();
        void run(Activity a, Hub hub);
    }
    public interface Route { String handle(Map<String, String> q); }

    private static final String TAG = "EtsHub";
    private static final Hub I = new Hub();
    public static Hub get() { return I; }
    public static void install(Context c) { I.doInstall(c); }

    private Context app;
    private volatile boolean installed;
    private volatile Activity current;
    private Handler ui;
    private final List<Feature> features = new ArrayList<Feature>();
    private final Map<Activity, View> buttons = new WeakHashMap<Activity, View>();
    private final Map<String, Route> routes = new java.util.HashMap<String, Route>();

    public static class PluginInfo {
        public String name = "";
        public String version = "";
        public String description = "";
        public String className = "";
        public String file = "";
    }
    private final List<PluginInfo> pluginInfos = new ArrayList<PluginInfo>();
    private final List<Feature> pluginFeatures = new ArrayList<Feature>();
    private final List<String> pluginRoutePaths = new ArrayList<String>();

    private HttpApi http;
    private final int httpPort = 8765;

    public synchronized void doInstall(Context c) {
        this.app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        if (installed) { loadPlugins(); return; }
        installed = true;
        try { ui = new Handler(Looper.getMainLooper()); } catch (Throwable ignored) {}
        try { Features.register(this); } catch (Throwable t) { Log.e(TAG, "register", t); }
        try {
            if (app instanceof Application) ((Application) app).registerActivityLifecycleCallbacks(this);
        } catch (Throwable t) { Log.e(TAG, "lifecycle", t); }
        loadPlugins();
        try { startHttp(); } catch (Throwable t) { Log.e(TAG, "http", t); }
        dbg("Hub installed");
    }

    public void add(Feature f) { if (f != null) features.add(f); }
    public void addRoute(String path, Route r) { if (path != null && r != null) routes.put(path, r); }
    public List<Feature> features() { return features; }
    public Context ctx() { return app; }
    public Activity current() { return current; }
    public Handler ui() { return ui; }
    public int httpPort() { return httpPort; }

    public void toast(String s) {
        try { Toast.makeText(app, s, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
    }
    public int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, app.getResources().getDisplayMetrics());
    }

    // ---------------- lifecycle + float button ----------------
    public void onActivityCreated(Activity a, Bundle b) {}
    public void onActivityStarted(Activity a) {}
    public void onActivityResumed(Activity a) { current = a; attachButton(a); }
    public void onActivityPaused(Activity a) {}
    public void onActivityStopped(Activity a) {}
    public void onActivitySaveInstanceState(Activity a, Bundle b) {}
    public void onActivityDestroyed(Activity a) { if (current == a) current = null; detachButton(a); }

    public FrameLayout contentView(Activity a) {
        try { return (FrameLayout) a.findViewById(android.R.id.content); } catch (Throwable t) { return null; }
    }

    private void attachButton(final Activity a) {
        try {
            FrameLayout root = contentView(a); if (root == null) return;
            View old = buttons.get(a); if (old != null && old.getParent() == root) return;
            final TextView b = new TextView(a);
            b.setText("HUB"); b.setTextColor(Color.WHITE); b.setTextSize(11.0f);
            b.setGravity(Gravity.CENTER); b.setElevation(dp(8)); b.setAlpha(0.82f);
            GradientDrawable g = new GradientDrawable();
            g.setColor(0xE6207CF0); g.setCornerRadius(dp(24)); g.setStroke(dp(1), 0x66FFFFFF);
            b.setBackground(g);
            final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(46), dp(46));
            lp.gravity = Gravity.BOTTOM | Gravity.END; lp.setMargins(0, 0, dp(10), dp(150));
            b.setOnTouchListener(new View.OnTouchListener() {
                float sx, sy; int ox, oy; boolean moved;
                public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            sx = e.getRawX(); sy = e.getRawY();
                            ox = lp.leftMargin; oy = lp.bottomMargin; moved = false;
                            return true;
                        case MotionEvent.ACTION_MOVE: {
                            int dx = (int) (e.getRawX() - sx);
                            int dy = (int) (e.getRawY() - sy);
                            if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                            lp.leftMargin = Math.max(0, ox + dx);
                            lp.bottomMargin = Math.max(0, oy - dy);
                            v.setLayoutParams(lp);
                            return true;
                        }
                        case MotionEvent.ACTION_UP:
                            if (!moved) showPanel(a);
                            return true;
                        default:
                            return true;
                    }
                }
            });
            root.addView(b, lp); buttons.put(a, b);
        } catch (Throwable t) { Log.e(TAG, "attachButton", t); }
    }
    private void detachButton(Activity a) {
        try { View b = buttons.remove(a); if (b != null && b.getParent() instanceof ViewGroup) ((ViewGroup) b.getParent()).removeView(b); } catch (Throwable ignored) {}
    }

    // ---------------- panel ----------------
    private FrameLayout showCard(Activity a, View contentView) {
        FrameLayout root = contentView(a); if (root == null) return null;
        final FrameLayout overlay = new FrameLayout(a);
        overlay.setBackgroundColor(0xCC000000);
        ScrollView sv = new ScrollView(a);
        sv.addView(contentView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        flp.setMargins(dp(12), dp(56), dp(12), dp(56));
        overlay.addView(sv, flp);
        overlay.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { overlay.setVisibility(View.GONE); } });
        sv.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { } });
        root.addView(overlay, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        return overlay;
    }
    private GradientDrawable cardBg() {
        GradientDrawable card = new GradientDrawable();
        card.setColor(0xF51B1B1B); card.setCornerRadius(dp(16)); card.setStroke(dp(1), 0x33FFFFFF);
        return card;
    }
    public void showPanel(final Activity a) {
        LinearLayout col = new LinearLayout(a); col.setOrientation(LinearLayout.VERTICAL);
        int p = dp(18); col.setPadding(p, dp(18), p, dp(18));
        col.setBackground(cardBg());
        TextView title = new TextView(a); title.setText("EtsHub"); title.setTextColor(0xFFFFFFFF); title.setTextSize(22.0f); title.setGravity(Gravity.CENTER);
        col.addView(title);
        TextView sub = new TextView(a);
        sub.setText("HTTP=" + ((http != null && http.isRunning()) ? ("on " + firstIp() + ":" + httpPort) : "off"));
        sub.setTextColor(0xFF9E9E9E); sub.setTextSize(11.0f); sub.setGravity(Gravity.CENTER); sub.setPadding(0, dp(2), 0, dp(14));
        col.addView(sub);
        for (final Feature f : new ArrayList<Feature>(features)) {
            TextView btn = new TextView(a);
            btn.setText(f.title()); btn.setTextColor(0xFFEDEDED); btn.setTextSize(14.0f);
            btn.setGravity(Gravity.CENTER_VERTICAL); btn.setPadding(dp(16), dp(14), dp(16), dp(14));
            GradientDrawable g = new GradientDrawable(); g.setColor(0xFF2A2A2A); g.setCornerRadius(dp(10)); g.setStroke(dp(1), 0x22FFFFFF);
            btn.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(5), 0, dp(5));
            btn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { try { f.run(a, Hub.this); } catch (Throwable t) { toast("执行失败: " + t); Log.e(TAG, "feature", t); } }
            });
            col.addView(btn, lp);
        }
        final FrameLayout ov = showCard(a, col);
        TextView close = new TextView(a);
        close.setText("关闭"); close.setTextColor(0xFFFFFFFF); close.setTextSize(14.0f); close.setGravity(Gravity.CENTER);
        close.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable cg = new GradientDrawable(); cg.setColor(0xFF2F6FED); cg.setCornerRadius(dp(10));
        close.setBackground(cg);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, dp(12), 0, 0);
        close.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { if (ov != null) ov.setVisibility(View.GONE); } });
        col.addView(close, clp);
    }
    public void showText(Activity a, String title, String text) {
        LinearLayout col = new LinearLayout(a); col.setOrientation(LinearLayout.VERTICAL);
        int p = dp(18); col.setPadding(p, dp(18), p, dp(18));
        col.setBackground(cardBg());
        TextView t = new TextView(a); t.setText(title); t.setTextColor(0xFFFFFFFF); t.setTextSize(18.0f); t.setGravity(Gravity.CENTER); t.setPadding(0, 0, 0, dp(10));
        col.addView(t);
        TextView tv = new TextView(a); tv.setText(text == null ? "(null)" : text); tv.setTextColor(0xFFDADADA); tv.setTextSize(12.0f);
        tv.setTextIsSelectable(true);
        col.addView(tv);
        showCard(a, col);
    }

    // ---------------- helpers ----------------
    public String info(Activity a) {
        StringBuilder sb = new StringBuilder();
        try { sb.append("package: ").append(app.getPackageName()).append("\n"); } catch (Throwable ignored) {}
        try {
            android.content.pm.PackageInfo pi = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            sb.append("version: ").append(pi.versionName).append(" (").append(pi.versionCode).append(")\n");
        } catch (Throwable ignored) {}
        sb.append("sdk: ").append(Build.VERSION.SDK_INT).append("\n");
        sb.append("device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
        sb.append("activity: ").append(a == null ? (current == null ? "?" : current.getClass().getName()) : a.getClass().getName()).append("\n");
        sb.append("http: ").append(httpStatus()).append("\n");
        sb.append("url: http://").append(firstIp()).append(":").append(httpPort).append("/help\n");
        try { sb.append("plugins: ").append(pluginDir()).append("\n"); } catch (Throwable ignored) {}
        return sb.toString();
    }
    public File pluginDir() {
        File d = app.getExternalFilesDir("plugins");
        if (d == null) d = new File(app.getFilesDir(), "plugins");
        if (!d.exists()) d.mkdirs();
        return d;
    }
    public File logFile() {
        try { return new File(Environment.getExternalStorageDirectory(), "Android/media/" + app.getPackageName() + "/crash.log"); }
        catch (Throwable t) { return new File(app.getExternalFilesDir(null), "crash.log"); }
    }
    public String readLog() { return readFile(logFile()); }
    public void clearLog() {
        try { File f = logFile(); if (f.exists()) f.delete(); } catch (Throwable ignored) {}
        try { File f2 = new File(app.getExternalFilesDir(null), "crash.log"); if (f2.exists()) f2.delete(); } catch (Throwable ignored) {}
    }
    public boolean pkAudio() {
        try {
            Class<?> p7 = Class.forName("com.ets100.secondary.utils.p7");
            return (Boolean) p7.getMethod("h", String.class, boolean.class).invoke(null, "sp_pk_audio_open", true);
        } catch (Throwable t) { return true; }
    }
    public String setPkAudio(boolean v) {
        try {
            Class<?> p7 = Class.forName("com.ets100.secondary.utils.p7");
            p7.getMethod("s", String.class, boolean.class).invoke(null, "sp_pk_audio_open", v);
            return "sp_pk_audio_open = " + v;
        } catch (Throwable t) { return "set fail: " + t; }
    }
    public String togglePkAudio() { try { boolean v = pkAudio(); return setPkAudio(!v) + " (was " + v + ")"; } catch (Throwable t) { return "fail: " + t; } }
    public void restart() {
        try {
            Intent i = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
            if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK); app.startActivity(i); }
        } catch (Throwable ignored) {}
        try { android.os.Process.killProcess(android.os.Process.myPid()); } catch (Throwable ignored) {}
        System.exit(0);
    }

    // ---------------- public reflection utils (for plugins) ----------------
    public static Object call(Object o, String m, Class<?>[] ts, Object[] as) {
        if (o == null) return null;
        try {
            Method mm;
            try { mm = o.getClass().getMethod(m, ts); }
            catch (NoSuchMethodException e) { mm = o.getClass().getDeclaredMethod(m, ts); }
            mm.setAccessible(true);
            return mm.invoke(o, as);
        } catch (Throwable t) { return null; }
    }
    public static Object call(Object o, String m) { return call(o, m, new Class<?>[0], new Object[0]); }
    public static Object field(Object o, String fname) {
        if (o == null) return null;
        try { Field ff = o.getClass().getField(fname); ff.setAccessible(true); return ff.get(o); }
        catch (Throwable t) {
            try { Field ff = o.getClass().getDeclaredField(fname); ff.setAccessible(true); return ff.get(o); }
            catch (Throwable t2) { return null; }
        }
    }
    public static int num(Object o, int def) { return (o instanceof Number) ? ((Number) o).intValue() : def; }

    private static Map<?, ?> asMap(Object o) {
        if (o == null) return null;
        try {
            Class<?> c = o.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    try { f.setAccessible(true); Object v = f.get(o); if (v instanceof Map) return (Map<?, ?>) v; }
                    catch (Throwable ignored) {}
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return null;
    }
    private static Object scanMap(Map<?, ?> m, String className) {
        if (m == null) return null;
        try { for (Object v : m.values()) if (v != null && v.getClass().getName().equals(className)) return v; }
        catch (Throwable ignored) {}
        return null;
    }
    public Object findViewModel(Activity a, String className) {
        if (a == null) return null;
        try {
            Object store = call(a, "getViewModelStore");
            Object r = scanMap(asMap(store), className);
            if (r != null) return r;
        } catch (Throwable ignored) {}
        try {
            Class<?> c = a.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(a);
                        if (v == null) continue;
                        if (v instanceof Map) { Object r = scanMap((Map<?, ?>) v, className); if (r != null) return r; }
                        Object r2 = scanMap(asMap(v), className);
                        if (r2 != null) return r2;
                    } catch (Throwable ignored) {}
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return null;
    }
    public Object findPkFragment(Activity a) {
        if (a == null) return null;
        try {
            Object fm = call(a, "getSupportFragmentManager");
            Object frags = call(fm, "getFragments");
            if (frags instanceof List) {
                for (Object f : (List<?>) frags) {
                    if (f != null && f.getClass().getName().equals("com.ets100.syncword.ui.fragment.WordBattleFragment")) return f;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ---------------- debug log ----------------
    public void dbg(String s) {
        try {
            File f = new File(app.getFilesDir(), "hub.log");
            if (f.length() > 200000) writeFile(f, readFile(f).substring(Math.max(0, (int) f.length() - 100000)));
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            w.write(new java.text.SimpleDateFormat("MM-dd HH:mm:ss").format(new java.util.Date()) + " " + s + "\n");
            w.close();
        } catch (Throwable ignored) {}
    }
    public String dbgRead() { try { return readFile(new File(app.getFilesDir(), "hub.log")); } catch (Throwable t) { return ""; } }
    public void clearDbg() { try { new File(app.getFilesDir(), "hub.log").delete(); } catch (Throwable ignored) {} }
    private static String readFile(File f) {
        if (f == null || !f.exists()) return "";
        StringBuilder sb = new StringBuilder(); BufferedReader br = null;
        try { br = new BufferedReader(new FileReader(f)); String l; while ((l = br.readLine()) != null) sb.append(l).append("\n"); }
        catch (Throwable ignored) {} finally { try { if (br != null) br.close(); } catch (Throwable ignored) {} }
        return sb.toString();
    }
    public static void copyFile(File in, File out) throws Exception {
        java.io.FileInputStream fis = new java.io.FileInputStream(in);
        java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
        byte[] buf = new byte[65536];
        int r;
        while ((r = fis.read(buf)) > 0) fos.write(buf, 0, r);
        fos.close(); fis.close();
    }
    private static void writeFile(File f, String s) {
        try { java.io.FileWriter w = new java.io.FileWriter(f, false); w.write(s); w.close(); } catch (Throwable ignored) {}
    }

    // ---------------- HTTP API ----------------
    public String startHttp() {
        try {
            if (http == null) http = new HttpApi(httpPort, new HttpApi.Router() {
                public HttpApi.Response route(String uri, byte[] body) {
                    String p = uri;
                    int qm = uri.indexOf('?');
                    if (qm >= 0) p = uri.substring(0, qm);
                    if (p.equals("/") || p.equals("/web") || p.equals("/index.html")) {
                        return new HttpApi.Response(webUi(), "text/html; charset=utf-8");
                    }
                    return new HttpApi.Response(handleHttp(uri, body), "application/json; charset=utf-8");
                }
            });
            return http.start();
        } catch (Throwable t) { return "start fail: " + t; }
    }
    public String stopHttp() { try { if (http != null) { http.stop(); http = null; } } catch (Throwable ignored) {} return "stopped"; }
    public String httpStatus() { return (http != null && http.isRunning()) ? ("running on " + firstIp() + ":" + httpPort) : "stopped"; }

    private Object targetOf(String t) {
        if (t == null) return null;
        if ("activity".equals(t)) return current;
        if ("app".equals(t)) return app;
        if ("vm".equals(t)) return findViewModel(current, "com.ets100.syncword.viewmodel.WordBattleViewModel");
        if ("frag".equals(t)) return findPkFragment(current);
        return null;
    }
    private static Object convert(String s, Class<?> p) {
        try {
            if (p == String.class) return s;
            if (p == int.class || p == Integer.class) return Integer.valueOf(s);
            if (p == long.class || p == Long.class) return Long.valueOf(s);
            if (p == boolean.class || p == Boolean.class) return Boolean.valueOf(s);
            if (p == double.class || p == Double.class) return Double.valueOf(s);
            if (p == float.class || p == Float.class) return Float.valueOf(s);
            if (p == short.class || p == Short.class) return Short.valueOf(s);
            if (p == byte.class || p == Byte.class) return Byte.valueOf(s);
            if (p == char.class || p == Character.class) return Character.valueOf(s.isEmpty() ? ' ' : s.charAt(0));
        } catch (Throwable ignored) {}
        return null;
    }
    private Object invokeByName(Object o, String name, List<String> args) {
        if (o == null) return null;
        for (Method m : o.getClass().getMethods()) {
            if (!m.getName().equals(name)) continue;
            if (m.getParameterCount() != args.size()) continue;
            try {
                Class<?>[] ps = m.getParameterTypes();
                Object[] as = new Object[ps.length];
                for (int i = 0; i < ps.length; i++) as[i] = convert(args.get(i), ps[i]);
                m.setAccessible(true);
                return m.invoke(o, as);
            } catch (Throwable ignored) {}
        }
        return null;
    }
    public String callJson(Map<String, String> q) {
        Object o = targetOf(q.get("target"));
        String m = q.get("method");
        if (o == null) return "{\"ok\":false,\"error\":\"target not found\"}";
        if (m == null) return "{\"ok\":false,\"error\":\"method required\"}";
        List<String> args = new ArrayList<String>();
        for (int i = 0; ; i++) { String v = q.get("arg" + i); if (v == null) break; args.add(v); }
        Object r = invokeByName(o, m, args);
        return "{\"ok\":true,\"result\":" + json(String.valueOf(r)) + ",\"resultClass\":" + json(r == null ? "null" : r.getClass().getName()) + "}";
    }
    public String fieldJson(Map<String, String> q) {
        Object o = targetOf(q.get("target"));
        String n = q.get("name");
        if (o == null || n == null) return "{\"ok\":false,\"error\":\"target/name required\"}";
        Object v = field(o, n);
        return "{\"ok\":true,\"value\":" + json(String.valueOf(v)) + ",\"valueClass\":" + json(v == null ? "null" : v.getClass().getName()) + "}";
    }
    public String fragJson() {
        StringBuilder sb = new StringBuilder("[");
        try {
            Object fm = call(current, "getSupportFragmentManager");
            Object fs = call(fm, "getFragments");
            if (fs instanceof List) {
                boolean first = true;
                for (Object x : (List<?>) fs) {
                    if (x == null) continue;
                    if (!first) sb.append(","); first = false;
                    sb.append(json(x.getClass().getName()));
                }
            }
        } catch (Throwable ignored) {}
        return "{\"fragments\":" + sb.append("]").toString() + "}";
    }
    public String vmDumpJson() {
        Activity a = current; if (a == null) return "{\"ok\":false,\"reason\":\"no activity\"}";
        Object vm = findViewModel(a, "com.ets100.syncword.viewmodel.WordBattleViewModel");
        if (vm == null) return "{\"ok\":false,\"reason\":\"no vm\"}";
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"class\":" + json(vm.getClass().getName()) + ",\"fields\":[");
        boolean first = true;
        try {
            Class<?> c = vm.getClass();
            while (c != null && c != Object.class) {
                for (Field fd : c.getDeclaredFields()) {
                    try {
                        fd.setAccessible(true);
                        Object v = fd.get(vm);
                        if (!first) sb.append(","); first = false;
                        String s = String.valueOf(v);
                        if (s.length() > 200) s = s.substring(0, 200) + "...";
                        sb.append("{\"name\":").append(json(fd.getName())).append(",\"type\":").append(json(fd.getType().getName())).append(",\"value\":").append(json(s)).append("}");
                    } catch (Throwable ignored) {}
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return sb.append("]}").toString();
    }
    public String pluginsJson() {
        StringBuilder sb = new StringBuilder("{\"dir\":" + json(pluginDir().getAbsolutePath()) + ",\"plugins\":[");
        for (int i = 0; i < pluginInfos.size(); i++) {
            PluginInfo p = pluginInfos.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"name\":").append(json(p.name)).append(",\"version\":").append(json(p.version))
              .append(",\"description\":").append(json(p.description)).append(",\"class\":").append(json(p.className))
              .append(",\"file\":").append(json(p.file)).append("}");
        }
        sb.append("],\"files\":[");
        File[] fs = pluginDir().listFiles();
        if (fs != null) { for (int i = 0; i < fs.length; i++) { if (i > 0) sb.append(","); sb.append(json(fs[i].getName() + " (" + fs[i].length() + ")")); } }
        return sb.append("]}").toString();
    }
    public List<PluginInfo> pluginInfos() { return pluginInfos; }
    public String helpJson() {
        String[] eps = {"/","/help","/info","/dbg","/dbg/clear","/frag","/view/dump?depth=6","/vm/dump",
                "/call?target=vm|frag|activity|app&method=NAME&arg0=..","/field?target=vm&name=NAME",
                "/toast?msg=..","/pk/audio?value=1","/plugins","/restart"};
        StringBuilder sb = new StringBuilder("{\"app\":" + json(app.getPackageName()) + ",\"endpoints\":[");
        for (int i = 0; i < eps.length; i++) { if (i > 0) sb.append(","); sb.append(json(eps[i])); }
        return sb.append("],\"routes\":" + json(routes.keySet().toString()) + "}").toString();
    }
    private void dumpViewRec(StringBuilder sb, View v, int maxDepth, int d) {
        if (v == null || d > maxDepth) return;
        for (int i = 0; i < d; i++) sb.append("  ");
        sb.append(v.getClass().getName());
        try { int id = v.getId(); if (id != 0) sb.append(" #").append(app.getResources().getResourceEntryName(id)); } catch (Throwable ignored) {}
        if (v.getVisibility() != View.VISIBLE) sb.append(v.getVisibility() == View.GONE ? " [gone]" : " [invisible]");
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.length() > 0) sb.append(" text=").append(t.length() > 50 ? t.subSequence(0, 50) : t);
        }
        sb.append("\n");
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) dumpViewRec(sb, g.getChildAt(i), maxDepth, d + 1);
        }
    }
    public String dumpView(int depth) {
        View root = null;
        try { if (current != null && current.getWindow() != null) root = current.getWindow().getDecorView(); } catch (Throwable ignored) {}
        StringBuilder sb = new StringBuilder();
        dumpViewRec(sb, root, depth, 0);
        return sb.toString();
    }
    public String webUi() {
        StringBuilder s = new StringBuilder();
        s.append("<!doctype html><html><head><meta charset=utf-8><meta name=viewport content=width=device-width,initial-scale=1>");
        s.append("<title>EtsHub 插件管理</title><style>");
        s.append("body{font-family:sans-serif;background:#111;color:#eee;margin:0;padding:16px}");
        s.append("h3{margin:0 0 8px}.card{background:#1e1e1e;border-radius:12px;padding:12px;margin:8px 0}");
        s.append("button{background:#2f6fed;color:#fff;border:0;border-radius:8px;padding:8px 12px;margin:4px 6px 4px 0}");
        s.append("button.bad{background:#e53935}button.gh{background:#333}small{color:#aaa}");
        s.append("</style></head><body><h3>EtsHub 插件管理</h3>");
        s.append("<div class=card id=dir></div>");
        s.append("<div class=card><input type=file id=f accept=.dex><br>");
        s.append("<button onclick=up()>上传安装</button><button class=gh onclick=restart()>重启应用</button></div>");
        s.append("<div id=list></div>");
        s.append("<script>");
        s.append("function esc(x){return (x+'').replace(/</g,'&lt;')}");
        s.append("function refresh(){fetch('/plugins').then(function(r){return r.json()}).then(function(d){");
        s.append("document.getElementById('dir').textContent='目录: '+d.dir;");
        s.append("var h='';var pl=d.plugins||[];");
        s.append("for(var i=0;i<pl.length;i++){var p=pl[i];");
        s.append("h+='<div class=card><b>'+esc(p.name)+'</b> v'+esc(p.version)+'<br><small>'+esc(p.description)+'</small><br><small>'+esc(p.file)+'</small><br>';");
        s.append("h+='<button data-op=rm data-n='+p.file+'>卸载</button></div>';}");
        s.append("h+='<div class=card><b>目录文件</b><br>';var fs=d.files||[];");
        s.append("for(var j=0;j<fs.length;j++){var fn=fs[j].split(' ')[0];h+='<small>'+esc(fn)+'</small> <button data-op=rm data-n='+fn+'>删除</button><br>';}");
        s.append("h+='</div>';document.getElementById('list').innerHTML=h;");
        s.append("var bs=document.querySelectorAll('button[data-op]');");
        s.append("for(var k=0;k<bs.length;k++){bs[k].addEventListener('click',function(){rm(this.getAttribute('data-n'))})}");
        s.append("})}");
        s.append("function restartNow(){try{fetch('/restart').catch(function(){})}catch(e){}alert('应用正在重启，约 10 秒后请手动刷新本页面。')}");
        s.append("function askRestart(msg){if(confirm(msg+' 需要重启应用才会生效，现在重启吗？')){restartNow()}else{alert(msg+' 已保存，重启应用后生效，请刷新本页面查看最新列表。');refresh()}}");
        s.append("function up(){var f=document.getElementById('f').files[0];if(!f){alert('请选择 dex 文件');return;}");
        s.append("f.arrayBuffer().then(function(b){return fetch('/plugins/upload?name='+encodeURIComponent(f.name),{method:'POST',body:b})}).then(function(r){return r.json()}).then(function(j){");
        s.append("if(j&&j.ok===false){alert('上传失败: '+(j.error||'未知错误'));return;}askRestart('插件 '+((j&&j.installed)||f.name)+' 已上传安装。')}).catch(function(e){alert('上传出错: '+e)})}");
        s.append("function doRm(n){return fetch('/plugins/remove?name='+encodeURIComponent(n)).then(function(r){return r.json()}).then(function(j){");
        s.append("if(j&&j.ok===false){alert('删除失败: '+(j.error||'未知错误'));return;}askRestart('插件 '+n+' 已卸载。')}).catch(function(e){alert('删除出错: '+e)})}");
        s.append("function rm(n){if(confirm('确定卸载 '+n+' 吗？'))doRm(n)}");
        s.append("function restart(){if(confirm('确定重启应用吗？'))restartNow()}");
        s.append("refresh();");
        s.append("</script></body></html>");
        return s.toString();
    }

    private String handleHttp(String uri, byte[] body) {
        String path = uri; Map<String, String> q = new java.util.HashMap<String, String>();
        int qm = uri.indexOf('?');
        if (qm >= 0) {
            path = uri.substring(0, qm);
            for (String kv : uri.substring(qm + 1).split("&")) {
                int e = kv.indexOf('=');
                if (e >= 0) q.put(kv.substring(0, e), urlDecode(kv.substring(e + 1)));
            }
        }
        try {
            if ("/health".equals(path)) return "{\"ok\":true,\"app\":\"" + app.getPackageName() + "\"}";
            if ("/".equals(path) || "/help".equals(path)) return helpJson();
            if ("/info".equals(path)) return "{\"info\":" + json(info(current)) + "}";
            if ("/log".equals(path)) return "{\"log\":" + json(readLog()) + "}";
            if ("/log/clear".equals(path)) { clearLog(); return "{\"ok\":true}"; }
            if ("/dbg".equals(path)) return "{\"log\":" + json(dbgRead()) + "}";
            if ("/dbg/clear".equals(path)) { clearDbg(); return "{\"ok\":true}"; }
            if ("/frag".equals(path)) return fragJson();
            if ("/view/dump".equals(path)) return "{\"view\":" + json(dumpView(parseInt(q.get("depth"), 6))) + "}";
            if ("/vm/dump".equals(path)) return vmDumpJson();
            if ("/call".equals(path)) return callJson(q);
            if ("/field".equals(path)) return fieldJson(q);
            if ("/toast".equals(path)) { toast(q.get("msg")); return "{\"ok\":true}"; }
            if ("/anim".equals(path)) {
                if (q.containsKey("speed") || q.containsKey("correct") || q.containsKey("wrong")) {
                    int cm = Tuning.getCorrectMs(), wm = Tuning.getWrongMs(); float sp = Tuning.getSpeed();
                    if (q.containsKey("correct")) cm = parseInt(q.get("correct"), cm);
                    if (q.containsKey("wrong")) wm = parseInt(q.get("wrong"), wm);
                    if (q.containsKey("speed")) { try { sp = Float.parseFloat(q.get("speed")); } catch (Throwable ignored) {} }
                    return "{\"result\":" + json(Tuning.setConfig(cm, wm, sp)) + "}";
                }
                return "{\"config\":" + json(Tuning.getConfig()) + "}";
            }
            if ("/pk/audio".equals(path)) {
                if (q.containsKey("value")) return "{\"result\":" + json(setPkAudio(truthy(q.get("value")))) + "}";
                return "{\"result\":" + json(togglePkAudio()) + ",\"value\":" + pkAudio() + "}";
            }
            if ("/plugins".equals(path)) return pluginsJson();
            if ("/plugins/install".equals(path)) {
                String src = q.get("src");
                if (src == null || src.length() == 0) return "{\"ok\":false,\"error\":\"src required\"}";
                File in = new File(src);
                if (!in.exists() || !in.isFile()) return "{\"ok\":false,\"error\":\"src not found\"}";
                String nm = q.get("name");
                if (nm == null || nm.length() == 0) nm = in.getName();
                File dst = new File(pluginDir(), nm);
                copyFile(in, dst);
                return "{\"ok\":true,\"installed\":" + json(nm) + ",\"size\":" + dst.length() + ",\"needRestart\":true}";
            }
            if ("/plugins/upload".equals(path)) {
                String nm = q.get("name");
                if (nm == null || nm.length() == 0) nm = "uploaded.dex";
                if (!nm.endsWith(".dex")) nm = nm + ".dex";
                if (body == null || body.length == 0) return "{\"ok\":false,\"error\":\"empty body\"}";
                File dst = new File(pluginDir(), nm);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(dst);
                fos.write(body); fos.close();
                return "{\"ok\":true,\"installed\":" + json(nm) + ",\"size\":" + dst.length() + ",\"needRestart\":true}";
            }
            if ("/plugins/remove".equals(path)) {
                String nm = q.get("name");
                if (nm == null || nm.length() == 0) return "{\"ok\":false,\"error\":\"name required\"}";
                File dst = new File(pluginDir(), nm);
                boolean ok = dst.exists() && dst.delete();
                return "{\"ok\":" + ok + ",\"removed\":" + json(nm) + ",\"needRestart\":true}";
            }
            if ("/restart".equals(path)) { restart(); return "{\"ok\":true}"; }
            Route r = routes.get(path);
            if (r != null) return r.handle(q);
        } catch (Throwable t) { return "{\"ok\":false,\"error\":" + json(String.valueOf(t)) + "}"; }
        return "{\"ok\":false,\"error\":\"not found\"}";
    }
    private static boolean truthy(String s) { return s != null && (s.equals("1") || s.equalsIgnoreCase("true") || s.equalsIgnoreCase("on") || s.equalsIgnoreCase("yes")); }
    private static int parseInt(String s, int def) { try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return def; } }
    public static String json(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
            }
        }
        return sb.append("\"").toString();
    }
    private static String urlDecode(String s) { try { return java.net.URLDecoder.decode(s, "UTF-8"); } catch (Throwable t) { return s; } }
    public String firstIp() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (Throwable ignored) {}
        return "127.0.0.1";
    }

    // ---------------- external plugins ----------------
    public synchronized void loadPlugins() {
        try { features.removeAll(pluginFeatures); } catch (Throwable ignored) {}
        pluginFeatures.clear();
        for (String p : pluginRoutePaths) { try { routes.remove(p); } catch (Throwable ignored) {} }
        pluginRoutePaths.clear();
        pluginInfos.clear();
        File dir; try { dir = pluginDir(); } catch (Throwable t) { return; }
        File[] ds = dir.listFiles(); if (ds == null) return;
        for (File d : ds) {
            if (!d.getName().endsWith(".dex")) continue;
            try {
                File opt = new File(app.getCodeCacheDir(), "hubopt"); if (!opt.exists()) opt.mkdirs();
                DexClassLoader cl = new DexClassLoader(d.getAbsolutePath(), opt.getAbsolutePath(), null, Hub.class.getClassLoader());
                DexFile df = DexFile.loadDex(d.getAbsolutePath(), null, 0);
                Enumeration<String> en = df.entries();
                while (en.hasMoreElements()) {
                    String cn = en.nextElement();
                    try {
                        Class<?> c = Class.forName(cn, true, cl);
                        if (IPlugin.class.isAssignableFrom(c) && c != IPlugin.class) {
                            int beforeF = features.size();
                            java.util.Set<String> beforeR = new java.util.HashSet<String>(routes.keySet());
                            IPlugin p = (IPlugin) c.newInstance();
                            p.install(this);
                            for (int i = beforeF; i < features.size(); i++) pluginFeatures.add(features.get(i));
                            for (String k : routes.keySet()) if (!beforeR.contains(k)) pluginRoutePaths.add(k);
                            PluginInfo pi = new PluginInfo();
                            pi.className = cn; pi.file = d.getName();
                            try { pi.name = p.name(); } catch (Throwable ignored) {}
                            try { pi.version = p.version(); } catch (Throwable ignored) {}
                            try { pi.description = p.description(); } catch (Throwable ignored) {}
                            pluginInfos.add(pi);
                            Log.i(TAG, "plugin installed: " + cn);
                        }
                    } catch (Throwable ignored) {}
                }
                try { df.close(); } catch (Throwable ignored) {}
            } catch (Throwable t) { Log.e(TAG, "loadPlugins " + d, t); }
        }
    }
}
