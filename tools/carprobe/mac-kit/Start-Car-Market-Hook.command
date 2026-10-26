#!/bin/bash
# All APKs and log files are local to this Mac; no external disk is required.
set -euo pipefail

PROJECT_DIR="/Users/hick/github/DiPlay"
KIT_DIR="$PROJECT_DIR/car-market-kit"
APK="$KIT_DIR/APKs/tvbox-android42-full-native-v5.apk"

if [[ ! -f "$PROJECT_DIR/scripts/car_market_hook.py" || ! -f "$APK" ]]; then
  echo "缺少 DiPlay 项目或本地 APK，请确认：$PROJECT_DIR 与 $APK"
  read -r -p "按回车退出..." _
  exit 1
fi
cd "$PROJECT_DIR"

if python3 -m scripts.car_market_hook check --open-demo; then
  echo "市场 Hook 已经运行，无需重复启动。"
  read -r -p "按回车关闭此窗口..." _
else
  echo "启动车机商店 Hook（Mac 本地 APK 和日志），Ctrl+C 停止。"
  python3 -u -m scripts.car_market_hook run --trusted-lan --open-demo \
    --apk "$APK" --log-dir "$KIT_DIR/logs"
fi
