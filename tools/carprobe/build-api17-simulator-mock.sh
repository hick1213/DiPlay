#!/bin/sh
# Separate, simulator-only DiPlay APK. Never packages MFi certificates or
# overwrites car-market-kit/APKs or the user's installed sogou.map build.
set -eu

project=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
lab="${1:-/Volumes/DiPlay42API17Lab}"
if [ ! -d "$lab" ]; then
    echo "API17 external APFS test volume not mounted: $lab" >&2
    exit 1
fi
if [ -n "${DIPLAY_AUTH_ASSETS_DIR:-}" ]; then
    echo "Unset DIPLAY_AUTH_ASSETS_DIR. Simulator mocks must never package MFi identity." >&2
    exit 1
fi
if ! command -v ffmpeg >/dev/null 2>&1; then
    echo "ffmpeg is required to generate a deterministic mock H264 fixture." >&2
    exit 1
fi

assets="$lab/diplay-api17-mock-assets"
output_dir="$lab/mock-apks"
mkdir -p "$assets/emulator-mock" "$output_dir"
fixture="$assets/emulator-mock/sample.h264"
if [ ! -s "$fixture" ]; then
    temp="$fixture.new"
    trap 'rm -f -- "$temp"' EXIT HUP INT TERM
    ffmpeg -hide_banner -loglevel error -y \
        -f lavfi -i 'testsrc2=size=320x180:rate=8' -t 8 \
        -c:v libx264 -preset ultrafast -profile:v baseline -crf 34 \
        -pix_fmt yuv420p \
        -x264-params 'aud=1:keyint=8:min-keyint=8:scenecut=0:repeat-headers=1:bframes=0' \
        -an -f h264 "$temp"
    mv -f "$temp" "$fixture"
fi

if [ -z "${JAVA_HOME:-}" ]; then
    java25="$HOME/.gradle/jdks/eclipse_adoptium-25-aarch64-os_x.2/jdk-25.0.3+9/Contents/Home"
    [ ! -x "$java25/bin/java" ] || export JAVA_HOME="$java25"
fi

(cd "$project" &&
  ./gradlew :legacy:assembleDebug \
    -PdiplayEmulatorMockConnect=true \
    -PdiplayMockClipDir="$assets" \
    --offline --no-configuration-cache --console=plain)

built="$project/legacy/build/outputs/apk/debug/legacy-debug.apk"
bt="${ANDROID_HOME:-$HOME/Library/Android/sdk}/build-tools/36.0.0"
if [ ! -s "$built" ]; then echo "Mock APK not produced" >&2; exit 1; fi
"$bt/apksigner" verify --min-sdk-version 17 "$built"
"$bt/aapt2" dump badging "$built" | grep -q "package: name='com.hick.diplay.simulator.mock'"
"$bt/aapt2" dump xmltree --file AndroidManifest.xml "$built" |
    grep -q 'minSdkVersion(0x0101020c)=17'
if unzip -Z1 "$built" | grep -q 'offline-mfi/'; then
    echo "Refusing mock APK: an authentication asset was unexpectedly packaged" >&2
    exit 1
fi

target="$output_dir/DiPlay-API17-MOCK-connect.apk"
cp "$built" "$target"
cmp "$built" "$target"
chmod 600 "$target"
echo "Verified simulator-only APK: $target"
echo "Application ID: com.hick.diplay.simulator.mock"
echo "No MFi, Bluetooth, Wi-Fi Direct or iPhone will be accessed by mock Connect."
