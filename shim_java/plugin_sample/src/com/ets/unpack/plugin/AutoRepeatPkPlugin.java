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
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ets.unpack.hub.Hub;
import com.ets.unpack.hub.IPlugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** 自动重复PK：结果弹窗延时关闭 -> 返回设置页 -> 延时点击“开始PK”。 */
public class AutoRepeatPkPlugin implements IPlugin {

    private static final String PREFS = "etshub_repeat";
    private static final String VM = "com.ets100.syncword.viewmodel.WordBattleViewModel";

    private Hub hub;
    private volatile int enable = 0;
    private volatile int closeDelay = 1500;
    private volatile int startDelay = 1500;
    private volatile String startText = "开始PK";
    private long closeAt = 0;
    private long startAt = 0;
    private long cooldownUntil = 0;
    private final Map<Activity, TextView> stats = new WeakHashMap<Activity, TextView>();

    public String name() { return "AutoRepeat 重复PK"; }
    public String version() { return "1.1.0"; }
    public String description() { return "结果弹窗延时关闭，返回后延时自动点开始PK"; }

    public void install(final Hub hub) {
        this.hub = hub;
        load();
        hub.add(new Hub.Feature() {
            public String title() { return "自动重复PK 配置"; }
            public void run(Activity a, Hub h) { dialog(a); }
        });
        hub.add(new Hub.Feature() {
            public String title() { return "查看自动重复PK配置"; }
            public void run(Activity a, Hub h) { h.showText(a, "自动重复PK", cfgJson()); }
        });
        hub.addRoute("/repeat", new Hub.Route() {
            public String handle(Map<String, String> q) {
                if (q.containsKey("enable") || q.containsKey("close") || q.containsKey("start") || q.containsKey("text")) {
                    setCfg(pi(q.get("enable"), enable), pi(q.get("close"), closeDelay), pi(q.get("start"), startDelay),
                            q.containsKey("text") ? q.get("text") : startText);
                }
                return cfgJson();
            }
        });
        startLoop();
        hub.dbg("AutoRepeatPkPlugin 1.0.0 installed");
    }

