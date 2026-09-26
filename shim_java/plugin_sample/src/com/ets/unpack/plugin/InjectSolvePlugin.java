package com.ets.unpack.plugin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ets.unpack.hub.Hub;
import com.ets.unpack.hub.IPlugin;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** 方案A注入：手动按钮 / 轮询自动注入（可设延迟与轮询间隔，成功即停）。 */
public class InjectSolvePlugin implements IPlugin {

    private static final String VM = "com.ets100.syncword.viewmodel.WordBattleViewModel";
    private static final String PREFS = "etshub_inject";

    private Hub hub;
    private volatile int enable = 0;
    private volatile int auto = 0;
    private volatile int useTime = 30000;   // ms
    private volatile int delayMs = 1000;    // 进入PK后延迟
    private volatile int intervalMs = 50;   // 轮询间隔
    private volatile int skipMatch = 0;     // 1=跳过配对与配对成功展示
    private java.lang.ref.WeakReference<Activity> skippedActivity = null;
    private final java.util.Map<Activity, Object> navCache = new WeakHashMap<Activity, Object>();
    private volatile String injectedSerial = "";
    private volatile String curSerial = "";
    private volatile long startAt = 0;
    private volatile long nextAt = 0;
    private final Map<Activity, TextView> btns = new WeakHashMap<Activity, TextView>();
    private final Map<Activity, TextView> stats = new WeakHashMap<Activity, TextView>();

    public String name() { return "Inject 注入答题"; }
    public String version() { return "1.5.4"; }
    public String description() { return "手动按钮 或 轮询自动注入(可配延迟/间隔，成功即停)"; }

    public void install(final Hub hub) {
        this.hub = hub;
        load();
        hub.add(new Hub.Feature() {
            public String title() { return "注入答题配置"; }
            public void run(Activity a, Hub h) { dialog(a); }
        });
        hub.add(new Hub.Feature() {
            public String title() { return "立即注入答题"; }
            public void run(Activity a, Hub h) { h.toast(inject(a)); }
        });
        hub.add(new Hub.Feature() {
            public String title() { return "查看注入配置"; }
            public void run(Activity a, Hub h) { h.showText(a, "注入答题配置", cfgJson()); }
        });
        hub.addRoute("/inject", new Hub.Route() {
            public String handle(Map<String, String> q) {
                if (q.containsKey("enable") || q.containsKey("use_time") || q.containsKey("auto")
                        || q.containsKey("delay") || q.containsKey("interval") || q.containsKey("skip")) {
                    setCfg(pi(q.get("enable"), enable), pi(q.get("use_time"), useTime), pi(q.get("auto"), auto),
                           pi(q.get("delay"), delayMs), pi(q.get("interval"), intervalMs), pi(q.get("skip"), skipMatch));
                }
                return cfgJson();
            }
        });
        hub.addRoute("/inject/run", new Hub.Route() {
            public String handle(Map<String, String> q) {
                final Activity a = hub.current();
                if (hub.ui() != null) hub.ui().post(new Runnable() { public void run() { hub.dbg("manual inject: " + inject(a)); } });
                return "{\"scheduled\":true}";
            }
        });
        startLoop();
        hub.dbg("InjectSolvePlugin 1.4.0 installed");
    }

