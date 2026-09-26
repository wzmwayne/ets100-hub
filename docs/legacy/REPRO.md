# E听说中学 7.0.67.2495 静态去壳修复记录

## 关键结论
SecNeo 壳把部分 code_item 的 insns 区加密/清零，并把真实 insns 搬到文件尾部
`BBbb.dgc` 区块，同时给出重定位表。之前通过改写 class_data.code_off 指向尾部
的做法破坏了 dex map（ART: `Duplicate map section of type 2001`）。

正确做法：把尾部真实 insns 原地写回每个 code_item 的 insns 区（只拷贝
`16 + 2*insns_size` 字节，保留原地 try/catch 数据），再截掉尾部区块、修正
file_size/sha1/adler32。map 不动，class_data 不动。

## 目录
- `../unpacked_dex/dex_N.raw`：从壳解出（已 SM4 解密前缀）的原始 dex
- `scripts/dex_restore_inline.py`：原地还原 insns（核心修复）
- `scripts/build_safe.sh`：完整构建（改包名/去推送/还原 dex/签名）
- `shim_java/`：AppComponentFactory + CrashGuard
- `deshell/safe6-release.apk`：产物

## 复现
```
bash scripts/build_safe.sh
```
