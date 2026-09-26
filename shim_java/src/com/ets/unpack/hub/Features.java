package com.ets.unpack.hub;

import android.app.Activity;
import android.util.Log;

/** 内置功能（保持通用）：PK自动答题等业务功能请用外部插件实现。 */
public final class Features {

    public static void register(final Hub hub) {
        hub.add(new Hub.Feature() {
            public String title() { return "设备 / 应用信息"; }
            public void run(Activity a, Hub h) { h.showText(a, "设备 / 应用信息", h.info(a)); }
        });
        hub.add(new Hub.Feature() {
            public String title() { return "动画调速（正确/错误/倍速）"; }
            public void run(final Activity a, final Hub h) {
                android.widget.LinearLayout ll = new android.widget.LinearLayout(a);
                ll.setOrientation(android.widget.LinearLayout.VERTICAL);
                int p = h.dp(16); ll.setPadding(p, p, p, p);
                android.widget.TextView l1 = new android.widget.TextView(a);
                l1.setText("正确动画时长 ms（0 = 用倍速）"); l1.setTextColor(0xFFBBBBBB); l1.setTextSize(12f);
                ll.addView(l1);
                final android.widget.EditText e1 = new android.widget.EditText(a);
                e1.setText(String.valueOf(Tuning.getCorrectMs())); e1.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                ll.addView(e1);
                android.widget.TextView l2 = new android.widget.TextView(a);
                l2.setText("错误动画时长 ms（0 = 用倍速）"); l2.setTextColor(0xFFBBBBBB); l2.setTextSize(12f);
                ll.addView(l2);
                final android.widget.EditText e2 = new android.widget.EditText(a);
                e2.setText(String.valueOf(Tuning.getWrongMs())); e2.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                ll.addView(e2);
                android.widget.TextView l3 = new android.widget.TextView(a);
                l3.setText("倍速（1.0 正常，2.0 更快，0.5 更慢）"); l3.setTextColor(0xFFBBBBBB); l3.setTextSize(12f);
                ll.addView(l3);
                final android.widget.EditText e3 = new android.widget.EditText(a);
                e3.setText(String.valueOf(Tuning.getSpeed()));
                e3.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
                ll.addView(e3);
                new android.app.AlertDialog.Builder(a).setTitle("动画调速").setView(ll)
                        .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                int cm = 0, wm = 0; float sp = 1f;
                                try { cm = Integer.parseInt(e1.getText().toString().trim()); } catch (Throwable ignored) {}
                                try { wm = Integer.parseInt(e2.getText().toString().trim()); } catch (Throwable ignored) {}
                                try { sp = Float.parseFloat(e3.getText().toString().trim()); } catch (Throwable ignored) {}
                                h.toast(Tuning.setConfig(cm, wm, sp));
                            }
                        })
                        .setNegativeButton("取消", null).show();
            }
        });
        hub.add(new Hub.Feature() {
            public String title() { return "查看动画配置"; }
            public void run(Activity a, Hub h) { h.showText(a, "动画配置", Tuning.getConfig()); }
        });
        hub.add(new Hub.Feature() {
            public String title() { return "切换 PK 音效"; }
            public void run(Activity a, Hub h) { h.toast(h.togglePkAudio()); }
        });
        hub.add(new Hub.Feature() {
            public String title() { return "打开管理网页"; }
            public void run(Activity a, Hub h) {
                String url = "http://127.0.0.1:" + h.httpPort() + "/";
                try {
                    android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
                    i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    h.ctx().startActivity(i);
                } catch (Throwable t) {
                    h.toast("打开失败，请手动访问 " + url);
                }
            }
        });
        hub.add(new Hub.Feature() {
            public String title() { return "HTTP API 状态 / 地址"; }
            public void run(Activity a, Hub h) {
                h.showText(a, "HTTP API", "status: " + h.httpStatus()
                        + "\nbase: http://" + h.firstIp() + ":" + h.httpPort() + "/help"
                        + "\n\n调试: /info /dbg /frag /view/dump /vm/dump /call /field /toast"
                        + "\nPK音效: /pk/audio?value=1"
                        + "\n插件: /plugins /plugins/install?src=/sdcard/Download/xx.dex /plugins/remove?name=xx.dex  (安装/删除后需重启应用)"
                        + "\n其它: /log /log/clear /restart");
            }
        });
        hub.add(new Hub.Feature() {
            public String title() { return "启动 HTTP API"; }
            public void run(Activity a, Hub h) { h.toast(h.startHttp()); }
        });
        hub.add(new Hub.Feature() {
            public String title() { return "停止 HTTP API"; }
            public void run(Activity a, Hub h) { h.toast(h.stopHttp()); }
        });
        hub.add(new Hub.Feature() {
            public String title() { return "查看崩溃日志"; }
            public void run(Activity a, Hub h) { h.showText(a, "crash.log", h.readLog()); }
        });
        hub.add(new Hub.Feature() {
            public String title() { return "清空崩溃日志"; }
            public void run(Activity a, Hub h) { h.clearLog(); h.toast("已清空"); }
        });
        hub.add(new Hub.Feature() {
            public String title() { return "插件列表（名称/版本/说明）"; }
            public void run(Activity a, Hub h) {
                StringBuilder sb = new StringBuilder();
                java.util.List<Hub.PluginInfo> ps = h.pluginInfos();
                if (ps.isEmpty()) sb.append("(未加载任何插件)\n目录: ").append(h.pluginDir());
                for (int i = 0; i < ps.size(); i++) {
                    Hub.PluginInfo p = ps.get(i);
                    sb.append(i + 1).append(". ").append(p.name).append("  v").append(p.version).append("\n");
                    sb.append("   ").append(p.description).append("\n");
                    sb.append("   class: ").append(p.className).append("\n   file: ").append(p.file).append("\n\n");
                }
                h.showText(a, "插件列表", sb.toString());
            }
        });
        hub.add(new Hub.Feature() {
            public String title() { return "重载外部插件"; }
            public void run(Activity a, Hub h) { h.loadPlugins(); h.toast("已尝试重载: " + h.pluginDir()); }
        });
        hub.add(new Hub.Feature() {
            public String title() { return "重启应用"; }
            public void run(Activity a, Hub h) { h.restart(); }
        });
        Log.i("EtsHub", "builtin features: " + hub.features().size());
    }
}
