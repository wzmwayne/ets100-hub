package com.ets.unpack.plugin;

import com.ets.unpack.hub.Hub;
import com.ets.unpack.hub.IPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 逆向辅助：签名探针 + 运行时抓取 app 自己的真实网络报文。 */
public class OraclePlugin implements IPlugin {

    private Hub hub;

    private static volatile String lastRequest = "";
    private static volatile String lastResponse = "";
    private static volatile String lastError = "";

    public String name() { return "Oracle 签名/抓包探针"; }
    public String version() { return "1.2.0"; }
    public String description() { return "/sign /rsign /opts /okhttp /capture"; }

    public void install(final Hub hub) {
        this.hub = hub;
        hub.addRoute("/sign", new Hub.Route() {
            public String handle(Map<String, String> q) { return sign(q.get("s")); }
        });
        hub.addRoute("/rsign", new Hub.Route() {
            public String handle(Map<String, String> q) { return rsign(q.get("d"), q.get("sn")); }
        });
        hub.addRoute("/opts", new Hub.Route() {
            public String handle(Map<String, String> q) { return opts(); }
        });
        hub.addRoute("/okhttp", new Hub.Route() {
            public String handle(Map<String, String> q) { return okhttp(); }
        });
        hub.addRoute("/capture", new Hub.Route() {
            public String handle(Map<String, String> q) { return capture(); }
        });
        hub.addRoute("/capture/log", new Hub.Route() {
            public String handle(Map<String, String> q) {
                return "{\"request\":" + Hub.json(lastRequest) + ",\"response\":" + Hub.json(lastResponse) + ",\"error\":" + Hub.json(lastError) + "}";
            }
        });
        hub.dbg("OraclePlugin installed");
    }

    private String sign(String s) {
        if (s == null) return "{\"ok\":false,\"error\":\"s required\"}";
        try {
            Class<?> c = Class.forName("com.ets100.secondary.utils.JniUtils");
            Object inst = c.getField("a").get(null);
            Method m = c.getMethod("getSign", String.class);
            Object r = m.invoke(inst, s);
            return "{\"ok\":true,\"sign\":" + Hub.json(String.valueOf(r)) + "}";
        } catch (Throwable t) { return "{\"ok\":false,\"error\":" + Hub.json(String.valueOf(t)) + "}"; }
    }

    private String rsign(String d, String sn) {
        if (d == null) return "{\"ok\":false,\"error\":\"d required\"}";
        try {
            Class<?> c = Class.forName("com.ets100.secondary.utils.JniUtils");
            Object inst = c.getField("a").get(null);
            Method m = c.getMethod("getResponseSign", String.class, String.class);
            Object r = m.invoke(inst, d, sn == null ? "" : sn);
            return "{\"ok\":true,\"sign\":" + Hub.json(String.valueOf(r)) + "}";
        } catch (Throwable t) { return "{\"ok\":false,\"error\":" + Hub.json(String.valueOf(t)) + "}"; }
    }

    private static Class<?> load(String name, ClassLoader... cls) {
        for (ClassLoader cl : cls) {
            if (cl == null) continue;
            try { return Class.forName(name, false, cl); } catch (Throwable ignored) {}
        }
        try { return Class.forName(name); } catch (Throwable ignored) {}
        return null;
    }

    private Object getRetrofit() throws Exception {
        Class<?> sg = Class.forName("com.ets100.secondary.model.api.ServiceGenerator");
        Object inst = sg.getField("INSTANCE").get(null);
        try {
            Method m = sg.getDeclaredMethod("getRetrofit");
            m.setAccessible(true);
            return m.invoke(inst);
        } catch (Throwable ignored) {}
        for (Field f : sg.getDeclaredFields()) {
            if ("kotlin.Lazy".equals(f.getType().getName())) {
                f.setAccessible(true);
                Object lazy = f.get(null);
                return lazy.getClass().getMethod("getValue").invoke(lazy);
            }
        }
        throw new Exception("retrofit not found");
    }

