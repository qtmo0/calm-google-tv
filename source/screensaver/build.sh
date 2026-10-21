#!/bin/sh
# Builds out/oleddream.apk without Gradle. Needs openjdk@17 and Android build-tools 34.
set -e
cd "$(dirname "$0")"

export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}
export PATH="$JAVA_HOME/bin:$PATH"
SDK=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
BT=$SDK/build-tools/34.0.0
JAR=$SDK/platforms/android-34/android.jar

rm -rf out && mkdir -p out/classes out/dex
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -o out/unsigned.apk
javac -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$JAR" -classpath "$BT/core-lambda-stubs.jar" \
  -d out/classes $(find src -name '*.java')
"$BT/d8" --lib "$JAR" --min-api 26 --output out/dex $(find out/classes -name '*.class')
(cd out/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 out/unsigned.apk out/aligned.apk

# Keep key.jks: updates must be signed with the same key.
[ -f key.jks ] || keytool -genkeypair -keystore key.jks -storepass oleddream -keypass oleddream \
  -alias oleddream -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=OLED Dream" >/dev/null 2>&1
"$BT/apksigner" sign --ks key.jks --ks-pass pass:oleddream --out out/oleddream.apk out/aligned.apk
echo "Built out/oleddream.apk"
