package com.ets.unpack.plugin;

import android.app.Activity;
import com.ets.unpack.hub.Hub;
import com.ets.unpack.hub.IPlugin;
import java.util.Map;

/** 外部插件示例：编译成 dex 放到 files/plugins/ 即自动加载。 */
public class SamplePlugin implements IPlugin {
    public String name() { return "Sample 示例插件"; }
    public String version() { return "1.0.0"; }
    public String description() { return "演示插件：面板功能 + /hello 路由"; }

    public void install(final Hub hub) {
        hub.add(new Hub.Feature() {
            public String title() { return "【插件】示例功能"; }
            public void run(Activity a, Hub h) {
                h.showText(a, "外部插件", "插件已生效\n包名: " + h.ctx().getPackageName() + "\nHTTP: " + h.httpStatus());
            }
        });
        hub.addRoute("/hello", new Hub.Route() {
            public String handle(Map<String, String> q) {
                return "{\"ok\":true,\"from\":\"sample-plugin\",\"msg\":" + Hub.json(q.get("msg")) + "}";
            }
        });
        hub.dbg("SamplePlugin installed");
    }
}
