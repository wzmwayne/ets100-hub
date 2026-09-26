# E听说中学 7.0.67.2495 去壳可用构建说明（safe9）

状态：**已成功启动并登录**。

最终产物：`deshell/safe9-release.apk`
- 包名 `com.ets100.repackapp`
- size 106,894,735
- sha256 26a182233eeef23a7cdf1b35b04882aa22138bd40540480602d7548310a4af4f

## 完整修复链（踩坑顺序）

1. **safe4/5 — dex map 损坏**
   壳把部分 code_item 的 insns 加密/清零，真实指令放在文件尾部 `BBbb.dgc` 区块。
   之前改写 class_data.code_off 指向尾部 → ART 报 `Duplicate map section of type 2001`。
   修复：`scripts/dex_restore_inline.py` 只把尾部 `16+2*insns_size` 字节原地拷回，
   保留原地 try/catch 数据，截掉尾部区块并修 file_size/sha1/adler32；map 与 class_data 不动。

2. **safe6 — 漏掉 primary dex**
   壳的 zstd blob 里有 **11 个 dex**，描述符表基址 0xF50；脚本按 10 个算成 0xF60，
   漏掉第一个 primary dex（22,257,200B），导致 `FastSafeIterableMap` 等类缺失。
   修复：`scripts/dex_extract_all.py` 按 11 个提取。

3. **safe8 — 误删 META-INF/services**
   `repack.py` 之前丢弃整个 `META-INF/`，删掉了
   `META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory`，
   导致 `JetApp.<clinit>` 失败、Kotlin Main dispatcher 缺失、Koin 未启动。
   修复：只丢签名文件（MANIFEST.MF/*.SF/*.RSA/*.DSA/*.EC），保留其余 META-INF。

4. **safe9 — 误删 libdexjni.so**
   `com.fort.andjni.JniLib` 的 native 反射桥 `cB/cC/.../cV` 由 `libdexjni.so` 提供；
   之前连同主壳 `libDexHelper*` 一起删了 → `UnsatisfiedLinkError`。
   修复：只剔除 `libDexHelper*`，保留 `libdexjni.so`（它只依赖 libc/libm/libdl/liblog/libstdc++）。

## 构建
```
bash scripts/build_safe.sh
```