    private String opts() {
        StringBuilder sb = new StringBuilder("{\"ok\":true");
        try {
            Class<?> eh = Class.forName("com.ets100.network.EtsHttp");
            Object etsHttp = eh.getField("a").get(null);
            Object options = Hub.call(Hub.call(etsHttp, "f"), "a");
            sb.append(",\"options\":" + Hub.json(String.valueOf(options)));
        } catch (Throwable t) { sb.append(",\"opt_err\":" + Hub.json(String.valueOf(t))); }
        try {
            Object retrofit = getRetrofit();
            Object url = retrofit.getClass().getMethod("baseUrl").invoke(retrofit);
            sb.append(",\"retrofit_base\":" + Hub.json(String.valueOf(url)));
        } catch (Throwable t) { sb.append(",\"retrofit_err\":" + Hub.json(String.valueOf(t))); }
        return sb.append("}").toString();
    }

    private String okhttp() {
        StringBuilder sb = new StringBuilder("{\"ok\":true");
        try {
            Object retrofit = getRetrofit();
            Object cf = retrofit.getClass().getMethod("callFactory").invoke(retrofit);
            for (String fn : new String[]{"interceptors", "networkInterceptors"}) {
                try {
                    Field f = cf.getClass().getDeclaredField(fn);
                    f.setAccessible(true);
                    Object list = f.get(cf);
                    sb.append(",\"" + fn + "\":[");
                    if (list instanceof List) {
                        boolean first = true;
                        for (Object o : (List<?>) list) {
                            if (!first) sb.append(',');
                            first = false;
                            sb.append(Hub.json(String.valueOf(o)));
                        }
                    }
                    sb.append("]");
                } catch (Throwable t) { sb.append(",\"" + fn + "_err\":" + Hub.json(String.valueOf(t))); }
            }
        } catch (Throwable t) { sb.append(",\"err\":" + Hub.json(String.valueOf(t))); }
        return sb.append("}").toString();
    }

    private static void store(StringBuilder sb, String k, Object v) {
        if (sb.length() > 0) sb.append(", ");
        sb.append(k).append('=').append(v);
    }

    private String dumpRequest(Object req) {
        StringBuilder sb = new StringBuilder();
        try {
            store(sb, "url", req.getClass().getMethod("url").invoke(req));
            store(sb, "method", req.getClass().getMethod("method").invoke(req));
            store(sb, "headers", req.getClass().getMethod("headers").invoke(req));
        } catch (Throwable t) { store(sb, "hdr_err", t); }
        try {
            Object body = req.getClass().getMethod("body").invoke(req);
            if (body != null) {
                ClassLoader cl = body.getClass().getClassLoader();
                Class<?> bufCls = load("okio.Buffer", cl, OraclePlugin.class.getClassLoader(), Thread.currentThread().getContextClassLoader());
                if (bufCls == null) { store(sb, "body_err", "okio.Buffer not found"); return sb.toString(); }
                Object buf = bufCls.newInstance();
                Method w = null;
                for (Method mm : body.getClass().getMethods()) {
                    if (mm.getName().equals("writeTo") && mm.getParameterCount() == 1) { w = mm; break; }
                }
                if (w == null) { store(sb, "body_err", "no writeTo"); return sb.toString(); }
                w.setAccessible(true); w.invoke(body, buf);
                store(sb, "body", bufCls.getMethod("readUtf8").invoke(buf));
            }
        } catch (Throwable t) { store(sb, "body_err", t); }
        return sb.toString();
    }

