#!/bin/bash
set -euo pipefail
cd /Users/hick/github/DiPlay
python3 -m scripts.car_market_hook check
read -r -p "按回车关闭窗口..." _
