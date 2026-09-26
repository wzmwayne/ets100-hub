#!/bin/bash
# 无壳 + 改包名 + 移除全部推送服务
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK="$ROOT/build_work_nopush"
ORIG="$ROOT/original/E听说中学_7.0.67.2495.apk"
OLD="com.ets100.secondary"
NEW="com.ets100.repackapp"
mkdir -p "$WORK" "$ROOT/deshell"

unzip -p "$ORIG" AndroidManifest.xml > "$WORK/AndroidManifest.xml"
python3 "$ROOT/scripts/axml_strip_push.py" "$WORK/AndroidManifest.xml"
python3 "$ROOT/scripts/axml_repackage.py" "$WORK/AndroidManifest.xml" "$OLD" "$NEW"

rm -rf "$WORK/dex"; mkdir -p "$WORK/dex"
cp "$ROOT"/dex/*.dex "$WORK/dex/"
python3 "$ROOT/scripts/dex_rename_pkg.py" "$WORK/dex" "$OLD" "$NEW"

smali a -o "$WORK/shim.dex" "$ROOT/shim"

python3 "$ROOT/scripts/repack.py" "$ORIG" "$WORK/AndroidManifest.xml" "$WORK/dex" "$WORK/shim.dex" "$WORK/nopush_dex.apk"
zipalign -f -p 4 "$WORK/nopush_dex.apk" "$WORK/nopush_aligned.apk"
apksigner sign --ks "$ROOT/keys/deshell.keystore" --ks-pass pass:android --key-pass pass:android \
  --out "$ROOT/deshell/nopush-release.apk" "$WORK/nopush_aligned.apk"
apksigner verify "$ROOT/deshell/nopush-release.apk"
echo "OK: $ROOT/deshell/nopush-release.apk"
