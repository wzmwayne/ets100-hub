#!/bin/bash
# 无壳 + 改包名 + 移除推送 + 防闪退 (v12: 保留META-INF/services; 11个真实dex)
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK="$ROOT/build_work_safe"
ORIG="$ROOT/original/E听说中学_7.0.67.2495.apk"
OLD="com.ets100.secondary"
NEW="com.ets100.repackapp"
DEXSRC="$ROOT/../unpacked_dex"
UNFIDX=10   # dex_out_10 定义 com/ets100/secondary/app/EtsApp
mkdir -p "$WORK" "$ROOT/deshell"
bash "$ROOT/scripts/build_shim.sh"
unzip -p "$ORIG" AndroidManifest.xml > "$WORK/AndroidManifest.xml"
python3 "$ROOT/scripts/axml_strip_push.py" "$WORK/AndroidManifest.xml"
python3 "$ROOT/scripts/axml_repackage.py" "$WORK/AndroidManifest.xml" "$OLD" "$NEW" "com.ets.unpack.repack.SafeApp"
rm -rf "$WORK/dex"; mkdir -p "$WORK/dex"
python3 "$ROOT/scripts/dex_restore_inline.py" "$DEXSRC" "$WORK/dex"
python3 "$ROOT/scripts/patch_anim.py" "$WORK/dex/dex_out_4.dex"
python3 "$ROOT/scripts/dex_rename_pkg.py" "$WORK/dex" "$OLD" "$NEW"
python3 "$ROOT/scripts/dex_unfinal.py" "$WORK/dex/dex_out_$UNFIDX.dex"
python3 "$ROOT/scripts/repack.py" "$ORIG" "$WORK/AndroidManifest.xml" "$WORK/dex" \
  "$ROOT/shim_java/shim.dex" "$WORK/safe_dex.apk"
zipalign -f -p 4 "$WORK/safe_dex.apk" "$WORK/safe_aligned.apk"
apksigner sign --ks "$ROOT/keys/deshell.keystore" --ks-pass pass:android --key-pass pass:android \
  --out "$ROOT/deshell/safe28-release.apk" "$WORK/safe_aligned.apk"
apksigner verify "$ROOT/deshell/safe28-release.apk"
echo "OK: $ROOT/deshell/safe28-release.apk"
