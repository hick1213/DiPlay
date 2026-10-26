#!/bin/sh
# Package the Oct 23 Android 4.2 :legacy build for a known installable car ID.
# Preserve the original decoded manifest and every DEX; never use the probe APK.
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
original_apk="$project_dir/legacy/build/outputs/apk/debug/legacy-debug.apk"
output_dir=${1:?"Historical repack requires an explicit scratch output directory; use the current Gradle build for v12"}
output_apk="$output_dir/diplay-legacy-android42-sogou-map-formal-20261025.apk"
android_sdk=${ANDROID_HOME:-"$HOME/Library/Android/sdk"}
build_tools="$android_sdk/build-tools/34.0.0"
debug_keystore="$HOME/.android/debug.keystore"

if [ ! -f "$original_apk" ] || [ ! -f "$debug_keystore" ]; then
    echo "Missing the original :legacy APK or the signing key" >&2
    exit 1
fi
if [ -e "$output_apk" ]; then
    echo "Refusing to overwrite: $output_apk" >&2
    exit 1
fi

mkdir -p "$output_dir"
# Apktool's intermediate files must stay on APFS, not an ExFAT disk.
work_dir=$(mktemp -d /private/tmp/diplay-legacy-formal.XXXXXX)
cleanup() {
    case "$work_dir" in
        /private/tmp/diplay-legacy-formal.*) rm -rf -- "$work_dir" ;;
    esac
}
trap cleanup EXIT HUP INT TERM

apktool d -s -o "$work_dir/app" "$original_apk"
manifest="$work_dir/app/AndroidManifest.xml"
if ! grep -q 'package="com.shihab.diplay.legacy"' "$manifest"; then
    echo "Unexpected original package ID; refusing to modify" >&2
    exit 1
fi
# Only change the manifest package. Keep all original permissions, queries,
# components, API levels and version metadata; no tests or probes are merged in.
perl -pi -e 's/package="com\.shihab\.diplay\.legacy"/package="sogou.map"/' "$manifest"
# The bundled Apktool linker does not recognize this Android 12+ attribute;
# it has no meaning on the target Android 4.2 device.
perl -pi -e 's/ android:usesPermissionFlags="0x00010000"//' "$manifest"
grep -q 'package="sogou.map"' "$manifest"

apktool b "$work_dir/app" -o "$work_dir/unsigned.apk"
"$build_tools/zipalign" -f 4 "$work_dir/unsigned.apk" "$work_dir/aligned.apk"
"$build_tools/apksigner" sign \
    --ks "$debug_keystore" --ks-key-alias androiddebugkey \
    --ks-pass pass:android --key-pass pass:android \
    --v1-signing-enabled true --v2-signing-enabled false --v3-signing-enabled false \
    --out "$output_apk" "$work_dir/aligned.apk"

"$build_tools/apksigner" verify --verbose --min-sdk-version 17 "$output_apk"
"$build_tools/aapt2" dump badging "$output_apk" | head -n 5
for dex in classes.dex classes2.dex classes3.dex; do
    expected=$(unzip -p "$original_apk" "$dex" | shasum -a 256 | awk '{print $1}')
    actual=$(unzip -p "$output_apk" "$dex" | shasum -a 256 | awk '{print $1}')
    if [ "$expected" != "$actual" ]; then
        echo "DEX mismatch after repack: $dex" >&2
        exit 1
    fi
    echo "$dex unchanged: $actual"
done
echo "Packaged original Android 4.2 port: $output_apk"
