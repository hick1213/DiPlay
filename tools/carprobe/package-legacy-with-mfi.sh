#!/bin/sh
# Build the current Android 4.2 CarPlay APK from source, not a historical repack.
# Usage: package-legacy-with-mfi.sh /path/to/offline-mfi /path/to/new.apk
set -eu

if [ "$#" -ne 2 ]; then
    echo "Usage: $0 /path/to/offline-mfi /path/to/new.apk" >&2
    exit 2
fi
identity_dir=$1
output_apk=$2
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
android_sdk=${ANDROID_HOME:-"$HOME/Library/Android/sdk"}
build_tools="$android_sdk/build-tools/35.0.0"

for file in "$identity_dir/identity.pk8" "$identity_dir/certificate.p7b"; do
    if [ ! -s "$file" ]; then
        echo "Missing authorized identity asset: $file" >&2
        exit 1
    fi
done
if [ -e "$output_apk" ]; then
    echo "Refusing to overwrite existing APK: $output_apk" >&2
    exit 1
fi

# Gradle expects an enclosing folder containing only offline-mfi/identity.pk8
# and offline-mfi/certificate.p7b, and rejects unexpected files.
identity_dir=$(CDPATH= cd -- "$identity_dir" && pwd -P)
assets_dir=$(dirname -- "$identity_dir")
if [ "$(basename -- "$identity_dir")" != offline-mfi ]; then
    echo "The identity directory must be named offline-mfi" >&2
    exit 1
fi

(cd "$project_dir" && \
    DIPLAY_AUTH_ASSETS_DIR="$assets_dir" \
      ./gradlew :legacy:assembleStandaloneDebug \
      -PdiplayLegacyApplicationId=sogou.map --offline --no-configuration-cache)

built="$project_dir/legacy/build/outputs/apk/debug/legacy-debug.apk"
if [ ! -s "$built" ]; then
    echo "Gradle did not produce the expected APK: $built" >&2
    exit 1
fi
"$build_tools/apksigner" verify --min-sdk-version 17 "$built"
"$build_tools/aapt2" dump badging "$built" | grep -q "package: name='sogou.map'"
"$build_tools/aapt2" dump badging "$built" | grep -q "sdkVersion:'17'"

# Refuse to write into a non-existent destination or overwrite older packages.
output_dir=$(dirname -- "$output_apk")
[ -d "$output_dir" ] || { echo "Missing APK output directory: $output_dir" >&2; exit 1; }
umask 077
staged=$(mktemp "$output_dir/.apk-stage.XXXXXX")
trap 'rm -f -- "$staged"' EXIT HUP INT TERM
cp "$built" "$staged"
chmod 600 "$staged"
if [ -e "$output_apk" ]; then
    echo "Output APK appeared during build; refusing overwrite" >&2
    exit 1
fi
mv -n "$staged" "$output_apk"
"$build_tools/apksigner" verify --min-sdk-version 17 "$output_apk"
echo "Verified standalone CarPlay APK: $output_apk"
