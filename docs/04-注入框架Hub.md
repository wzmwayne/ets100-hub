# 04 · 注入框架 HUB

HUB 是随 APK 一起打包的 `shim.dex`，在 Application 启动时安装，
提供**悬浮球 / 面板 / 反射工具 / 本地 HTTP API / 网页管理台 / 插件体系**。

## 1. 启动链路

```
AndroidManifest: android:name="com.ets.unpack.repack.SafeApp"
        │
        ├── SafeApp extends com.ets100.secondary.app.EtsApp   ← 真实 Application
        │     attachBaseContext(): try/catch 包裹 super，异常落盘
        │     onCreate():          try/catch 包裹 super，异常落盘
        │
        ├── CP  (ContentProvider, 在 Application.onCreate 之前执行)
        │     → 安装 CrashGuard + 绑定 Context
        │
        ├── CrashGuard
        │     Thread.setDefaultUncaughtExceptionHandler
        │     → 记录堆栈到 crash.log，**不交给系统默认处理**（阻止闪退弹窗/进程被杀）
        │
        ├── AP (Application/Activity/Service/Receiver/Provider 实例化包装)
        │     → 记录 + 兜底
        │
        └── Hub.install(ctx)
              Application.ActivityLifecycleCallbacks
              onActivityResumed → attachButton(浮球)
```

异常日志路径：
```
/sdcard/Android/data/com.ets100.repackapp/files/crash.log
（回退 getExternalFilesDir → 内部 files/crash.log）
```

## 2. 悬浮球与面板

| 项 | 实现 |
|---|---|
| 位置 | `Gravity.BOTTOM\|END`，右下角，距底 150dp |
| 外观 | 46dp 圆形，蓝色渐变，文字 "HUB" |
| 拖动 | `OnTouchListener` 统一处理（避免误触：位移 > 6dp 才算拖） |
| 点击 | 打开面板 `showPanel()` |
| 生命周期 | `onActivityResumed` 挂载 / `onActivityDestroyed` 卸载 |

面板 = 滚动列表，每一项是一个 `Feature`：

```java
hub.add(new Hub.Feature() {
    public String title() { return "菜单标题"; }
    public void run(Activity a, Hub h) { /* 点击行为 */ }
});
```

内置菜单（`Features.java`）：

| 菜单 | 功能 |
|---|---|
| 设备/环境信息 | 包名、版本、SDK、设备型号、当前 Activity |
| 动画速度设置 | `/anim?speed=&correct=&wrong=` |
| 查看动画配置 | 显示当前值 |
| 切换 PK 音效 | `/pk/audio?value=1` |
| 打开管理网页 | 打开 `http://127.0.0.1:8765/` |
| HTTP API 状态 / 地址 | 显示端口与内网地址 |
| 启动 / 停止 HTTP API | — |
| 查看崩溃日志 | 读 `crash.log` |
| 重启应用 | `/restart` |

## 3. 本地 HTTP API（端口 8765）

| 路由 | 说明 |
|---|---|
| `GET /` `/web` | **内置网页管理台**（插件上传/卸载/重启） |
| `GET /health` | `{"ok":true,"app":"..."}` |
| `GET /info` | 包名/版本/设备/当前 Activity/插件目录 |
| `GET /log` `/log/clear` | 日志 |
| `GET /dbg` `/dbg/clear` | 调试日志 |
| `GET /frag` | 当前 Fragment 列表 |
| `GET /view/dump?depth=6` | View 树 |
| `GET /vm/dump` | ViewModel 反射 dump |
| `GET /call?target=vm|frag|activity|app&method=NAME&arg0=..` | **反射调用方法** |
| `GET /field?target=vm&name=NAME` | 反射读字段 |
| `GET /toast?msg=..` | 弹 Toast |
| `GET /anim?...` | 动画调速 |
| `GET /pk/audio?value=1` | PK 音效开关 |
| `GET /plugins` | 插件列表 + 目录 + 目录文件 |
| `POST /plugins/upload?name=x.dex` | **上传插件（原始 body）** |
| `GET /plugins/install?src=/sdcard/Download/x.dex` | 从文件安装 |
| `GET /plugins/remove?name=x.dex` | 删除插件 |
| `GET /restart` | 重启应用 |

**插件安装/删除后不立即生效，必须重启应用**（已移除动态重载，避免残留注入）。

## 4. 内置网页管理台（webUi）

```
EtsHub 插件管理
├── 目录: /storage/emulated/0/Android/data/com.ets100.repackapp/files/plugins
├── [选择 dex] [上传安装] [重启应用]
└── 插件卡片列表（名称/版本/描述/文件 + [卸载]）
        + 目录文件列表（[删除]）
```

交互逻辑（v28 起）：

1. **上传安装** → 成功后询问
   `插件 x.dex 已上传安装。需要重启应用才会生效，现在重启吗？`
   - 确定 → `应用正在重启，约 10 秒后请手动刷新本页面。`
   - 取消 → `已保存，重启应用后生效，请刷新本页面查看最新列表。`
2. **卸载** → 先 `确定卸载 x.dex 吗？` → 再询问重启（同上）
3. 上传/删除都会检查返回结果，失败弹 `上传失败: ... / 删除失败: ...`
4. 每次变更都提示 **刷新页面**

## 5. 插件体系

### 5.1 接口

```java
public interface IPlugin {
    void install(Hub hub);
    default String name()        { return getClass().getSimpleName(); }
    default String version()     { return ""; }
    default String description() { return ""; }
}
```

### 5.2 加载机制

```
files/plugins/*.dex
   → DexClassLoader(dex, codeCacheDir/hubopt, null, Hub.class.getClassLoader())
   → DexFile.entries() 遍历类
   → IPlugin 的实现类 → newInstance().install(hub)
   → 记录它新增的 Feature 与 Route（卸载时可精确移除）
```

- 只在 **Application 启动时**加载一次
- 插件可注册：菜单项（`hub.add`）+ HTTP 路由（`hub.addRoute`）
- 每个插件独立编译为**一个 dex**（`scripts/build_plugin.sh`）

### 5.3 插件目录

```
/storage/emulated/0/Android/data/com.ets100.repackapp/files/plugins/
```

设备上可用 `adb push`、或网页上传、或 `/plugins/install?src=/sdcard/Download/x.dex`
（注意：`/sdcard/Android/data/...` 在新版 Android 上不可直接写，需用 App 自身权限）

## 6. 反射工具（Hub 静态方法）

```java
Hub.call(Object o, String method, Class<?>[] types, Object[] args)  // 调方法
Hub.call(Object o, String method)                                   // 无参
Hub.field(Object o, String name)                                    // 读字段
Hub.num(Object o, int def)                                          // 数值转换
hub.findViewModel(Activity a, String className)                     // 找 ViewModel
hub.findPkFragment(Activity a)                                      // 找 PK Fragment
```

这些是插件与 App 内部交互的主要手段（App 大量使用 Kotlin/混淆类名，只能反射）。
