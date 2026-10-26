#!/bin/bash
# All APKs and log files are local to this Mac; no external disk is required.
set -euo pipefail

PROJECT_DIR="/Users/hick/github/DiPlay"
KIT_DIR="$PROJECT_DIR/car-market-kit"
APK="$KIT_DIR/APKs/tvbox-android42-full-native-v5.apk"
MFI_APK="$KIT_DIR/APKs/diplay-android42-toolbar-voice-v13.apk"

if [[ ! -f "$PROJECT_DIR/scripts/car_market_hook.py" || ! -f "$APK" || ! -f "$MFI_APK" ]]; then
  echo "缺少 DiPlay 项目、市场 APK 或当前 v12 APK，请检查：$PROJECT_DIR 与 $KIT_DIR/APKs，以及 $MFI_APK"
  read -r -p "按回车退出..." _
  exit 1
fi
cd "$PROJECT_DIR"

if python3 -m scripts.car_market_hook check --bind 0.0.0.0 --mfi-test-apk "$MFI_APK" --open-demo; then
  echo "市场 Hook 已经运行，无需重复启动。"
  read -r -p "按回车关闭此窗口..." _
else
  echo "启动车机商店 Hook（Mac 本地 APK 和日志），Ctrl+C 停止。"
  python3 -u -m scripts.car_market_hook run --bind 0.0.0.0 --trusted-lan --open-demo \
    --mfi-test-apk "$MFI_APK" --apk "$APK" --log-dir "$KIT_DIR/logs"
fi
