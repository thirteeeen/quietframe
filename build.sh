#!/bin/sh
# Builds out/quietframe.apk with the plain Android SDK tools (no Gradle).
# Needs Java 17+ and the Android SDK with platforms;android-34 and build-tools;34.0.0:
#   macOS:  brew install openjdk@17 && brew install --cask android-commandlinetools
#           sdkmanager "platforms;android-34" "build-tools;34.0.0"
# Or let GitHub build it: push this repo and download the APK from the Actions run.
set -e
cd "$(dirname "$0")"

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
[ -n "$SDK" ] || for d in /opt/homebrew/share/android-commandlinetools "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
  [ -d "$d" ] && SDK=$d && break
done
BT=$SDK/build-tools/34.0.0
JAR=$SDK/platforms/android-34/android.jar
[ -f "$JAR" ] && [ -d "$BT" ] || {
  echo "Android SDK not found. Set ANDROID_HOME and install platforms;android-34 and build-tools;34.0.0." >&2
  exit 1
}

rm -rf out
mkdir -p out/classes out/dex

"$BT/aapt2" compile --dir res -o out/res.zip
"$BT/aapt2" link -o out/base.apk -I "$JAR" --manifest AndroidManifest.xml \
  --min-sdk-version 24 --target-sdk-version 34 out/res.zip
# shellcheck disable=SC2046 # one argument per source file on purpose
javac -nowarn -Xlint:-options -encoding UTF-8 -source 1.8 -target 1.8 \
  -bootclasspath "$JAR:$BT/core-lambda-stubs.jar" -d out/classes $(find src -name '*.java')
# shellcheck disable=SC2046
"$BT/d8" --min-api 24 --release --lib "$JAR" --output out/dex $(find out/classes -name '*.class')
cp out/base.apk out/unsigned.apk
(cd out/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 out/unsigned.apk out/aligned.apk

# A signing key is made on the first build. Keep key.jks: updates must be signed with the same key,
# or Android refuses them and you'd have to uninstall first (losing your settings).
KS=${KEYSTORE:-key.jks}
PASS=${KEYSTORE_PASS:-quietframe}
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass "$PASS" -keypass "$PASS" -alias quietframe \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=QuietFrame" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$PASS" --out out/quietframe.apk out/aligned.apk
echo "Built out/quietframe.apk"
