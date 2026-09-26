#!/bin/bash
# 无壳重建流水线（不重建资源，仅二进制补丁 AndroidManifest.xml + 重打包）
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK="$ROOT/build_work"
ORIG="$ROOT/original/E听说中学_7.0.67.2495.apk"
mkdir -p "$WORK" "$ROOT/deshell"

# 1) 提取并二进制补丁 AndroidManifest.xml：壳 Application -> 真实 Application
unzip -p "$ORIG" AndroidManifest.xml > "$WORK/AndroidManifest.xml"
python3 "$ROOT/scripts/axml_patch.py" "$WORK/AndroidManifest.xml" \
  "com.secneo.apkwrapper.AW" "com.ets100.secondary.app.EtsApp"

# 2) 汇编壳组件 shim（AppComponentFactory AP / ContentProvider CP）
smali a -o "$WORK/shim.dex" "$ROOT/shim"

# 3) 重打包：删除 classes*.dex / META-INF / assets/meta-data / libDexHelper* / libdexjni.so，
#    写入补丁后的清单、shim.dex、10 个还原 DEX
python3 "$ROOT/scripts/repack.py" "$ORIG" "$WORK/AndroidManifest.xml" \
  "$ROOT/dex" "$WORK/shim.dex" "$WORK/deshell_dex.apk"

# 4) 对齐与签名
zipalign -f -p 4 "$WORK/deshell_dex.apk" "$WORK/deshell_aligned.apk"
apksigner sign --ks "$ROOT/keys/deshell.keystore" --ks-pass pass:android --key-pass pass:android \
  --out "$ROOT/deshell/deshell-release.apk" "$WORK/deshell_aligned.apk"
apksigner verify "$ROOT/deshell/deshell-release.apk"
echo "OK: $ROOT/deshell/deshell-release.apk"