    private Object addLoggingInterceptor(Object retrofit) throws Exception {
        final Object cf = retrofit.getClass().getMethod("callFactory").invoke(retrofit);
        ClassLoader cl = cf.getClass().getClassLoader();
        final Class<?> iface = Class.forName("okhttp3.Interceptor", false, cl);
        final Class<?> reqCls = Class.forName("okhttp3.Request", false, cl);
        Object proxy = Proxy.newProxyInstance(cl, new Class<?>[]{iface}, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) throws Throwable {
                if ("intercept".equals(m.getName()) && a != null && a.length == 1) {
                    Object chain = a[0];
                    Object req;
                    try { req = chain.getClass().getMethod("request").invoke(chain); }
                    catch (Throwable t) { lastError = "request(): " + t; return null; }
                    try { lastRequest = dumpRequest(req); } catch (Throwable t) { lastError = "dump: " + t; }
                    Object resp;
                    try { resp = chain.getClass().getMethod("proceed", reqCls).invoke(chain, req); }
                    catch (Throwable t) { lastError = "proceed: " + t; throw t; }
                    try {
                        Object peek = resp.getClass().getMethod("peekBody", long.class).invoke(resp, Long.MAX_VALUE);
                        lastResponse = String.valueOf(peek.getClass().getMethod("string").invoke(peek));
                    } catch (Throwable t) { lastResponse = "peek_err: " + t; }
                    return resp;
                }
                Class<?> rt = m.getReturnType();
                if (rt == boolean.class) return Boolean.FALSE;
                if (rt == int.class) return Integer.valueOf(0);
                return null;
            }
        });
        Object builder = cf.getClass().getMethod("newBuilder").invoke(cf);
        builder.getClass().getMethod("addInterceptor", iface).invoke(builder, proxy);
        Object newCf = builder.getClass().getMethod("build").invoke(builder);
        for (Field f : retrofit.getClass().getDeclaredFields()) {
            if (f.getType().getName().equals("okhttp3.Call$Factory") || f.getName().equals("callFactory")) {
                f.setAccessible(true);
                f.set(retrofit, newCf);
            }
        }
        return newCf;
    }

    private String capture() {
        StringBuilder sb = new StringBuilder("{\"ok\":true");
        lastRequest = ""; lastResponse = ""; lastError = "";
        try {
            Object retrofit = getRetrofit();
            Object svc = null;
            Class<?> etsCls = Class.forName("com.ets100.syncword.request.SyncWordEtsClient");
            Object etsInst = etsCls.getField("INSTANCE").get(null);
            String svcType = "com.ets100.syncword.request.SyncWordEtsService";
            for (Field f : etsCls.getDeclaredFields()) {
                if (f.getType().getName().equals(svcType)) { f.setAccessible(true); svc = f.get(etsInst); break; }
            }
            if (svc == null) {
                for (Field f : etsCls.getDeclaredFields()) {
                    f.setAccessible(true);
                    Object v = f.get(etsInst);
                    if (v != null && Proxy.isProxyClass(v.getClass())) { svc = v; break; }
                }
            }
            if (svc == null) return sb.append(",\"error\":\"service not found\"}").toString();
            addLoggingInterceptor(retrofit);
            final CountDownLatch latch = new CountDownLatch(1);
            ClassLoader cl = etsCls.getClassLoader();
            Class<?> contCls = Class.forName("kotlin.coroutines.Continuation", false, cl);
            Object cont = Proxy.newProxyInstance(cl, new Class<?>[]{contCls}, new InvocationHandler() {
                public Object invoke(Object p, Method m, Object[] a) throws Throwable {
                    if ("resumeWith".equals(m.getName())) { latch.countDown(); return null; }
                    if ("getContext".equals(m.getName())) {
                        Class<?> e = load("kotlin.coroutines.EmptyCoroutineContext", p.getClass().getClassLoader());
                        if (e != null) return e.getField("INSTANCE").get(null);
                        return null;
                    }
                    return null;
                }
            });
            Class<?> iface = Class.forName(svcType);
            Method m = iface.getMethod("getWordBattleCount", Map.class, contCls);
            Object ret = m.invoke(svc, new java.util.LinkedHashMap<Object, Object>(), cont);
            boolean done = latch.await(20, TimeUnit.SECONDS);
            sb.append(",\"ret\":" + Hub.json(String.valueOf(ret)));
            sb.append(",\"resumed\":" + done);
        } catch (Throwable t) { sb.append(",\"error\":" + Hub.json(String.valueOf(t))); }
        sb.append(",\"request\":" + Hub.json(lastRequest));
        sb.append(",\"response\":" + Hub.json(lastResponse));
        sb.append(",\"inner_error\":" + Hub.json(lastError));
        return sb.append("}").toString();
    }
}
