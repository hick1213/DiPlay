#!/bin/sh
# Android 4.2 diagnostic v11: read-only root forensics and original blue-car icon.
# Same title.navi package and debug signing key as v5-v10.
set -eu
src_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
root_dir=$(CDPATH= cd -- "$src_dir/../.." && pwd)
output_dir=${1:-/Users/hick/github/DiPlay/car-market-kit/APKs}
sdk_dir=${ANDROID_HOME:-"$HOME/Library/Android/sdk"}
tools_dir="$sdk_dir/build-tools/34.0.0"
android_jar="$sdk_dir/platforms/android-21/android.jar"
keystore="$HOME/.android/debug.keystore"
icon="$root_dir/common/src/main/res/drawable/ic_carplay.png"
output_apk="$output_dir/car-probe-whitelist-title-navi-v11.apk"

if [ ! -f "$keystore" ]; then
    echo "Missing debug keystore" >&2
    exit 1
fi
if [ ! -f "$icon" ]; then
    echo "Missing original DiPlay car icon: $icon" >&2
    exit 1
fi
if [ -e "$output_apk" ]; then
    echo "APK exists, will not overwrite: $output_apk" >&2
    exit 1
fi
mkdir -p "$output_dir"
build_dir=$(mktemp -d "$output_dir/.whitelist-probe-v11-build.XXXXXX")
trap 'test -z "$build_dir" || rm -r "$build_dir"' EXIT
mkdir -p "$build_dir/classes" "$build_dir/dex" "$build_dir/res"
javac -source 7 -target 7 -cp "$android_jar" -d "$build_dir/classes" \
    "$src_dir/src/com/hick/carprobe/ProbeActivity.java" \
    "$src_dir/src/com/hick/carprobe/WhitelistProbeActivity.java" \
    "$src_dir/src/com/hick/carprobe/StoreCacheInstallerActivity.java" \
    "$src_dir/src/com/hick/carprobe/RootResearchActivity.java" \
    "$src_dir/src/com/hick/carprobe/AppDumpActivity.java" \
    "$src_dir/src/com/hick/carprobe/InstallEvidence.java" \
    "$src_dir/src/com/hick/carprobe/RootEvidence.java" \
    "$src_dir/src/com/hick/carprobe/CloudReporter.java" \
    "$src_dir/src/com/hick/carprobe/AdvancedDiagnostics.java" \
    "$src_dir/src/com/hick/carprobe/DiagnosticLog.java" \
    "$src_dir/src/com/hick/carprobe/ProbeMonitorService.java"
"$tools_dir/d8" --min-api 17 --lib "$android_jar" --output "$build_dir/dex" \
    "$build_dir"/classes/com/hick/carprobe/*.class
"$tools_dir/aapt2" compile -o "$build_dir/res" "$icon"
"$tools_dir/aapt2" link -o "$build_dir/manifest.apk" \
    --manifest "$src_dir/AndroidManifestWhitelistV11.xml" -I "$android_jar" \
    --min-sdk-version 17 --target-sdk-version 17 \
    -R "$build_dir/res/drawable_ic_carplay.png.flat"
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
