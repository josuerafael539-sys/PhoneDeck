#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

SDK=/usr/lib/android-sdk
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-35/android.jar"
OUT=build

rm -rf "$OUT"
mkdir -p "$OUT/compiled" "$OUT/gen" "$OUT/classes" "$OUT/dex"

"$BT/aapt2" compile --dir res -o "$OUT/compiled/resources.zip"
"$BT/aapt2" link \
  -o "$OUT/PhoneLink-unsigned.apk" \
  -I "$PLATFORM" \
  --manifest AndroidManifest.xml \
  -R "$OUT/compiled/resources.zip" \
  --java "$OUT/gen" \
  --auto-add-overlay \
  --min-sdk-version 26 \
  --target-sdk-version 35 \
  --version-code 1 \
  --version-name 1.0

find src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac --release 8 -Xlint:-options -classpath "$PLATFORM" -d "$OUT/classes" @"$OUT/sources.txt"
python3 tools/strip_method_parameters.py "$OUT/classes"

find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --lib "$PLATFORM" --min-api 26 --output "$OUT/dex" @"$OUT/classes.txt"

cp "$OUT/PhoneLink-unsigned.apk" "$OUT/PhoneLink-merged.apk"
(cd "$OUT" && zip -q -j PhoneLink-merged.apk dex/classes.dex)

if [ ! -f debug.keystore ]; then
  keytool -genkeypair \
    -keystore debug.keystore \
    -alias phonelink \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass phonelink -keypass phonelink \
    -dname "CN=PhoneLink Debug,O=PhoneDeck,C=DO" 2>/dev/null
fi

"$BT/zipalign" -f 4 "$OUT/PhoneLink-merged.apk" "$OUT/PhoneLink-aligned.apk"
"$BT/apksigner" sign \
  --ks debug.keystore --ks-key-alias phonelink \
  --ks-pass pass:phonelink --key-pass pass:phonelink \
  --out PhoneLink.apk "$OUT/PhoneLink-aligned.apk"

"$BT/apksigner" verify --print-certs PhoneLink.apk | head -3
echo "APK listo: $(pwd)/PhoneLink.apk"
