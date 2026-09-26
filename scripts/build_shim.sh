#!/bin/bash
# 编译防闪退 shim（javac + d8）
set -e
DIR="$(cd "$(dirname "$0")/.." && pwd)/shim_java"
AJ=/tmp/android-sdk/platforms/android-34/android.jar
D8=/tmp/android-sdk/build-tools/35.0.0/d8
cd "$DIR"
rm -rf classes d8out shim.dex
find src stub -name "*.java" > sources.txt
javac -encoding UTF-8 -source 8 -target 8 -nowarn -cp "$AJ" -d classes @sources.txt
find classes -name "*.class" | grep -v "com/ets100" > classlist.txt
mkdir -p d8out
"$D8" --min-api 21 --lib "$AJ" --classpath classes --output d8out $(cat classlist.txt)
cp -f d8out/classes.dex shim.dex
echo "shim.dex built: $(ls -la shim.dex | awk '{print $5}') bytes"
