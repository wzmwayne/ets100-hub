package com.ets.unpack.plugin;

import android.app.Activity;

import com.ets.unpack.hub.Hub;
import com.ets.unpack.hub.IPlugin;

import java.util.List;
import java.util.Map;

/** PkApi：导出账号/配置、导出 PK 题目答案、外部提交答案与用时。 */
public class PkApiPlugin implements IPlugin {

    private static final String VM = "com.ets100.syncword.viewmodel.WordBattleViewModel";

    private Hub hub;

    public String name() { return "PkApi 数据接口"; }
    public String version() { return "1.2.0"; }
    public String description() { return "/account 账号配置; /pk/export 题目答案; /pk/submit 提交答案与用时"; }

    public void install(final Hub hub) {
        this.hub = hub;
        hub.add(new Hub.Feature() {
            public String title() { return "查看 PK 导出JSON"; }
            public void run(Activity a, Hub h) { h.showText(a, "PkApi /pk/export", exportJson()); }
        });
        hub.addRoute("/account", new Hub.Route() {
            public String handle(Map<String, String> q) { return accountJson(); }
        });
        hub.addRoute("/pk/export", new Hub.Route() {
            public String handle(Map<String, String> q) { return exportJson(); }
        });
        hub.addRoute("/pk/submit", new Hub.Route() {
            public String handle(Map<String, String> q) { return submit(q); }
        });
        hub.dbg("PkApiPlugin installed");
    }

    private Activity cur() { return hub.current(); }

    public String accountJson() {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append(Hub.json("pkg")).append(':').append(Hub.json(hub.ctx().getPackageName()));
        try {
            String aid = android.provider.Settings.Secure.getString(hub.ctx().getContentResolver(), "android_id");
            sb.append(',').append(Hub.json("android_id")).append(':').append(Hub.json(aid));
        } catch (Throwable ignored) {}
        sb.append(',').append(Hub.json("p7")).append(':').append('{');
        try {
            Class<?> p7 = Class.forName("com.ets100.secondary.utils.p7");
            Object map = p7.getMethod("f").invoke(null);
            if (map instanceof java.util.Map) {
                boolean first = true;
                for (Object o : ((java.util.Map<?, ?>) map).entrySet()) {
                    Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
                    if (e.getKey() == null) continue;
                    if (!first) sb.append(','); first = false;
                    String v = String.valueOf(e.getValue());
                    if (v.length() > 300) v = v.substring(0, 300) + "...";
                    sb.append(Hub.json(String.valueOf(e.getKey()))).append(':').append(Hub.json(v));
                }
            }
        } catch (Throwable t) { sb.append(Hub.json("_error")).append(':').append(Hub.json(String.valueOf(t))); }
        sb.append('}');
        sb.append(',').append(Hub.json("config")).append(':').append('{');
        try {
            Class<?> eh = Class.forName("com.ets100.network.EtsHttp");
            Object etsHttp = eh.getField("a").get(null);
            Object token = Hub.call(Hub.call(etsHttp, "h"), "a");
            sb.append(Hub.json("token")).append(':').append(Hub.json(String.valueOf(token)));
            Object options = Hub.call(Hub.call(etsHttp, "f"), "a");
            sb.append(',').append(Hub.json("options")).append(':');
            dumpObj(sb, options, 2);
            Class<?> oda = Class.forName("od.a");
            Object inst = oda.getField("a").get(null);
            sb.append(',').append(Hub.json("client_version")).append(':').append(Hub.json(String.valueOf(Hub.call(inst, "d"))));
            sb.append(',').append(Hub.json("rand4")).append(':').append(Hub.json(String.valueOf(Hub.call(inst, "a", new Class<?>[]{int.class}, new Object[]{Integer.valueOf(4)}))));
        } catch (Throwable t) { sb.append(Hub.json("_error")).append(':').append(Hub.json(String.valueOf(t))); }
        sb.append('}');
        sb.append('}');
        return sb.toString();
    }

    private void dumpObj(StringBuilder sb, Object o, int depth) {
        if (o == null || depth < 0) { sb.append("null"); return; }
        sb.append('{');
        boolean first = true;
        for (java.lang.reflect.Method m : o.getClass().getMethods()) {
            if (m.getParameterCount() != 0) continue;
            Class<?> r = m.getReturnType();
            boolean scalar = (r == String.class || r == int.class || r == boolean.class || r == Integer.class);
            boolean nested = depth > 0 && r.getName().startsWith("pd.");
            if (!scalar && !nested) continue;
            String n = m.getName();
            if (n.equals("getClass") || n.equals("hashCode") || n.equals("toString")) continue;
            try {
                m.setAccessible(true);
                Object v = m.invoke(o);
                if (!first) sb.append(','); first = false;
                sb.append(Hub.json(n)).append(':');
                if (scalar) sb.append(Hub.json(String.valueOf(v)));
                else dumpObj(sb, v, depth - 1);
            } catch (Throwable ignored) {}
        }
        sb.append('}');
    }

