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
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ets.unpack.hub.Hub;
import com.ets.unpack.hub.IPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** 单词PK 自动答题（外部插件）：扫描界面按选项内容点击 + 随机延迟 + HUD 中文。 */
public class AutoPkPlugin implements IPlugin {

    private static final String VM = "com.ets100.syncword.viewmodel.WordBattleViewModel";
    private static final String PREFS = "etshub_plugin";

    private Hub hub;
    private volatile boolean autoPk;
    private volatile int minMs = 1500, maxMs = 4000, wrongPct = 0;
    private int seenIdx = -1, lastAnswered = -1;
    private long answerAt = 0;
    private final Map<Activity, TextView> huds = new WeakHashMap<Activity, TextView>();

    public String name() { return "AutoPk 自动答题"; }
    public String version() { return "1.3.0"; }
    public String description() { return "单词PK自动答题：扫描界面按选项内容点击、随机延迟、HUD显示中文"; }

    public void install(final Hub hub) {
        this.hub = hub;
        load();
        hub.add(new Hub.Feature() {
            public String title() { return "PK 自动答题 开/关"; }
            public void run(Activity a, Hub h) { setAuto(!autoPk); }
        });
        hub.add(new Hub.Feature() {
            public String title() { return "PK 自动答题 参数设置"; }
            public void run(Activity a, Hub h) { editParams(a); }
        });
        hub.add(new Hub.Feature() {
            public String title() { return "当前 PK 答案"; }
            public void run(Activity a, Hub h) { h.showText(a, "PK state", stateJson()); }
        });
        hub.addRoute("/pk/raw", new Hub.Route() {
            public String handle(Map<String, String> q) { return rawJson(); }
        });
        hub.addRoute("/pk/auto", new Hub.Route() {
            public String handle(Map<String, String> q) {
                if (q.containsKey("min") || q.containsKey("max") || q.containsKey("wrong")) {
                    setConfig(pi(q.get("min"), minMs), pi(q.get("max"), maxMs), pi(q.get("wrong"), wrongPct));
                }
                if (q.containsKey("on")) setAuto(truthy(q.get("on")));
                else setAuto(!autoPk);
                return "{\"result\":\"auto=" + autoPk + " min=" + minMs + " max=" + maxMs + " wrong=" + wrongPct + "%\"}";
            }
        });
        startLoop();
        hub.dbg("AutoPkPlugin 1.3.0 installed");
    }

