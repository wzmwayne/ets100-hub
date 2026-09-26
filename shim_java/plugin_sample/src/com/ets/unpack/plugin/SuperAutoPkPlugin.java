package com.ets.unpack.plugin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ets.unpack.hub.Hub;
import com.ets.unpack.hub.IPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

/** 超级自动答题：PK 页面悬浮按钮 -> 参数编辑 -> 取题/开局/满分提交（独立直连 API）。 */
public class SuperAutoPkPlugin implements IPlugin {

    private static final String SECRET = "717e3cdc7314959fa2ef469d38f05673";
    private static final String PID = "androidV3";
    private static final int DEF_N = 10;
    private static final int DEF_POINT = 10000;

    private Hub hub;
    private final Map<Activity, View> shown = new WeakHashMap<Activity, View>();
    private final Random rnd = new Random();
    private volatile boolean running = false;
    private final List<String> lastErrors = java.util.Collections.synchronizedList(new ArrayList<String>());

    public String name() { return "超级自动答题"; }
    public String version() { return "1.2.0"; }
    public String description() { return "PK页面悬浮按钮：未选范围变灰提示，已选可编辑参数一键自动PK"; }

    public void install(final Hub hub) {
        this.hub = hub;
        hub.add(new Hub.Feature() {
            public String title() { return "超级自动答题(菜单)"; }
            public void run(Activity a, Hub h) { showParams(a); }
        });
        hub.addRoute("/superpk", new Hub.Route() {
            public String handle(Map<String, String> q) {
                try {
                    int n = num(q, "n", DEF_N), t = num(q, "use_time", 0);
                    int p = num(q, "point", DEF_POINT), r = Math.max(1, num(q, "rounds", 1));
                    int c = Math.max(1, num(q, "conc", 1));
                    if (r == 1) return doPk(new StringBuilder(), n, t, p).toString();
                    int[] s = runBatch(n, t, p, r, c, null);
                    JSONObject o = new JSONObject();
                    o.put("ok", true);
                    o.put("rounds", r);
                    o.put("win", s[0]); o.put("draw", s[1]); o.put("lose", s[2]);
                    o.put("err", s[3]); o.put("bean", s[4]);
                    o.put("errors", new JSONArray(lastErrors));
                    return o.toString();
                } catch (Throwable ex) {
                    return "{\"ok\":false,\"error\":" + Hub.json(String.valueOf(ex)) + "}";
                }
            }
        });
        Thread t = new Thread(new Runnable() { public void run() { watch(); } }, "superpk-float");
        t.setDaemon(true);
        t.start();
        hub.dbg("SuperAutoPkPlugin installed");
    }

    private static int num(Map<String, String> q, String k, int def) {
        try { String v = q == null ? null : q.get(k); return v == null ? def : Integer.parseInt(v.trim()); }
        catch (Throwable t) { return def; }
    }

    // ---------------- 悬浮按钮 ----------------

    private void watch() {
        while (true) {
            try {
                Activity a = hub.current();
                if (a != null && isPkPage(a)) refresh(a);
                Thread.sleep(500);
            } catch (Throwable t) {
                try { Thread.sleep(800); } catch (Throwable ignored) {}
            }
        }
    }

    private boolean isPkPage(Activity a) {
        return a.getClass().getName().startsWith("com.ets100.syncword.ui.pk");
    }

