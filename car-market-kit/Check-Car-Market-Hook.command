#!/bin/bash
set -euo pipefail
cd /Users/hick/github/DiPlay
python3 -m scripts.car_market_hook check --mfi-test-apk "$PWD/car-market-kit/APKs/diplay-android42-toolbar-voice-v12.apk"
read -r -p "按回车关闭窗口..." _
