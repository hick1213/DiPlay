#!/bin/sh
# Android 4.2 goldfish/ranchu emulator may expose no AudioPolicy output device.
# For video-output regression ONLY, strip the AAC track WITHOUT re-encoding H264.
# Do NOT use this as a production car-head-unit playback conversion.
set -eu
if [ "$#" -ne 2 ]; then
    echo "Usage: $0 /path/to/original-h264-aac.mp4 /path/to/api17-video-only.mp4" >&2
    exit 2
fi
src=$1
dst=$2
[ -s "$src" ] && [ ! -e "$dst" ] || { echo "Source missing or output already exists" >&2; exit 1; }
ffmpeg -hide_banner -loglevel error -i "$src" -map 0:v:0 -c:v copy -an -movflags +faststart "$dst"
video_sha() {
    ffmpeg -hide_banner -loglevel error -i "$1" -map 0:v:0 \
        -c:v copy -bsf:v h264_mp4toannexb -f h264 - | shasum -a 256 | cut -d ' ' -f1
}
before=$(video_sha "$src")
after=$(video_sha "$dst")
if [ "$before" != "$after" ]; then
    rm -f "$dst"
    echo "ERROR: H264 bitstream changed during remux" >&2
    exit 1
fi
echo "Simulator-only no-audio H264 media: $dst"
echo "Video bitstream SHA-256 preserved: $after"
