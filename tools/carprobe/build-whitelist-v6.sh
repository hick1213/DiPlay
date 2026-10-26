#!/bin/sh
# Independent v6 Android 4.2 build. Keep v5 and its signature/identity intact.
set -eu
src_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
output_dir=${1:-/Users/hick/github/DiPlay/car-market-kit/APKs}
sdk_dir=${ANDROID_HOME:-"$HOME/Library/Android/sdk"}
tools_dir="$sdk_dir/build-tools/34.0.0"
android_jar="$sdk_dir/platforms/android-21/android.jar"
keystore="$HOME/.android/debug.keystore"
output_apk="$output_dir/car-probe-whitelist-title-navi-v6.apk"

if [ ! -f "$keystore" ]; then
    echo "Missing debug keystore" >&2
    exit 1
fi
if [ -e "$output_apk" ]; then
    echo "APK exists, will not overwrite: $output_apk" >&2
    exit 1
fi
mkdir -p "$output_dir"
build_dir=$(mktemp -d "$output_dir/.whitelist-probe-v6-build.XXXXXX")
mkdir -p "$build_dir/classes" "$build_dir/dex"
javac -source 7 -target 7 -cp "$android_jar" -d "$build_dir/classes" \
    "$src_dir/src/com/hick/carprobe/ProbeActivity.java" \
    "$src_dir/src/com/hick/carprobe/WhitelistProbeActivity.java" \
    "$src_dir/src/com/hick/carprobe/StoreCacheInstallerActivity.java" \
    "$src_dir/src/com/hick/carprobe/CloudReporter.java" \
    "$src_dir/src/com/hick/carprobe/AdvancedDiagnostics.java" \
    "$src_dir/src/com/hick/carprobe/DiagnosticLog.java" \
    "$src_dir/src/com/hick/carprobe/ProbeMonitorService.java"
"$tools_dir/d8" --min-api 17 --lib "$android_jar" --output "$build_dir/dex" \
    "$build_dir"/classes/com/hick/carprobe/*.class
"$tools_dir/aapt2" link -o "$build_dir/manifest.apk" \
    --manifest "$src_dir/AndroidManifestWhitelistV6.xml" -I "$android_jar" \
    --min-sdk-version 17 --target-sdk-version 17
(cd "$build_dir/dex" && zip -q "$build_dir/manifest.apk" classes.dex)
"$tools_dir/zipalign" -f 4 "$build_dir/manifest.apk" "$build_dir/aligned.apk"
"$tools_dir/apksigner" sign \
    --ks "$keystore" --ks-key-alias androiddebugkey \
    --ks-pass pass:android --key-pass pass:android \
    --v1-signing-enabled true --v2-signing-enabled false --v3-signing-enabled false \
    --out "$output_apk" "$build_dir/aligned.apk"
"$tools_dir/apksigner" verify --verbose --min-sdk-version 17 "$output_apk"
"$tools_dir/aapt2" dump badging "$output_apk" | head -n 12
echo "Built: $output_apk"
