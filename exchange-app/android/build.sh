#!/usr/bin/env bash
# Builds a signed WZExchange APK without Gradle or the Google SDK manager, using
# the Android build tools packaged by Ubuntu/Debian:
#   sudo apt-get install aapt apksigner zipalign dalvik-exchange android-sdk-platform-23
#
# Output: build/WZExchange.apk
#
# Signing: uses $KEYSTORE (default: release.keystore next to this script, created
# on first run). Keep that file and its password — Android only installs an
# update over the existing app if it is signed with the same key.
set -euo pipefail
cd "$(dirname "$0")"

SDK=${ANDROID_SDK:-/usr/lib/android-sdk}
TOOLS=${BUILD_TOOLS:-$SDK/build-tools/debian}
PLATFORM=${ANDROID_JAR:-$SDK/platforms/android-23/android.jar}
KEYSTORE=${KEYSTORE:-release.keystore}
KEY_ALIAS=${KEY_ALIAS:-wzexchange}
KEY_PASS=${KEY_PASS:-wzexchange}

OUT=build
rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes"

echo "== resources"
"$TOOLS/aapt2" compile --dir res -o "$OUT/res/"
"$TOOLS/aapt2" link -o "$OUT/unsigned.apk" -I "$PLATFORM" \
  --manifest AndroidManifest.xml -A assets --java "$OUT/gen" \
  "$OUT"/res/*.flat

echo "== java"
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$PLATFORM" -d "$OUT/classes" \
  $(find src "$OUT/gen" -name '*.java')

echo "== dex"
"$TOOLS/dx" --dex --min-sdk-version=23 --output="$OUT/classes.dex" "$OUT/classes"
(cd "$OUT" && zip -q -j unsigned.apk classes.dex)

echo "== sign"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -noprompt -keystore "$KEYSTORE" -alias "$KEY_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KEY_PASS" -keypass "$KEY_PASS" \
    -dname "CN=WZExchange, O=WZExchange"
  echo "Created new signing key: $KEYSTORE"
fi
"$TOOLS/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$KEY_PASS" --key-pass "pass:$KEY_PASS" \
  --out "$OUT/WZExchange.apk" "$OUT/aligned.apk"
"$TOOLS/apksigner" verify "$OUT/WZExchange.apk"

echo "Built $OUT/WZExchange.apk"
