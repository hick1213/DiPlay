#!/bin/bash
# Optional router/DNS route. Only run for a private network under your control.
set -euo pipefail
cd /Users/hick/github/DiPlay
LAN_IP=$(ipconfig getifaddr en0 || true)
if [[ -z "$LAN_IP" ]]; then
  echo "请先让 Mac 连上与车机相同的 Wi-Fi/热点。"
  read -r -p "按回车退出..." _
  exit 1
fi
echo "在受控路由器上将 www.xygala.com 的 DNS 指到 $LAN_IP。"
echo "启动 80 端口仅处理 host.php 请求；需要 Mac 管理员授权。Ctrl+C 停止。"
sudo python3 -u -m scripts.car_market_host80 --bind "$LAN_IP" --market-port 18611
