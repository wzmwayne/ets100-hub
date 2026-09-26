# 02 · 去壳与 DEX 提取

## 1. 壳的特征

原始 APK 被 **SecNeo（梆梆/娜迦系）** 加固：

| 位置 | 内容 |
|---|---|
| `lib/arm64-v8a/libDexHelper1788501371.so` | 壳运行时 + 加密的 dex 数据 |
| `lib/arm64-v8a/libdexjni.so` | `com.fort.andjni.JniLib` 的 native 实现 |
| `assets/BBbb.dgc` 等 | 加密数据段 / 尾部附加 insns |
| `com.secneo.apkwrapper.AW / AP / CP` | 壳的入口包装类 |
| `AndroidManifest.xml` 的 application | 指向 `com.secneo.apkwrapper.AW`（壳） |

现象：apktool 解出来的 dex 里，业务方法体全是
```smali
invoke-static {v0, v1}, Lcom/fort/andjni/JniLib;->cL([Ljava/lang/Object;)Ljava/lang/Object;
```
即方法体被抽取，真正的代码在 native/blob 里。

## 2. 提取流程（脚本化）

```
libDexHelper*.so / assets blob
        │  dex_extract_all.py
        ▼
  unpacked_dex/dex_out_0..10.dex      ← 11 个真实 dex（基址 0xF50）
        │  dex_restore_inline.py
        ▼
  把 BBbb.dgc 尾部 insns 原地写回（修复 Duplicate map section of type 2001）
        │  dex_fix_codemap.py
        ▼
  修正 code_item / debug_info 偏移表
        │  dex_unfinal.py
        ▼
  清除 ACC_FINAL（EtsApp 等需要被继承）
        │  fix_bad_resource_names.py
        ▼
  修复资源名非法字符
```

### 2.1 为什么是 11 个 dex

`libDexHelper*.so` 里的数据不是标准 zip，是一段带头部描述的连续 blob。
逐个 dex 头 (`dex\n035`) 扫描 + 长度校验后，确认 **11 个**：

```bash
python3 scripts/dex_extract_all.py <libDexHelper.so> ../../unpacked_dex 0xF50
```

> 关键点：`com.ets100.secondary.app.EtsApp` 定义在 `dex_out_10.dex`，
> `FastSafeIterableMap` 等 androidx 类也只在 blob 里，缺失会直接 `ClassNotFoundException`。

### 2.2 `Duplicate map section of type 2001`

原因：`dex_restore_inline.py` 早期版本往尾部追加了 insns，导致 map_list 出现重复段。
修复方式：**原地写回**（把尾部 insns 覆盖回它原本的位置），而不是追加。

### 2.3 方法体恢复

绝大部分方法体在提取出的 dex 里已经是**真实 smali**（不是 `JniLib` 调用）。
只有少量类仍是 `JniLib.cL/cV` 桩（例如
`com.ets100.secondary.model.api.interceptor.EtsHttpInterceptor`），
它们的实现确实在 native 里 —— 这部分我们用**运行时抓包**绕过（见 05 文档）。

## 3. 反编译产物的保存

**重要约定：反编译输出一律落盘，不要重复解包**（耗时且没必要）：

| 目录 | 内容 | 用哪个工具产出 |
|---|---|---|
| `apktool_out/` | 资源 + AndroidManifest.xml + lib | apktool d |
| `jadx_out/` | 原始 APK 的 Java 源码 | jadx -d |
| `unpacked_dex/` | 去壳后的 11 个真实 dex | dex_extract_all.py |
| `unpacked_dex_smali/` | 真实 dex 的 smali | baksmali -o |
| `unpacked_dex_src/` | 部分 dex 的 Java 源码 | jadx |

按需局部反编译（快得多）：

```bash
jadx --single-class com.ets100.network.ets.interceptor.EtsGzHttpInterceptor \
     -d analysis/jadx_single unpacked_dex/dex_out_1.dex
```

## 4. 反汇编 native

```bash
aarch64-linux-gnu-objdump -d --start-address=0x62a4c --stop-address=0x62d4c \
    apktool_out/lib/arm64-v8a/libmd5.so
```

或使用脚本 `analysis/arm64_disasm.py`（基于 capstone，可读 ELF 段与字符串）。

## 5. 相关脚本

| 脚本 | 作用 |
|---|---|
| `dex_extract_all.py` | 从壳 blob 中提取全部真实 dex |
| `dex_restore_inline.py` | 尾部 insns 原地写回，修 map 段重复 |
| `dex_fix_codemap.py` | 修 code_item/debug_info 偏移 |
| `dex_unfinal.py` | 清 ACC_FINAL |
| `fix_bad_resource_names.py` | 修资源名非法字符 |
