#!/usr/bin/env bash
# UI 版构建脚本（非 Gradle 流水线）
# 环境变量: ANDROID_BUILD_TOOLS, ANDROID_PLATFORM_JAR, JAVA_HOME, KS_FILE, KS_PASS
set -euo pipefail

BT="${ANDROID_BUILD_TOOLS:-$HOME/Android/Sdk/build-tools/34.0.0}"
AJ="${ANDROID_PLATFORM_JAR:-$HOME/Android/Sdk/platforms/android-34/android.jar}"
JH="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk}"
KS="${KS_FILE:-./ccode.keystore}"
KS_PASS="${KS_PASS:?请设置 KS_PASS 环境变量为签名密钥密码}"
OUT="${1:-C语言草稿纸.apk}"
cd "$(dirname "$0")"

rm -rf gen classes dex res.zip out.apk aligned.apk "$OUT" "$OUT.idsig" 2>/dev/null || true
mkdir -p gen classes dex

echo "[1/4] 编译资源"
"$BT/aapt2" compile --dir res -o res.zip

echo "[2/4] 链接+生成 R"
ASSETS=""
[ -d assets ] && ASSETS="-A assets"
"$BT/aapt2" link -o out.apk -I "$AJ" \
  --manifest src/AndroidManifest.xml \
  --java gen \
  --min-sdk-version 26 --target-sdk-version 34 \
  $ASSETS \
  res.zip

echo "[3/4] javac + d8"
"$JH/bin/javac" --release 8 -classpath "$AJ" -encoding UTF-8 \
  -d classes gen/com/ccode/mintest/R.java src/com/ccode/mintest/*.java
"$BT/d8" --release --lib "$AJ" --output dex $(find classes -name '*.class')
( cd dex && zip -q -j ../out.apk classes.dex )

echo "[4/4] 对齐+签名+验证"
"$BT/zipalign" -f 4 out.apk aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --out "$OUT" aligned.apk
"$BT/apksigner" verify --print-certs "$OUT" | head -2
"$BT/aapt2" dump badging "$OUT" | grep -E "package:|sdkVersion|targetSdk|launchable|application-label"
rm -f "$OUT.idsig"
ls -lh "$OUT"