    public String exportJson() {
        Activity a = cur();
        if (a == null) return "{\"active\":false}";
        Object vm = hub.findViewModel(a, VM);
        if (vm == null) return "{\"active\":false,\"reason\":\"no pk viewmodel\"}";
        Object structs = Hub.call(vm, "t0");
        if (!(structs instanceof List)) return "{\"active\":false,\"reason\":\"no structs\"}";
        List<?> l = (List<?>) structs;
        StringBuilder sb = new StringBuilder();
        sb.append('{').append(Hub.json("active")).append(":true");
        sb.append(',').append(Hub.json("serial")).append(':').append(Hub.json(String.valueOf(Hub.call(vm, "a0"))));
        sb.append(',').append(Hub.json("source")).append(':').append(Hub.num(Hub.call(vm, "c0"), 0));
        sb.append(',').append(Hub.json("useTime")).append(':').append(Hub.num(Hub.call(vm, "D"), 0));
        sb.append(',').append(Hub.json("point")).append(':').append(Hub.num(Hub.call(vm, "N"), 10000));
        sb.append(',').append(Hub.json("index")).append(':').append(Hub.num(Hub.call(vm, "O"), -1));
        sb.append(',').append(Hub.json("count")).append(':').append(l.size());
        sb.append(',').append(Hub.json("questions")).append(':').append('[');
        for (int i = 0; i < l.size(); i++) {
            Object st = l.get(i);
            Object qs = Hub.call(st, "getQuestion");
            if (!(qs instanceof List) || ((List<?>) qs).isEmpty()) continue;
            Object q = ((List<?>) qs).get(0);
            if (i > 0) sb.append(',');
            sb.append('{').append(Hub.json("i")).append(':').append(i);
            sb.append(',').append(Hub.json("structId")).append(':').append(Hub.num(Hub.call(st, "getId"), -1));
            sb.append(',').append(Hub.json("content")).append(':').append(Hub.json((String) Hub.call(st, "getContent")));
            sb.append(',').append(Hub.json("translate")).append(':').append(Hub.json((String) Hub.call(st, "getTranslate")));
            sb.append(',').append(Hub.json("wordId")).append(':').append(Hub.num(Hub.call(q, "getWordId"), 0));
            sb.append(',').append(Hub.json("questionId")).append(':').append(Hub.num(Hub.call(q, "getId"), 0));
            sb.append(',').append(Hub.json("answer")).append(':').append(Hub.json((String) Hub.call(q, "getAnswer")));
            sb.append('}');
        }
        sb.append(']');
        sb.append('}');
        return sb.toString();
    }

    public String submit(Map<String, String> q) {
        Activity a = cur();
        if (a == null) return "{\"ok\":false,\"error\":\"no activity\"}";
        Object vm = hub.findViewModel(a, VM);
        if (vm == null) return "{\"ok\":false,\"error\":\"no pk viewmodel\"}";
        Object structs = Hub.call(vm, "t0");
        if (!(structs instanceof List)) return "{\"ok\":false,\"error\":\"no structs\"}";
        List<?> l = (List<?>) structs;
        if (l.isEmpty()) return "{\"ok\":false,\"error\":\"empty structs\"}";
        int useMs = pi(q.get("use_time_ms"), 30000);
        int defPoint = Hub.num(Hub.call(vm, "N"), 10000);
        int done = 0;
        for (int i = 0; i < l.size(); i++) {
            Object st = l.get(i);
            Object qs = Hub.call(st, "getQuestion");
            if (!(qs instanceof List) || ((List<?>) qs).isEmpty()) continue;
            Object qb = ((List<?>) qs).get(0);
            String answer = (String) Hub.call(qb, "getAnswer");
            int point = defPoint;
            String custom = findAnswer(q.get("answers"), i);
            if (custom != null) {
                String[] parts = custom.split(",", 3);
                if (parts.length >= 2) answer = parts[1];
                if (parts.length >= 3) point = pi(parts[2], defPoint);
            }
            int wid = Hub.num(Hub.call(qb, "getWordId"), 0);
            int qid = Hub.num(Hub.call(qb, "getId"), 0);
            Hub.call(vm, "K0", new Class<?>[]{int.class}, new Object[]{Integer.valueOf(i)});
            Hub.call(vm, "z", new Class<?>[]{int.class, String.class, int.class, int.class},
                    new Object[]{Integer.valueOf(point), answer, Integer.valueOf(wid), Integer.valueOf(qid)});
            done++;
        }
        int seconds = useMs / 1000;
        if (seconds < 0) seconds = 0;
        Hub.call(vm, "C0", new Class<?>[]{int.class}, new Object[]{Integer.valueOf(seconds)});
        Object serial = Hub.call(vm, "a0");
        Hub.call(vm, "e1");
        hub.dbg("pkapi submit done=" + done + " useTime=" + seconds + "s serial=" + serial);
        return "{\"ok\":true,\"done\":" + done + ",\"use_time_s\":" + seconds + ",\"serial\":" + Hub.json(String.valueOf(serial)) + "}";
    }

    private String findAnswer(String blob, int i) {
        if (blob == null || blob.length() == 0) return null;
        for (String item : blob.split(";")) {
            String[] p = item.split(",", 2);
            if (p.length >= 2 && pi(p[0], -1) == i) return item;
        }
        return null;
    }
    private static int pi(String s, int def) { try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return def; } }
}