    /** 是否已在 PK 页面选好教材 + 单元（p7: sp_pk_book_id_/sp_pk_book_res_id_/sp_pk_unit_id_） */
    private boolean rangeSelected() {
        try {
            Map<?, ?> m = p7map();
            for (Object k : m.keySet()) {
                String ks = String.valueOf(k);
                if (!ks.startsWith("sp_pk_unit_id_")) continue;
                String pid = ks.substring("sp_pk_unit_id_".length());
                int unit = toInt(m.get(ks));
                int book = toInt(m.get("sp_pk_book_id_" + pid));
                int res = toInt(m.get("sp_pk_book_res_id_" + pid));
                String name = String.valueOf(m.get("sp_pk_unit_name_" + pid));
                boolean named = name != null && name.length() > 0 && !"null".equals(name);
                return unit > 0 && book > 0 && res > 0 && named;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static int toInt(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Throwable t) { return 0; }
    }

    private GradientDrawable pill(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(hub.dp(22));
        g.setStroke(hub.dp(1), 0x66FFFFFF);
        return g;
    }

    private void refresh(final Activity a) {
        View old = shown.get(a);
        if (old == null || old.getParent() == null) {
            old = attach(a);
            if (old == null) return;
        }
        final TextView b = (TextView) old;
        boolean ok = rangeSelected();
        Object cur = b.getTag();
        if (cur instanceof Boolean && ((Boolean) cur).booleanValue() == ok) return;
        b.setTag(Boolean.valueOf(ok));
        if (ok) {
            b.setText("超级自动答题");
            b.setBackground(pill(0xE6E8562A));
            b.setAlpha(0.92f);
        } else {
            b.setText("请选择PK范围");
            b.setBackground(pill(0x99888888));
            b.setAlpha(0.75f);
        }
    }

    private View attach(final Activity a) {
        FrameLayout root = hub.contentView(a);
        if (root == null) return null;
        final TextView b = new TextView(a);
        b.setText("超级自动答题");
        b.setTextColor(Color.WHITE);
        b.setTextSize(12.5f);
        b.setGravity(Gravity.CENTER);
        b.setElevation(hub.dp(8));
        b.setBackground(pill(0xE6E8562A));
        b.setPadding(hub.dp(14), hub.dp(6), hub.dp(14), hub.dp(6));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, hub.dp(42));
        lp.gravity = Gravity.BOTTOM | Gravity.START;
        lp.setMargins(hub.dp(10), 0, 0, hub.dp(150));
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!rangeSelected()) { hub.toast("请先选择PK范围（教材 + 单元）"); return; }
                showParams(a);
            }
        });
        try {
            root.addView(b, lp);
            shown.put(a, b);
            return b;
        } catch (Throwable t) { return null; }
    }

    // ---------------- 参数编辑 ----------------

    private EditText numField(Activity a, int val) {
        EditText e = new EditText(a);
        e.setText(String.valueOf(val));
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setTextSize(15f);
        return e;
    }

    private LinearLayout row(Activity a, String label, EditText e) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setPadding(0, hub.dp(4), 0, hub.dp(4));
        TextView t = new TextView(a);
        t.setText(label);
        t.setTextSize(14f);
        t.setGravity(Gravity.CENTER_VERTICAL);
        l.addView(t, new LinearLayout.LayoutParams(hub.dp(92), ViewGroup.LayoutParams.WRAP_CONTENT));
        l.addView(e, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return l;
    }

    private void showParams(final Activity a) {
        if (a == null) { hub.toast("没有可用页面"); return; }
        final SharedPreferences sp = a.getSharedPreferences("superpk", 0);
        final EditText eN = numField(a, sp.getInt("n", DEF_N));
        final EditText eT = numField(a, sp.getInt("use_time", 0));
        final EditText eP = numField(a, sp.getInt("point", DEF_POINT));
        final EditText eR = numField(a, sp.getInt("rounds", 1));
        final EditText eC = numField(a, sp.getInt("conc", 1));
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(hub.dp(18), hub.dp(8), hub.dp(18), hub.dp(4));
        box.addView(row(a, "题数", eN));
        box.addView(row(a, "用时(秒)", eT));
        box.addView(row(a, "每题满分", eP));
        box.addView(row(a, "局数", eR));
        box.addView(row(a, "并发", eC));
        new AlertDialog.Builder(a)
                .setTitle("超级自动答题 · 参数")
                .setView(box)
                .setPositiveButton("开始", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        int n = Math.max(1, parse(eN, DEF_N));
                        int t = Math.max(0, parse(eT, 0));
                        int p = Math.max(1, parse(eP, DEF_POINT));
                        int r = Math.max(1, parse(eR, 1));
                        int c = Math.max(1, parse(eC, 1));
                        sp.edit().putInt("n", n).putInt("use_time", t).putInt("point", p)
                          .putInt("rounds", r).putInt("conc", c).apply();
                        runAuto(a, n, t, p, r, c);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static int parse(EditText e, int def) {
        try { return Integer.parseInt(e.getText().toString().trim()); } catch (Throwable t) { return def; }
    }

    // ---------------- 执行 + 进度 ----------------

    private void runAuto(final Activity act, final int n, final int useTime,
                         final int point, final int rounds, final int conc) {
        if (running) { hub.toast("正在执行中，请稍候..."); return; }
        running = true;
        final AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle("超级自动答题").setMessage("准备中...")
                .setCancelable(false).setPositiveButton("关闭", null).create();
        act.runOnUiThread(new Runnable() { public void run() { try { dlg.show(); } catch (Throwable ignored) {} } });
        new Thread(new Runnable() {
            public void run() {
                long t0 = System.currentTimeMillis();
                try {
                    int[] s = runBatch(n, useTime, point, rounds, conc, new Progress() {
                        public void onRound(int done, int win, int draw, int lose, int err, int bean, String detail) {
                            if (done % Math.max(1, rounds / 50) != 0 && done != rounds) return;
                            msg(act, dlg, "进行中 " + done + "/" + rounds
                                    + "\n胜 " + win + " 平 " + draw + " 负 " + lose + " 失败 " + err
                                    + "\n成长豆 +" + bean + "\n" + detail);
                        }
                    });
                    double sec = (System.currentTimeMillis() - t0) / 1000.0;
                    msg(act, dlg, "完成 " + rounds + " 局\n"
                            + "胜 " + s[0] + "  平 " + s[1] + "  负 " + s[2] + "  失败 " + s[3]
                            + "\n成长豆 +" + s[4]
                            + "\n参数: " + n + "题 / 用时" + useTime + "秒 / 满分" + point + " / 并发" + conc
                            + "\n总耗时 " + String.format("%.1f", sec) + " 秒"
                            + "  (" + String.format("%.2f", rounds / Math.max(0.001, sec)) + " 局/s)");
                } catch (Throwable t) {
                    msg(act, dlg, "失败: " + t);
                } finally {
                    running = false;
                }
            }
        }, "superpk-run").start();
    }

    public interface Progress {
        void onRound(int done, int win, int draw, int lose, int err, int bean, String detail);
    }

    /** 批量执行；返回 [胜,平,负,失败,成长豆] */
    private int[] runBatch(final int n, final int useTime, final int point,
                           final int rounds, final int conc, final Progress cb) throws Exception {
        lastErrors.clear();
        final AtomicInteger done = new AtomicInteger(), win = new AtomicInteger(), draw = new AtomicInteger(),
                lose = new AtomicInteger(), err = new AtomicInteger(), bean = new AtomicInteger();
        ExecutorService ex = Executors.newFixedThreadPool(Math.max(1, conc));
        List<Future<?>> fs = new ArrayList<Future<?>>();
        try {
            for (int i = 0; i < rounds; i++) {
                fs.add(ex.submit(new Runnable() {
                    public void run() {
                        String detail;
                        try {
                            JSONObject r = doPk(new StringBuilder(), n, useTime, point);
                            int res = r.optInt("result");
                            if (res == 1) win.incrementAndGet();
                            else if (res == 0) draw.incrementAndGet();
                            else lose.incrementAndGet();
                            bean.addAndGet(r.optInt("add_bean"));
                            detail = "得分 " + r.optInt("point") + " | 对手 "
                                    + r.optString("opp_name") + " " + r.optInt("opp_point") + "分/" + r.optInt("opp_time") + "秒";
                        } catch (Throwable t) {
                            err.incrementAndGet();
                            detail = "错误: " + t;
                            if (lastErrors.size() < 5) lastErrors.add(String.valueOf(t));
                        }
                        int d = done.incrementAndGet();
                        if (cb != null) cb.onRound(d, win.get(), draw.get(), lose.get(), err.get(), bean.get(), detail);
                    }
                }));
            }
            for (Future<?> f : fs) { try { f.get(); } catch (Throwable ignored) {} }
        } finally {
            ex.shutdown();
        }
        return new int[]{win.get(), draw.get(), lose.get(), err.get(), bean.get()};
    }

    private void msg(final Activity a, final AlertDialog dlg, final String m) {
        try {
            a.runOnUiThread(new Runnable() { public void run() { try { dlg.setMessage(m); } catch (Throwable ignored) {} } });
        } catch (Throwable ignored) {}
    }

    // ---------------- 核心流程（与 pk_client.py 一致） ----------------

    private JSONObject doPk(StringBuilder log, int n, int useTime, int point) throws Exception {
        Map<?, ?> p7 = p7map();
        String unitKey = null;
        for (Object k : p7.keySet()) {
            String ks = String.valueOf(k);
            if (ks.startsWith("sp_pk_unit_id_")) { unitKey = ks; break; }
        }
        if (unitKey == null) throw new RuntimeException("未找到 PK 单元，请先在 PK 页面选择单元");
        String pid = unitKey.substring(unitKey.lastIndexOf('_') + 1);
        int unitId = Integer.parseInt(str(p7, unitKey));
        int bookId = Integer.parseInt(str(p7, "sp_pk_book_id_" + pid));
        int resId = Integer.parseInt(str(p7, "sp_pk_book_res_id_" + pid));
        log.append("unit=").append(unitId).append(" book=").append(bookId);

        JSONObject wlParams = new JSONObject();
        wlParams.put("unit_id", unitId);
        wlParams.put("resource_id", resId);
        wlParams.put("parent_account_id", Integer.parseInt(pid));
        JSONObject wl = api("g/set/word-list", wlParams);
        JSONArray cats = wl.getJSONObject("body").optJSONArray("data");
        if (cats == null) throw new RuntimeException("词表为空");
        JSONArray wids = new JSONArray();
        for (int i = 0; i < cats.length(); i++) {
            JSONArray ws = cats.getJSONObject(i).optJSONArray("words");
            if (ws == null) continue;
            for (int j = 0; j < ws.length(); j++) wids.put(ws.getJSONObject(j).getInt("id"));
        }
        if (wids.length() == 0) throw new RuntimeException("该单元没有单词");

        JSONObject stParams = new JSONObject();
        stParams.put("word_ids", wids);
        JSONObject st = api("g/word/struct", stParams);
        JSONArray data = st.getJSONObject("body").getJSONArray("data");
        JSONArray pw = new JSONArray(), pq = new JSONArray(), pa = new JSONArray();
        for (int i = 0; i < data.length() && pw.length() < n; i++) {
            JSONObject s = data.getJSONObject(i);
            JSONArray qs = s.optJSONArray("question");
            if (qs == null) continue;
            for (int k = 0; k < qs.length(); k++) {
                JSONObject q = qs.getJSONObject(k);
                if (q.optInt("question_type") == 19 && q.optString("answer").length() > 0) {
                    pw.put(s.getInt("id"));
                    pq.put(q.getInt("id"));
                    pa.put(q.getString("answer"));
                    break;
                }
            }
        }
        if (pw.length() < n) throw new RuntimeException("可用题目不足: " + pw.length() + " < " + n);

        StringBuilder wj = new StringBuilder(), qj = new StringBuilder();
        for (int i = 0; i < pw.length(); i++) {
            if (i > 0) { wj.append('_'); qj.append('_'); }
            wj.append(pw.getInt(i));
            qj.append(pq.getInt(i));
        }
        JSONObject sp = new JSONObject();
        sp.put("unit_id", unitId);
        sp.put("book_id", bookId);
        sp.put("word_ids", wj.toString());
        sp.put("question_ids", qj.toString());
        sp.put("name", "");
        sp.put("source", 0);
        sp.put("version", 1);
        JSONObject sb = api("g/pk/start-word-pk", sp).getJSONObject("body");
        String serial = sb.getString("serial_id");
        JSONObject opp = sb.optJSONObject("opponent");

        JSONArray detail = new JSONArray();
        for (int i = 0; i < pw.length(); i++) {
            JSONObject d = new JSONObject();
            d.put("point", point);
            d.put("answer", pa.getString(i));
            d.put("word_id", pw.getInt(i));
            d.put("question_id", pq.getInt(i));
            detail.put(d);
        }
        JSONObject mp = new JSONObject();
        mp.put("serial_id", serial);
        mp.put("use_time", useTime);
        mp.put("source", 0);
        mp.put("score_detail", detail);
        JSONObject mb = api("g/pk/submit-word", mp).getJSONObject("body");

        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("result", mb.optInt("result"));
        out.put("add_bean", mb.optInt("add_bean"));
        out.put("point", mb.optInt("point"));
        out.put("serial", serial);
        out.put("unit_id", unitId);
        out.put("book_id", bookId);
        if (opp != null) {
            JSONObject os = opp.optJSONObject("score");
            out.put("opp_name", opp.optString("name"));
            if (os != null) { out.put("opp_point", os.optInt("point")); out.put("opp_time", os.optInt("use_time")); }
        }
        return out;
    }

    // ---------------- 配置 / 网络 ----------------

    private String token, ver, etsApp, base;

    private synchronized void cfg() throws Exception {
        if (base != null && token != null && ver != null && etsApp != null) return;
        Class<?> eh = Class.forName("com.ets100.network.EtsHttp");
        Object etsHttp = eh.getField("a").get(null);
        String t = String.valueOf(Hub.call(Hub.call(etsHttp, "h"), "a"));
        Object options = Hub.call(Hub.call(etsHttp, "f"), "a");
        String e = String.valueOf(Hub.call(options, "i"));
        String b = String.valueOf(Hub.call(Hub.call(options, "j"), "e"));
        Class<?> oda = Class.forName("od.a");
        Object inst = oda.getField("a").get(null);
        String v = String.valueOf(Hub.call(inst, "d"));
        if (b == null || b.length() < 8 || b.equals("null")) b = "https://api.ets100.com";
        b = b.replaceAll("/+$", "");
        token = t; etsApp = e; base = b; ver = v;      // 全部就绪后再发布，避免半初始化
    }

    private Map<?, ?> p7map() throws Exception {
        Class<?> c = Class.forName("com.ets100.secondary.utils.p7");
        return (Map<?, ?>) c.getMethod("f").invoke(null);
    }

    private static String str(Map<?, ?> m, Object k) {
        Object v = m.get(k);
        if (v == null) throw new RuntimeException("缺少配置项 " + k + "（请先在 PK 页面选好书本/单元）");
        return String.valueOf(v);
    }

    private JSONObject api(String path, JSONObject params) throws Exception {
        cfg();
        long t = System.currentTimeMillis();
        String sn = t + "" + (1000 + rnd.nextInt(9000));
        params.put("system", "2");
        params.put("global_client_version", ver);
        params.put("sn", sn);
        params.put("token", token);
        params.put("ets_app", etsApp);
        params.put("sign_response", 2);
        JSONObject r = new JSONObject();
        r.put("r", path);
        r.put("params", params);
        JSONArray arr = new JSONArray();
        arr.put(r);
        String body = Base64.encodeToString(arr.toString().getBytes("UTF-8"), Base64.NO_WRAP);
        String ts = String.valueOf(t);
        String sign = md5(PID + ts + body + SECRET);
        JSONObject head = new JSONObject();
        head.put("sign", sign);
        head.put("pid", PID);
        head.put("time", ts);
        head.put("time_second", t);
        head.put("version", "1.0");
        JSONObject outer = new JSONObject();
        outer.put("body", body);
        outer.put("head", head);

        byte[] data = outer.toString().getBytes("UTF-8");
        HttpURLConnection c = (HttpURLConnection) new URL(base + "/" + path).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(25000);
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        c.setRequestProperty("Referer", base);
        c.setRequestProperty("User-Agent", "okhttp/4.9.3");
        c.setRequestProperty("Accept-Encoding", "gzip");
        c.getOutputStream().write(data);
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        byte[] raw = readAll(is);
        String enc = c.getContentEncoding();
        if ((enc != null && enc.toLowerCase().contains("gzip"))
                || (raw.length > 2 && (raw[0] & 0xff) == 0x1f && (raw[1] & 0xff) == 0x8b)) {
            raw = readAll(new GZIPInputStream(new java.io.ByteArrayInputStream(raw)));
        }
        JSONArray resp = new JSONArray(new String(raw, "UTF-8"));
        JSONObject o = resp.getJSONObject(0);
        if (o.optInt("code") != 0) {
            throw new RuntimeException(path + " -> " + o.optString("msg") + "(code=" + o.optInt("code") + ")");
        }
        return o;
    }

    private static byte[] readAll(InputStream is) throws Exception {
        if (is == null) return new byte[0];
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }

    private static String md5(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] d = md.digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) {
            int v = b & 0xff;
            if (v < 16) sb.append('0');
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }
}
