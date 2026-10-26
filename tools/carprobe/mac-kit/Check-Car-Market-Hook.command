#!/bin/bash
set -euo pipefail
cd /Users/hick/github/DiPlay
python3 -m scripts.car_market_hook check --mfi-test-apk "/Users/hick/Library/Application Support/DiPlay/mfi-test-20261026/diplay-legacy-android42-toolbar-voice-v12-20261026.apk"
read -r -p "按回车关闭窗口..." _
