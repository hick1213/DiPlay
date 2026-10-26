#!/bin/sh
# Native Java API17 build: no Gradle / AndroidX, no replacement of OEM packages.
set -eu

source_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
output_dir=${1:-/Volumes/untitled/Android42-apps}
sdk_dir=${ANDROID_HOME:-"$HOME/Library/Android/sdk"}
build_tools="$sdk_dir/build-tools/35.0.0"
android_jar="$sdk_dir/platforms/android-21/android.jar"
keystore="$HOME/.android/debug.keystore"
output_apk="$output_dir/lan-cast-android42-v1.apk"

if [ -e "$output_apk" ]; then
    echo "Preserving existing APK, refusing to overwrite: $output_apk" >&2
    exit 1
fi
if [ ! -f "$keystore" ]; then
    echo "Missing standard Android debug keystore: $keystore" >&2
    exit 1
fi

mkdir -p "$output_dir"
build_dir=$(mktemp -d "$output_dir/.lancast-build.XXXXXX")
mkdir -p "$build_dir/classes" "$build_dir/dex"

javac -source 7 -target 7 -cp "$android_jar" -d "$build_dir/classes" \
    "$source_dir/src/com/hick/lancast/LanCastActivity.java" \
    "$source_dir/src/com/hick/lancast/CastPlayerActivity.java" \
    "$source_dir/src/com/hick/lancast/CastService.java"

"$build_tools/d8" --min-api 17 --lib "$android_jar" --output "$build_dir/dex" \
    "$build_dir"/classes/com/hick/lancast/*.class
"$build_tools/aapt2" link -o "$build_dir/manifest.apk" \
    --manifest "$source_dir/AndroidManifest.xml" -I "$android_jar" \
    --min-sdk-version 17 --target-sdk-version 17
(cd "$build_dir/dex" && zip -q "$build_dir/manifest.apk" classes.dex)
"$build_tools/zipalign" -f 4 "$build_dir/manifest.apk" "$build_dir/aligned.apk"
"$build_tools/apksigner" sign --ks "$keystore" \
    --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android \
    --v1-signing-enabled true --v2-signing-enabled false --v3-signing-enabled false \
    --out "$output_apk" "$build_dir/aligned.apk"
"$build_tools/apksigner" verify --min-sdk-version 17 "$output_apk"
"$build_tools/aapt2" dump badging "$output_apk" | head -n 8
echo "Built: $output_apk"