    private void dialog(Activity a) {
        int p = hub.dp(16);
        LinearLayout ll = new LinearLayout(a);
        ll.setOrientation(LinearLayout.VERTICAL); ll.setPadding(p, p, p, p);
        ll.addView(label(a, "手动按钮开关（0关 1开）"));
        final EditText e1 = input(a, String.valueOf(enable)); ll.addView(e1);
        ll.addView(label(a, "轮询自动注入（0关 1开）"));
        final EditText e3 = input(a, String.valueOf(auto)); ll.addView(e3);
        ll.addView(label(a, "进入PK后延迟 ms"));
        final EditText e4 = input(a, String.valueOf(delayMs)); ll.addView(e4);
        ll.addView(label(a, "轮询间隔 ms（>=10）"));
        final EditText e5 = input(a, String.valueOf(intervalMs)); ll.addView(e5);
        ll.addView(label(a, "跳过配对+配对成功展示（0关 1开）"));
        final EditText e6 = input(a, String.valueOf(skipMatch)); ll.addView(e6);
        ll.addView(label(a, "上报用时 ms（内部按秒写入）"));
        final EditText e2 = input(a, String.valueOf(useTime)); ll.addView(e2);
        new AlertDialog.Builder(a).setTitle("注入答题配置").setView(ll)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        setCfg(pi(e1.getText().toString(), enable), pi(e2.getText().toString(), useTime),
                               pi(e3.getText().toString(), auto), pi(e4.getText().toString(), delayMs), pi(e5.getText().toString(), intervalMs),
                               pi(e6.getText().toString(), skipMatch));
                        hub.toast(cfgJson());
                    }
                }).setNegativeButton("取消", null).show();
    }
    private TextView label(Activity a, String s) {
        TextView t = new TextView(a); t.setText(s); t.setTextColor(0xFFBBBBBB); t.setTextSize(12f); t.setPadding(0, hub.dp(8), 0, hub.dp(4));
        return t;
    }
    private EditText input(Activity a, String v) {
        EditText e = new EditText(a); e.setText(v); e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        return e;
    }

    private void startLoop() {
        if (hub.ui() == null) return;
        final Runnable task = new Runnable() {
            public void run() {
                try { tick(); } catch (Throwable t) { hub.dbg("inject ui err " + t); }
                if (hub.ui() != null) hub.ui().postDelayed(this, auto == 1 ? 20 : 500);
            }
        };
        hub.ui().postDelayed(task, auto == 1 ? 20 : 500);
    }

    private void tick() {
        Activity a = hub.current();
        if (a == null) return;
        if (skipMatch == 1) { try { trySkipMatch(a); } catch (Throwable ignored) {} }
        Object vm = hub.findViewModel(a, VM);
        if (vm == null) { hideButton(a); hideStatus(a); curSerial = ""; startAt = 0; nextAt = 0; return; }
        if (auto == 1) {
            hideButton(a);
            long now = System.currentTimeMillis();
            String serial = String.valueOf(Hub.call(vm, "a0"));
            if (serial.length() > 0 && !serial.equals(curSerial)) {
                curSerial = serial;
                startAt = now + Math.max(0, delayMs);
                nextAt = startAt;
            }
            if (serial.length() > 0 && serial.equals(injectedSerial)) {
                showStatus(a, "注入成功 · 已停止\nserial=" + serial);
                return;
            }
            if (startAt == 0) { showStatus(a, "等待进入PK…"); return; }
            if (now < startAt) { showStatus(a, "延迟中 " + (startAt - now) + "ms 后开始轮询"); return; }
            if (now < nextAt) { showStatus(a, "轮询等待 " + (nextAt - now) + "ms（间隔 " + intervalMs + "ms）"); return; }
            nextAt = now + Math.max(10, intervalMs);
            String r = inject(a);
            if (r.startsWith("injected ")) {
                injectedSerial = serial;
                showStatus(a, "注入成功 · 已停止\n" + r);
            } else {
                showStatus(a, "注入轮询中… 间隔" + intervalMs + "ms\n" + r);
            }
            return;
        }
        hideStatus(a);
        if (enable == 1) showButton(a); else hideButton(a);
    }

    // ---------- 手动按钮 ----------
    private TextView ensureButton(Activity a) {
        FrameLayout root = hub.contentView(a); if (root == null) return null;
        TextView tv = btns.get(a);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.START; lp.setMargins(hub.dp(10), hub.dp(64), 0, 0);
        if (tv == null) {
            tv = new TextView(a);
            tv.setText("注入并胜利"); tv.setTextColor(Color.WHITE); tv.setTextSize(13f);
            GradientDrawable g = new GradientDrawable();
            g.setColor(0xF0E53935); g.setCornerRadius(hub.dp(10)); g.setStroke(hub.dp(1), 0x66FFFFFF);
            tv.setBackground(g); tv.setPadding(hub.dp(12), hub.dp(8), hub.dp(12), hub.dp(8));
            tv.setElevation(hub.dp(6));
            tv.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { hub.toast(inject(hub.current())); } });
            root.addView(tv, lp); btns.put(a, tv);
        } else if (tv.getParent() != root) {
            if (tv.getParent() instanceof FrameLayout) ((FrameLayout) tv.getParent()).removeView(tv);
            root.addView(tv, lp);
        }
        return tv;
    }
    private void showButton(Activity a) { TextView tv = ensureButton(a); if (tv != null) tv.setVisibility(View.VISIBLE); }
    private void hideButton(Activity a) { TextView tv = btns.get(a); if (tv != null) tv.setVisibility(View.GONE); }

    // ---------- 状态浮标 ----------
    private TextView ensureStatus(Activity a) {
        FrameLayout root = hub.contentView(a); if (root == null) return null;
        TextView tv = stats.get(a);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.START; lp.setMargins(hub.dp(10), hub.dp(64), 0, 0);
        if (tv == null) {
            tv = new TextView(a);
            tv.setTextColor(Color.WHITE); tv.setTextSize(12f);
            GradientDrawable g = new GradientDrawable();
            g.setColor(0xE01E88E5); g.setCornerRadius(hub.dp(10)); g.setStroke(hub.dp(1), 0x66FFFFFF);
            tv.setBackground(g); tv.setPadding(hub.dp(10), hub.dp(7), hub.dp(10), hub.dp(7));
            tv.setElevation(hub.dp(6));
            root.addView(tv, lp); stats.put(a, tv);
        } else if (tv.getParent() != root) {
            if (tv.getParent() instanceof FrameLayout) ((FrameLayout) tv.getParent()).removeView(tv);
            root.addView(tv, lp);
        }
        return tv;
    }
    private void showStatus(Activity a, String s) { TextView tv = ensureStatus(a); if (tv != null) { tv.setText(s); tv.setVisibility(View.VISIBLE); } }
    private void hideStatus(Activity a) { TextView tv = stats.get(a); if (tv != null) tv.setVisibility(View.GONE); }

    /** 核心：逐题写入满分成绩并提交。成功返回以 "injected " 开头。 */
    public String inject(Activity a) {
        if (a == null) return "no activity";
        Object vm = hub.findViewModel(a, VM);
        if (vm == null) return "no pk viewmodel";
        Object structs = Hub.call(vm, "t0");
        if (!(structs instanceof List)) return "no structs";
        List<?> l = (List<?>) structs;
        if (l.isEmpty()) return "empty structs";
        int point = Hub.num(Hub.call(vm, "N"), 10000);
        int done = 0;
        for (int i = 0; i < l.size(); i++) {
            Object st = l.get(i);
            Object qs = Hub.call(st, "getQuestion");
            if (!(qs instanceof List) || ((List<?>) qs).isEmpty()) continue;
            Object q = ((List<?>) qs).get(0);
            String ans = (String) Hub.call(q, "getAnswer");
            int wid = Hub.num(Hub.call(q, "getWordId"), 0);
            int qid = Hub.num(Hub.call(q, "getId"), 0);
            Hub.call(vm, "K0", new Class<?>[]{int.class}, new Object[]{Integer.valueOf(i)});
            Hub.call(vm, "z", new Class<?>[]{int.class, String.class, int.class, int.class},
                    new Object[]{Integer.valueOf(point), ans, Integer.valueOf(wid), Integer.valueOf(qid)});
            done++;
        }
        if (done == 0) return "no questions";
        int seconds = useTime / 1000;
        if (seconds < 0) seconds = 0;
        Hub.call(vm, "C0", new Class<?>[]{int.class}, new Object[]{Integer.valueOf(seconds)});
        Object serial = Hub.call(vm, "a0");
        Hub.call(vm, "e1");
        hub.dbg("injected " + done + "/" + l.size() + " point=" + point + " useTime=" + seconds + "s serial=" + serial);
        return "injected " + done + "/" + l.size() + " useTime=" + seconds + "s";
    }

    private int id(String name) { try { return hub.ctx().getResources().getIdentifier(name, "id", hub.ctx().getPackageName()); } catch (Throwable t) { return 0; } }
    private String lastLog = "";
    private long lastLogAt = 0;
    private void dlog(String s) {
        long now = System.currentTimeMillis();
        if (s.equals(lastLog) && now - lastLogAt < 3000) return;
        lastLog = s; lastLogAt = now;
        hub.dbg(s);
    }
    /** 视图树里找 FragmentContainerView，其 getFragment() 即 NavHostFragment；失败再 BFS 兜底。 */
    private Object findNavHost(Activity a) {
        try {
            View root = a.getWindow().getDecorView();
            java.util.ArrayDeque<View> q = new java.util.ArrayDeque<View>();
            q.add(root);
            int n = 0;
            while (!q.isEmpty() && n < 8000) {
                View v = q.poll(); n++;
                if (v.getClass().getName().equals("androidx.fragment.app.FragmentContainerView")) {
                    Object f = Hub.call(v, "getFragment");
                    if (f != null) return f;
                }
                if (v instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) q.add(g.getChildAt(i));
                }
            }
        } catch (Throwable ignored) {}
        return findNavHostBfs(a);
    }
    private Object findNavHostBfs(Activity a) {
        try {
            java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<Object, Boolean>();
            java.util.ArrayDeque<Object> q = new java.util.ArrayDeque<Object>();
            java.util.ArrayDeque<Integer> dq = new java.util.ArrayDeque<Integer>();
            q.add(a); dq.add(0); seen.put(a, Boolean.TRUE);
            int visited = 0;
            while (!q.isEmpty() && visited < 30000) {
                Object o = q.poll(); int d = dq.poll(); visited++;
                if (o == null) continue;
                if (o.getClass().getName().equals("androidx.navigation.fragment.NavHostFragment")) return o;
                if (d >= 8) continue;
                Class<?> c = o.getClass();
                if (c.getName().startsWith("java.lang.reflect.") || c == Class.class) continue;
                while (c != null && c != Object.class) {
                    for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                        try {
                            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                            f.setAccessible(true);
                            Object v = f.get(o);
                            if (v == null) continue;
                            Class<?> vc = v.getClass();
                            if (vc.isPrimitive() || vc == String.class || vc.isArray()) continue;
                            if (seen.containsKey(v)) continue;
                            seen.put(v, Boolean.TRUE);
                            q.add(v); dq.add(d + 1);
                        } catch (Throwable ignored) {}
                    }
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
    private static boolean hasIterable(Class<?> c) {
        try { c.getMethod("iterator"); return true; } catch (Throwable t) { return false; }
    }
    private int destIdFor(Object nav, String needle) {
        try {
            Object graph = null;
            for (java.lang.reflect.Method m : nav.getClass().getMethods()) {
                if (m.getParameterCount() != 0) continue;
                Class<?> r = m.getReturnType();
                if (r.getName().startsWith("androidx.navigation.") && hasIterable(r)) {
                    m.setAccessible(true);
                    graph = m.invoke(nav);
                    if (graph != null) break;
                }
            }
            if (graph == null) return 0;
            Object it = graph.getClass().getMethod("iterator").invoke(graph);
            if (it == null) return 0;
            java.lang.reflect.Method hasNext = it.getClass().getMethod("hasNext");
            java.lang.reflect.Method next = it.getClass().getMethod("next");
            int guard = 0;
            while (guard++ < 300 && Boolean.TRUE.equals(hasNext.invoke(it))) {
                Object node = next.invoke(it);
                if (node == null) continue;
                String label = null, cn = null; int id = 0;
                for (java.lang.reflect.Method m : node.getClass().getMethods()) {
                    if (m.getParameterCount() != 0) continue;
                    Class<?> r = m.getReturnType();
                    try {
                        if (r == String.class) {
                            m.setAccessible(true);
                            String s = (String) m.invoke(node);
                            if (s != null) { if (label == null) label = s; else cn = s; }
                        } else if (r == int.class || r == Integer.class) {
                            m.setAccessible(true);
                            Object v = m.invoke(node);
                            if (v != null && id == 0) id = ((Number) v).intValue();
                        }
                    } catch (Throwable ignored) {}
                }
                if ((label != null && label.contains(needle)) || (cn != null && cn.contains(needle))) return id;
            }
        } catch (Throwable ignored) {}
        return 0;
    }
    private Object navigate(Object nav, int id) {
        try {
            for (java.lang.reflect.Method m : nav.getClass().getMethods()) {
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == int.class && m.getName().equals("s")) {
                    m.setAccessible(true);
                    return m.invoke(nav, Integer.valueOf(id));
                }
            }
            for (java.lang.reflect.Method m : nav.getClass().getMethods()) {
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == int.class) {
                    m.setAccessible(true);
                    return m.invoke(nav, Integer.valueOf(id));
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
    /** skip_match=1：vm+题目+serial 就绪且还没进战斗 → 直接导航到 battleFragment。 */
    private void trySkipMatch(Activity a) {
        Object vm = hub.findViewModel(a, VM);
        if (vm == null) return;
        if (hub.findPkFragment(a) != null) return;
        Object structs = Hub.call(vm, "t0");
        if (!(structs instanceof java.util.List) || ((java.util.List<?>) structs).isEmpty()) return;
        String serial = String.valueOf(Hub.call(vm, "a0"));
        if (serial == null || serial.length() == 0 || "null".equals(serial)) return;
        if (skippedActivity != null && skippedActivity.get() == a) return;
        Object nav = navCache.get(a);
        if (nav == null) {
            Object host = findNavHost(a);
            if (host == null) { dlog("skip: no navhost(bfs)"); return; }
            nav = Hub.call(host, "d");
            if (nav == null) { dlog("skip: host.d() null cls=" + host.getClass().getName()); return; }
            navCache.put(a, nav);
        }
        int destId = destIdFor(nav, "WordBattleFragment");
        if (destId == 0) { dlog("skip: dest not found nav=" + nav.getClass().getName()); return; }
        Object r = navigate(nav, destId);
        skippedActivity = new java.lang.ref.WeakReference<Activity>(a);
        hub.dbg("skip match -> destId=" + destId + " r=" + r + " serial=" + serial);
    }

    private void setCfg(int e, int u, int au, int d, int iv, int sk) {
        enable = e == 1 ? 1 : 0;
        auto = au == 1 ? 1 : 0;
        if (u < 0) u = 0;
        if (d < 0) d = 0;
        if (iv < 10) iv = 10;
        useTime = u; delayMs = d; intervalMs = iv; skipMatch = sk == 1 ? 1 : 0;
        try {
            SharedPreferences sp = hub.ctx().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            sp.edit().putInt("enable", enable).putInt("auto", auto).putInt("use_time", useTime)
              .putInt("delay", delayMs).putInt("interval", intervalMs).putInt("skip", skipMatch).commit();
        } catch (Throwable ignored) {}
    }
    private void load() {
        try {
            SharedPreferences sp = hub.ctx().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            enable = sp.getInt("enable", enable);
            auto = sp.getInt("auto", auto);
            useTime = sp.getInt("use_time", useTime);
            delayMs = sp.getInt("delay", delayMs);
            intervalMs = sp.getInt("interval", intervalMs);
            skipMatch = sp.getInt("skip", skipMatch);
        } catch (Throwable ignored) {}
    }
    public String cfgJson() {
        return "{\"enable\":" + enable + ",\"auto\":" + auto + ",\"delay_ms\":" + delayMs + ",\"interval_ms\":" + intervalMs
                + ",\"skip_match\":" + skipMatch + ",\"use_time_ms\":" + useTime + ",\"use_time_s\":" + (useTime / 1000) + "}";
    }
    private static int pi(String s, int def) { try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return def; } }
}