    private void editParams(Activity a) {
        int p = hub.dp(16);
        LinearLayout ll = new LinearLayout(a);
        ll.setOrientation(LinearLayout.VERTICAL); ll.setPadding(p, p, p, p);
        ll.addView(label(a, "最小延迟 (ms)"));
        final EditText eMin = input(a, String.valueOf(minMs)); ll.addView(eMin);
        ll.addView(label(a, "最大延迟 (ms)"));
        final EditText eMax = input(a, String.valueOf(maxMs)); ll.addView(eMax);
        ll.addView(label(a, "错答概率 (%)"));
        final EditText eWrong = input(a, String.valueOf(wrongPct)); ll.addView(eWrong);
        new AlertDialog.Builder(a).setTitle("PK 自动答题参数").setView(ll)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        setConfig(pi(eMin.getText().toString(), minMs), pi(eMax.getText().toString(), maxMs), pi(eWrong.getText().toString(), wrongPct));
                        hub.toast("已保存: " + minMs + "-" + maxMs + "ms, 错答 " + wrongPct + "%");
                    }
                }).setNegativeButton("取消", null).show();
    }
    private TextView label(Activity a, String s) {
        TextView t = new TextView(a); t.setText(s); t.setTextColor(0xFFBBBBBB); t.setTextSize(12.0f); t.setPadding(0, hub.dp(8), 0, hub.dp(4));
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
                try { tick(); } catch (Throwable t) { hub.dbg("AutoPk tick err " + t); }
                if (hub.ui() != null) hub.ui().postDelayed(this, autoPk ? 50 : 450);
            }
        };
        hub.ui().postDelayed(task, 400);
    }

    // ---------- 界面扫描 ----------
    private List<View> allViews(Activity a) {
        List<View> out = new ArrayList<View>();
        try {
            java.util.ArrayDeque<View> q = new java.util.ArrayDeque<View>();
            View root = a.getWindow().getDecorView();
            q.add(root);
            int n = 0;
            while (!q.isEmpty() && n < 8000) {
                View v = q.poll(); n++; out.add(v);
                if (v instanceof android.view.ViewGroup) {
                    android.view.ViewGroup g = (android.view.ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) q.add(g.getChildAt(i));
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }
    private static boolean sameText(CharSequence cs, String want) {
        if (cs == null || want == null) return false;
        String t = cs.toString().trim();
        String w = want.trim();
        if (t.length() == 0 || w.length() == 0) return false;
        return t.equals(w) || t.equalsIgnoreCase(w) || t.replace(" ", "").equals(w.replace(" ", ""));
    }
    /** 在界面上找文本等于 want 的 TextView。 */
    private View findText(Activity a, String want) {
        if (want == null || want.trim().length() == 0) return null;
        for (View v : allViews(a)) {
            if (v instanceof TextView && v.getVisibility() == View.VISIBLE && sameText(((TextView) v).getText(), want)) return v;
        }
        return null;
    }
    /** 向上找到可点击祖先并点击。 */
    private boolean clickView(View v) {
        if (v == null) return false;
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

    // ---------- 选项内容匹配 ----------
    private int matchIndex(List<?> opts, String target) {
        if (target == null || target.trim().length() == 0) return -1;
        for (int i = 0; i < opts.size(); i++) {
            String v = (String) Hub.call(opts.get(i), "getOptionValue");
            if (sameText(v, target)) return i;
        }
        for (int i = 0; i < opts.size(); i++) {
            String n = (String) Hub.call(opts.get(i), "getOptionName");
            if (sameText(n, target)) return i;
        }
        return -1;
    }
    private String optionDisplay(List<?> opts, int idx) {
        try {
            if (idx >= 0 && idx < opts.size()) {
                String v = (String) Hub.call(opts.get(idx), "getOptionValue");
                if (v != null && v.trim().length() > 0) return v;
                return (String) Hub.call(opts.get(idx), "getOptionName");
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void tick() {
        Activity a = hub.current();
        if (a == null) return;
        Object vm = hub.findViewModel(a, VM);
        if (vm == null) { hide(a); return; }
        Object structs = Hub.call(vm, "t0");
        int idx = Hub.num(Hub.call(vm, "O"), -1);
        if (!(structs instanceof List)) { hide(a); return; }
        List<?> l = (List<?>) structs;
        if (idx < 0 || idx >= l.size()) { hide(a); return; }
        Object st = l.get(idx);
        String content = (String) Hub.call(st, "getContent");
        String zh = (String) Hub.call(st, "getTranslate");
        Object qs = Hub.call(st, "getQuestion");
        Object q = (qs instanceof List && !((List<?>) qs).isEmpty()) ? ((List<?>) qs).get(0) : null;
        String answer = q == null ? null : (String) Hub.call(q, "getAnswer");
        Object od = q == null ? null : Hub.call(q, "getOptionData");
        List<?> opts = (od instanceof List) ? (List<?>) od : null;

        int pos = opts == null ? -1 : matchIndex(opts, zh);
        if (pos < 0 && opts != null) pos = matchIndex(opts, answer);
        String correct = opts == null ? zh : optionDisplay(opts, pos);
        if (correct == null) correct = zh;

        String wordView = content != null ? content : zh;
        boolean wordOnScreen = findText(a, wordView) != null;
        View target = findText(a, correct);

        StringBuilder hb = new StringBuilder();
        if (autoPk) hb.append("自动答题 ").append(idx + 1).append("/").append(l.size()).append("\n");
        if (content != null && content.length() > 0 && !content.equals(zh)) hb.append("单词: ").append(content).append("\n");
        hb.append("中文: ").append(zh == null || zh.length() == 0 ? "?" : zh);
        String base = hb.toString();

        if (!autoPk) { show(a, base); return; }
        if (opts == null || opts.isEmpty()) { show(a, base + "\n状态: 无选项数据"); return; }
        if (idx == lastAnswered) { show(a, base + "\n状态: 已作答"); return; }

        if (idx != seenIdx) {
            seenIdx = idx;
            int lo = minMs < 50 ? 50 : minMs;
            int hi = maxMs < lo ? lo : maxMs;
            answerAt = System.currentTimeMillis() + lo + (int) (Math.random() * Math.max(1, hi - lo));
            hub.dbg("schedule idx=" + idx + " zh=" + zh + " answer=" + answer + " pos=" + pos + " correct=" + correct + " wordOnScreen=" + wordOnScreen + " target=" + (target != null));
        }
        long remain = answerAt - System.currentTimeMillis();
        if (remain > 0) { show(a, base + "\n状态: 等待 " + String.format("%.1f", remain / 1000.0) + "s"); return; }
        if (target == null) target = findText(a, correct);
        if (target == null) { answerAt = System.currentTimeMillis() + 50; show(a, base + "\n状态: 选项未就绪"); return; }

        if (wrongPct > 0 && Math.random() * 100.0 < wrongPct) {
            View alt = null;
            for (View v : allViews(a)) {
                if (v instanceof TextView && v.getVisibility() == View.VISIBLE && v != target && v.isClickable()) { alt = v; break; }
            }
            if (alt != null) target = alt;
        }
        lastAnswered = idx;
        boolean ok = clickView(target);
        show(a, base + (ok ? "\n状态: 已作答" : "\n状态: 点击失败"));
        hub.dbg("answered idx=" + idx + " pos=" + pos + " correct=" + correct + " ok=" + ok);
    }

    // ---------- HUD ----------
    private TextView ensure(Activity a) {
        FrameLayout root = hub.contentView(a);
        if (root == null) return null;
        TextView tv = huds.get(a);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.START; lp.setMargins(hub.dp(10), hub.dp(64), 0, 0);
        if (tv == null) {
            tv = new TextView(a);
            tv.setTextColor(Color.WHITE); tv.setTextSize(11.0f);
            GradientDrawable g = new GradientDrawable();
            g.setColor(0xB0000000); g.setCornerRadius(hub.dp(8)); g.setStroke(hub.dp(1), 0x55FFFFFF);
            tv.setBackground(g); tv.setPadding(hub.dp(8), hub.dp(6), hub.dp(8), hub.dp(6));
            root.addView(tv, lp); huds.put(a, tv);
        } else if (tv.getParent() != root) {
            if (tv.getParent() instanceof FrameLayout) ((FrameLayout) tv.getParent()).removeView(tv);
            root.addView(tv, lp);
        }
        return tv;
    }
    private void show(Activity a, String t) { TextView tv = ensure(a); if (tv != null) { tv.setText(t); tv.setVisibility(View.VISIBLE); } }
    private void hide(Activity a) { TextView tv = huds.get(a); if (tv != null) tv.setVisibility(View.GONE); }

    public String stateJson() {
        Activity a = hub.current();
        if (a == null) return "{\"active\":false,\"reason\":\"no activity\"}";
        Object vm = hub.findViewModel(a, VM);
        if (vm == null) return "{\"active\":false,\"reason\":\"no pk viewmodel\"}";
        int idx = Hub.num(Hub.call(vm, "O"), -1);
        StringBuilder sb = new StringBuilder("{\"active\":true,\"index\":").append(idx);
        String zh = null, answer = null;
        Object structs = Hub.call(vm, "t0");
        if (structs instanceof List) {
            List<?> l = (List<?>) structs;
            sb.append(",\"total\":").append(l.size());
            if (idx >= 0 && idx < l.size()) {
                Object st = l.get(idx);
                sb.append(",\"structId\":").append(Hub.num(Hub.call(st, "getId"), -1));
                sb.append(",\"content\":").append(Hub.json((String) Hub.call(st, "getContent")));
                zh = (String) Hub.call(st, "getTranslate");
                sb.append(",\"translate\":").append(Hub.json(zh));
                Object qs = Hub.call(st, "getQuestion");
                if (qs instanceof List && !((List<?>) qs).isEmpty()) {
                    Object q = ((List<?>) qs).get(0);
                    answer = (String) Hub.call(q, "getAnswer");
                    sb.append(",\"answerName\":").append(Hub.json(answer));
                    Object od = Hub.call(q, "getOptionData");
                    sb.append(",\"options\":[");
                    if (od instanceof List) {
                        List<?> os = (List<?>) od;
                        for (int i = 0; i < os.size(); i++) {
                            if (i > 0) sb.append(",");
                            sb.append("{\"name\":").append(Hub.json((String) Hub.call(os.get(i), "getOptionName")))
                              .append(",\"value\":").append(Hub.json((String) Hub.call(os.get(i), "getOptionValue"))).append("}");
                        }
                    }
                    sb.append("]");
                }
            }
        }
        sb.append(",\"auto\":").append(autoPk);
        sb.append(",\"min\":").append(minMs).append(",\"max\":").append(maxMs).append(",\"wrongPct\":").append(wrongPct);
        sb.append(",\"audio\":").append(hub.pkAudio());
        sb.append("}");
        return sb.toString();
    }

    public String rawJson() {
        Activity a = hub.current();
        if (a == null) return "{\"active\":false}";
        Object vm = hub.findViewModel(a, VM);
        if (vm == null) return "{\"active\":false,\"reason\":\"no vm\"}";
        int idx = Hub.num(Hub.call(vm, "O"), -1);
        Object structs = Hub.call(vm, "t0");
        if (!(structs instanceof List)) return "{\"active\":false,\"reason\":\"no structs\"}";
        List<?> l = (List<?>) structs;
        if (idx < 0 || idx >= l.size()) return "{\"active\":false,\"reason\":\"index out\"}";
        Object st = l.get(idx);
        StringBuilder sb = new StringBuilder("{\"content\":").append(Hub.json((String) Hub.call(st, "getContent")))
                .append(",\"translate\":").append(Hub.json((String) Hub.call(st, "getTranslate")));
        Object qs = Hub.call(st, "getQuestion");
        String answer = null, correct = null;
        if (qs instanceof List && !((List<?>) qs).isEmpty()) {
            Object q = ((List<?>) qs).get(0);
            answer = (String) Hub.call(q, "getAnswer");
            sb.append(",\"answer\":").append(Hub.json(answer));
            sb.append(",\"optionRaw\":").append(Hub.json((String) Hub.call(q, "getOption")));
            Object od = Hub.call(q, "getOptionData");
            sb.append(",\"parsed\":[");
            if (od instanceof List) {
                List<?> os = (List<?>) od;
                for (int i = 0; i < os.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append("{\"name\":").append(Hub.json((String) Hub.call(os.get(i), "getOptionName")))
                      .append(",\"value\":").append(Hub.json((String) Hub.call(os.get(i), "getOptionValue"))).append("}");
                }
                int pos = matchIndex(os, (String) Hub.call(st, "getTranslate"));
                if (pos < 0) pos = matchIndex(os, answer);
                correct = optionDisplay(os, pos);
            }
            sb.append("],\"correct\":").append(Hub.json(correct));
        }
        sb.append(",\"onScreenTexts\":[");
        boolean first = true;
        for (View v : allViews(a)) {
            if (v instanceof TextView && v.getVisibility() == View.VISIBLE) {
                CharSequence cs = ((TextView) v).getText();
                if (cs != null && cs.toString().trim().length() > 0) {
                    if (!first) sb.append(","); first = false;
                    sb.append(Hub.json(cs.toString()));
                }
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    private void setAuto(boolean on) {
        autoPk = on; seenIdx = -1; lastAnswered = -1; answerAt = 0;
        if (!on) { Activity a = hub.current(); if (a != null) hide(a); }
        hub.toast("PK 自动答题: " + (on ? "开" : "关"));
    }
    private void setConfig(int min, int max, int wrong) {
        if (min < 50) min = 50;
        if (max < min) max = min;
        if (wrong < 0) wrong = 0;
        if (wrong > 100) wrong = 100;
        minMs = min; maxMs = max; wrongPct = wrong;
        try {
            SharedPreferences p = hub.ctx().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            p.edit().putInt("min", minMs).putInt("max", maxMs).putInt("wrong", wrongPct).commit();
        } catch (Throwable ignored) {}
    }
    private void load() {
        try {
            SharedPreferences p = hub.ctx().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            minMs = p.getInt("min", minMs); maxMs = p.getInt("max", maxMs); wrongPct = p.getInt("wrong", wrongPct);
        } catch (Throwable ignored) {}
    }
    private static boolean truthy(String s) { return s != null && (s.equals("1") || s.equalsIgnoreCase("true") || s.equalsIgnoreCase("on") || s.equalsIgnoreCase("yes")); }
    private static int pi(String s, int def) { try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return def; } }
}
