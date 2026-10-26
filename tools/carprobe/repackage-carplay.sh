#!/bin/sh
# Repackage the previously built API17 legacy APK under an independently
# whitelisted identity. Does not touch source or the existing diagnostic APK.
set -eu

root_dir=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
input_apk="$root_dir/legacy/build/outputs/apk/debug/legacy-debug.apk"
manifest="$root_dir/tools/carprobe/AndroidManifestCarPlayWhitelist.xml"
output_dir=${1:?"Legacy repack requires an explicit scratch output directory; current APKs are in car-market-kit/APKs"}
output_apk="$output_dir/diplay-carplay-android42-sogou-v2.apk"
android_sdk=${ANDROID_HOME:-"$HOME/Library/Android/sdk"}
build_tools="$android_sdk/build-tools/34.0.0"
debug_keystore="$HOME/.android/debug.keystore"

if [ ! -f "$input_apk" ] || [ ! -f "$debug_keystore" ]; then
    echo "Missing legacy input APK or debug keystore" >&2
    exit 1
fi
if [ -e "$output_apk" ]; then
    echo "Refusing to overwrite an existing APK: $output_apk" >&2
    exit 1
fi

mkdir -p "$output_dir"
# Build on APFS. ExFAT creates AppleDouble ._ files which otherwise get
# incorrectly bundled inside the APK as extra resources and DEX companions.
work_dir=$(mktemp -d "${TMPDIR:-/private/tmp}/diplay-carplay-release.XXXXXX")
apktool d -s -o "$work_dir/app" "$input_apk"
cp "$manifest" "$work_dir/app/AndroidManifest.xml"
apktool b "$work_dir/app" -o "$work_dir/unsigned.apk"
"$build_tools/zipalign" -f 4 "$work_dir/unsigned.apk" "$work_dir/aligned.apk"
"$build_tools/apksigner" sign \
    --ks "$debug_keystore" --ks-key-alias androiddebugkey \
    --ks-pass pass:android --key-pass pass:android \
    --v1-signing-enabled true --v2-signing-enabled false --v3-signing-enabled false \
    --out "$output_apk" "$work_dir/aligned.apk"
"$build_tools/apksigner" verify --verbose --min-sdk-version 17 "$output_apk"
"$build_tools/aapt2" dump badging "$output_apk" | head -n 16
echo "Built: $output_apk"
