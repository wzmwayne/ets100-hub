#!/bin/bash
# 每个插件编译成一个独立 dex：deshell/etshub-plugin-<Class>.dex
set -e
DIR="$(cd "$(dirname "$0")/.." && pwd)"
AJ=/tmp/android-sdk/platforms/android-34/android.jar
D8=/tmp/android-sdk/build-tools/35.0.0/d8
SRC="$DIR/shim_java/plugin_sample/src"
CLS="$DIR/shim_java/classes"
rm -rf /tmp/plug_cls
mkdir -p /tmp/plug_cls
javac -encoding UTF-8 --release 8 -nowarn -cp "$CLS:$AJ" -d /tmp/plug_cls $(find "$SRC" -name "*.java")
for src in $(find "$SRC" -name "*.java"); do
  name=$(basename "$src" .java)
  rm -rf /tmp/plug_out
  mkdir -p /tmp/plug_out
  files=$(find /tmp/plug_cls -name "$name.class" -o -name "$name\$*.class" | sort)
  out="$DIR/deshell/etshub-plugin-$name.dex"
  "$D8" --min-api 21 --lib "$AJ" --classpath "$CLS" --output /tmp/plug_out $files
  cp -f /tmp/plug_out/classes.dex "$out"
  echo "plugin: $out ($(stat -c%s "$out") bytes)"
done