    private void dialog(Activity a) {
        int p = hub.dp(16);
        LinearLayout ll = new LinearLayout(a);
        ll.setOrientation(LinearLayout.VERTICAL); ll.setPadding(p, p, p, p);
        ll.addView(label(a, "开关（0关 1开）"));
        final EditText e1 = input(a, String.valueOf(enable)); ll.addView(e1);
        ll.addView(label(a, "弹窗出现后延时关闭 ms"));
        final EditText e2 = input(a, String.valueOf(closeDelay)); ll.addView(e2);
        ll.addView(label(a, "返回设置页后延时点开始PK ms"));
        final EditText e3 = input(a, String.valueOf(startDelay)); ll.addView(e3);
        ll.addView(label(a, "开始按钮文本"));
        final EditText e4 = new EditText(a); e4.setText(startText); ll.addView(e4);
        new AlertDialog.Builder(a).setTitle("自动重复PK").setView(ll)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        setCfg(pi(e1.getText().toString(), enable), pi(e2.getText().toString(), closeDelay),
                               pi(e3.getText().toString(), startDelay), e4.getText().toString());
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
                try { tick(); } catch (Throwable t) { hub.dbg("repeat err " + t); }
                if (hub.ui() != null) hub.ui().postDelayed(this, 300);
            }
        };
        hub.ui().postDelayed(task, 800);
    }

    private void tick() {
        Activity a = hub.current();
        if (a == null) return;
        if (enable != 1) { hideStatus(a); closeAt = 0; startAt = 0; return; }
        long now = System.currentTimeMillis();
        Object vm = hub.findViewModel(a, VM);
        boolean inBattle = vm != null;

        // 1) 结果：优先窗口里找 iv_close；枚举不到就用 ViewModel 提交结果判定，延时后 finish()
        if (now >= cooldownUntil) {
            View close = findInWindows(a, "iv_close");
            boolean resultReady = vm != null && isResultReady(vm);
            boolean dialogUp = close != null || resultReady || findInWindows(a, "tv_go_detail") != null;
            if (dialogUp) {
                startAt = 0;
                if (closeAt == 0) { closeAt = now + Math.max(0, closeDelay); status(a, "结果弹窗：等待关闭 " + closeDelay + "ms"); return; }
                if (now < closeAt) { status(a, "结果弹窗：等待关闭 " + (closeAt - now) + "ms"); return; }
                closeAt = 0;
                boolean ok = false;
                if (close != null) ok = click(close);
                if (!ok) { try { a.finish(); ok = true; } catch (Throwable ignored) {} }
                cooldownUntil = System.currentTimeMillis() + 1500;
                status(a, "已关闭弹窗 ok=" + ok);
                return;
            }
            closeAt = 0;
        }

        // 2) 设置页：出现“开始PK”且不在对战
        View start = inBattle ? null : findText(a, startText);
        if (start != null) {
            if (startAt == 0) { startAt = now + Math.max(0, startDelay); status(a, "设置页：等待开始 " + startDelay + "ms"); return; }
            if (now < startAt) { status(a, "设置页：等待开始 " + (startAt - now) + "ms"); return; }
            startAt = 0;
            boolean ok = click(start);
            status(a, "已点击开始PK ok=" + ok);
            return;
        }
        startAt = 0;
        status(a, inBattle ? "对战中…等待结果" : "等待PK界面");
    }

    /** 用 ViewModel 的提交结果 LiveData 判断本局是否已结束（弹窗应已出现）。 */
    private boolean isResultReady(Object vm) {
        for (String m : new String[]{"d0", "e0"}) {
            try {
                Object live = Hub.call(vm, m);
                if (live == null) continue;
                Object val = Hub.call(live, "getValue");
                if (val == null) continue;
                Object data = Hub.call(val, "getData");
                if (data != null) return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    // ---------------- 视图查找 ----------------
    private List<View> windowRoots(Activity a) {
        List<View> out = new ArrayList<View>();
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            java.lang.reflect.Method getInstance = wmg.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            Object inst = getInstance.invoke(null);
            java.lang.reflect.Method getRootViews = wmg.getDeclaredMethod("getRootViews");
            getRootViews.setAccessible(true);
            Object list = getRootViews.invoke(inst);
            if (list instanceof List) {
                for (Object o : (List<?>) list) {
                    if (o instanceof View) out.add((View) o);
                    else if (o != null) {
                        try { Object v = o.getClass().getMethod("getView").invoke(o); if (v instanceof View) out.add((View) v); } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}
        if (out.isEmpty()) { try { out.add(a.getWindow().getDecorView()); } catch (Throwable ignored) {} }
        return out;
    }
    private void collect(View v, List<View> out) {
        if (v == null) return;
        out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }
    private View findInWindows(Activity a, String idName) {
        int id = 0;
        try { id = hub.ctx().getResources().getIdentifier(idName, "id", hub.ctx().getPackageName()); } catch (Throwable ignored) {}
        if (id == 0) return null;
        for (View root : windowRoots(a)) {
            try {
                View v = root.findViewById(id);
                if (v != null && v.getVisibility() == View.VISIBLE) return v;
            } catch (Throwable ignored) {}
        }
        return null;
    }
    private View findText(Activity a, String want) {
        if (want == null || want.trim().length() == 0) return null;
        for (View root : windowRoots(a)) {
            List<View> all = new ArrayList<View>();
            collect(root, all);
            for (View v : all) {
                if (v instanceof TextView && v.getVisibility() == View.VISIBLE) {
                    CharSequence cs = ((TextView) v).getText();
                    if (cs != null && cs.toString().trim().equalsIgnoreCase(want.trim())) return v;
                }
            }
        }
        return null;
    }
    private boolean click(View v) {
        try {
            View cur = v;
            for (int i = 0; i < 8 && cur != null; i++) {
                if (cur.isClickable() && cur.isEnabled()) { cur.performClick(); return true; }
                ViewParent p = cur.getParent();
                cur = (p instanceof View) ? (View) p : null;
            }
            v.performClick();
            return true;
        } catch (Throwable ignored) {}
        return false;
    }

    // ---------------- 状态浮标 ----------------
    private void status(Activity a, String s) {
        try {
            FrameLayout root = hub.contentView(a);
            if (root == null) return;
            TextView tv = stats.get(a);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.TOP | Gravity.START; lp.setMargins(hub.dp(10), hub.dp(110), 0, 0);
            if (tv == null) {
                tv = new TextView(a);
                tv.setTextColor(Color.WHITE); tv.setTextSize(11f);
                GradientDrawable g = new GradientDrawable();
                g.setColor(0xE0558B2F); g.setCornerRadius(hub.dp(8)); g.setStroke(hub.dp(1), 0x66FFFFFF);
                tv.setBackground(g); tv.setPadding(hub.dp(8), hub.dp(6), hub.dp(8), hub.dp(6));
                root.addView(tv, lp); stats.put(a, tv);
            }
            tv.setText("重复PK: " + s);
            tv.setVisibility(View.VISIBLE);
        } catch (Throwable ignored) {}
    }
    private void hideStatus(Activity a) { TextView tv = stats.get(a); if (tv != null) tv.setVisibility(View.GONE); }

    private void setCfg(int e, int c, int s, String t) {
        enable = e == 1 ? 1 : 0;
        if (c < 0) c = 0;
        if (s < 0) s = 0;
        closeDelay = c; startDelay = s;
        if (t != null && t.trim().length() > 0) startText = t.trim();
        try {
            SharedPreferences sp = hub.ctx().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            sp.edit().putInt("enable", enable).putInt("close", closeDelay).putInt("start", startDelay).putString("text", startText).commit();
        } catch (Throwable ignored) {}
    }
    private void load() {
        try {
            SharedPreferences sp = hub.ctx().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            enable = sp.getInt("enable", enable);
            closeDelay = sp.getInt("close", closeDelay);
            startDelay = sp.getInt("start", startDelay);
            startText = sp.getString("text", startText);
        } catch (Throwable ignored) {}
    }
    public String cfgJson() {
        return "{\"enable\":" + enable + ",\"close_ms\":" + closeDelay + ",\"start_ms\":" + startDelay + ",\"startText\":" + Hub.json(startText) + "}";
    }
    private static int pi(String s, int def) { try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return def; } }
}
