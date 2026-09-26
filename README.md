# E听说中学 7.0.67.2495 · 去壳 / 改包 / 注入框架 / 单词PK自动化

本仓库是「E听说中学」(com.ets100.secondary) Android 客户端的静态逆向与二次改造工程记录，
包含**可复现的反编译方法、去壳脚本、改包构建流水线、注入框架（HUB）、插件体系、网络协议还原、单词PK 自动化**。

> 仅供个人学习研究使用，请勿用于任何商业或非法用途。请自行承担使用风险。

---

## 一、成果总览

| 产物 | 说明 |
|---|---|
| `safe28-release.apk` | 当前成品：去壳 + 改包名(`com.ets100.repackapp`) + 移除推送 + 防闪退 + 内置 HUB 框架 |
| `plugins/etshub-plugin-*.dex` | 7 个 HUB 插件（外部 dex，放进 `files/plugins/` 即生效） |
| `scripts/` | 全套 Python/Shell 工具链（去壳、dex 修复、改包、构建、插件编译、PK 客户端） |
| `shim_java/` | 注入框架源码（Hub / CrashGuard / SafeApp / AP / CP）+ 全部插件源码 |
| `docs/` | 完整方法论文档（从原始 APK 到成品的每一步） |
| `reversing/` | 逆向分析笔记（壳分析、PK 模块、网络协议） |

**原始 APK**：`E听说中学_7.0.67.2495.apk`
`sha256 = 127da9671d87a313c93a27cf0ae04c9256e66b07cf9772864cadfd84b1694427`

**成品 APK**：`safe28-release.apk`（106,939,791 字节）
`sha256 = 41fc36bfa6d20fa5ebe155d7313eb8c9cd4c84fd1caf7ff5a88766c2e720b707`

---

## 二、目录结构

```
ets100-hub/
├── README.md                    ← 本文件
├── docs/                        ← 方法论文档
│   ├── 01-环境与工具.md
│   ├── 02-去壳与DEX提取.md
│   ├── 03-改包与构建.md
│   ├── 04-注入框架Hub.md
│   ├── 05-网络协议与签名.md
│   ├── 06-单词PK模块.md
│   ├── 07-插件开发指南.md
│   ├── 08-插件清单.md
│   ├── 09-脚本说明.md
│   ├── 10-复现步骤.md
│   ├── 11-版本与产物.md
│   └── legacy/                  ← 早期记录
├── scripts/                     ← 工具链
├── shim_java/
│   ├── src/                     ← Hub 框架源码
│   └── plugin_sample/src/       ← 插件源码
├── plugins/                     ← 插件 dex 产物
├── keys/deshell.keystore        ← 签名证书（口令见构建脚本）
└── reversing/                   ← 逆向笔记
```

---

## 三、快速开始

### 1. 从原始 APK 复现成品 APK

参见 [docs/10-复现步骤.md](docs/10-复现步骤.md)，核心就一条：

```bash
# 需要 /tmp/android-sdk（android.jar API34 + build-tools 35.0.0 的 d8）
PROJ=/path/to/this/repo bash scripts/build_safe.sh
```

### 2. 编译插件

```bash
bash scripts/build_plugin.sh        # 产出 plugins/etshub-plugin-<Class>.dex
```

### 3. 装插件（设备）

HUB 面板 → 打开管理网页 → 上传安装 → **重启应用**（插件只在启动时加载）

### 4. 单词PK 自动化（Python 独立客户端）

```bash
python3 scripts/pk_client.py probe          # 连通性 + 签名自检
python3 scripts/pk_client.py pk 10 0        # 打一局（10题/用时0秒）
python3 scripts/pk_client.py repk 10 50 0 8 # 50局/8线程
python3 scripts/pk_client.py repk 10 50 0 0 # 线程数=0 => 动态并发
```

---

## 四、核心结论速查

| 主题 | 结论 |
|---|---|
| 壳 | SecNeo（`libDexHelper*.so` + `assets` 加密 blob），可静态提取出 11 个真实 dex |
| 改包名 | 二进制补丁 AndroidManifest.xml + `dex_rename_pkg.py` 改 dex 内常量 |
| 防闪退 | 自写 `SafeApp` 继承真实 Application + `CrashGuard` 吞掉未捕获异常 |
| 注入方式 | `shim.dex` 随 APK 打包，Application 指向 SafeApp；插件为外部 dex，启动时加载 |
| 网络入口 | `POST https://api.ets100.com/<r>`（路径就是接口名，不是根路径） |
| 请求签名 | `md5("androidV3" + time + body + "717e3cdc7314959fa2ef469d38f05673")` |
| 签名来源 | `libmd5.so` 的 `Java_com_ets100_secondary_utils_JniUtils_getSign @ 0x62a4c` 反汇编 |
| PK 接口 | `g/pk/start-word-pk` + `g/pk/submit-word`，10 题，`use_time=0` 必赢 |
| 并发瓶颈 | 服务端每账号约 40 请求/秒（≈20 局/秒），脚本侧已无瓶颈 |

---

## 五、免责声明

本项目仅用于**个人设备上的学习与研究**。所有商标、App 内容版权归原公司所有。
请勿传播、售卖或用于任何违法用途。
