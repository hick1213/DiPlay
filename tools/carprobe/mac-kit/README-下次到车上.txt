车机应用市场 Hook / Android 4.2 Mac 随车包（2026-10-26）

本地位置：/Users/hick/github/DiPlay/car-market-kit/
所有 APK、启动命令和日志都在 Mac 内置磁盘。无需带移动硬盘。
仅共享已授权拥有的车机测试网络，不会自动更改车机系统。

1. Mac 与车机连接同一个可信热点；双击 Start-Car-Market-Hook.command。
   启动后会自动在 Mac 浏览器打开“车机 Android 4.2 安装 Demo”页面。
   车机浏览器访问同一个地址：http://<Mac 当前局域网 IP>:18816/
   页面提供“下载车机诊断 v11（蓝车图标 / Root / 安装限制）”按钮。
   页面还提供当前 DiPlay v12 和“车机局域网投屏 LAN Cast”。
   当前 DiPlay 与局域网投屏下载地址分别是：
   http://<Mac 当前局域网 IP>:18816/diplay-android42-toolbar-voice-v12.apk
   http://<Mac 当前局域网 IP>:18816/lan-cast-android42.apk
   无身份 DiPlay 基线 APK 不再显示或开放下载，原始文件仅在 Mac 本地备份。
   DiPlay v12 使用 sogou.map 包名；按一次 Back 显示控制栏，闲置 3 秒自动隐藏；期间点按顶部重新计时，快速再次 Back 仍可退出。
   断开后需等待 Wi-Fi Direct 清理结束，再点击连接；避免新群组被旧连接清理线程移除。
   v9 修复双向音频；v10 优化触摸 MOVE 队列、视频时间戳与解码器缓冲；v11 可选 30/35/40/45/50/55/60 FPS 并恢复后台画面；v12 控制栏 3 秒空闲隐藏并记录 CarPlay 语音状态以排查高德按钮。
   顶部状态可查看 CarPlay 音频资源、iPhone 下行 RTP 和 Android 的 AudioTrack PCM/AAC/Opus 播放状态。
   MFi 身份认证和真实 CarPlay 连接仍须真车实测，不能据此声称已修复。

2. 首次安装或升级诊断 APK
   APKs/car-probe-whitelist-title-navi-v11.apk
   应用包名为 title.navi（与 v5-v10 相同签名，API 17），进入“读取车辆信息”
   页面后最上方有“系统设置 / Wi-Fi 代理”和“直接打开 Wi-Fi”按钮。
   主页面（无需下拉）并排有“Root / Wi-Fi ADB”和“安装限制 Dump”：
   ① 先读权限、su、ADB 5555 是否监听；② 如有 su，手动点授权检查；
   ③ 仅在停车、自己热点、已获 Root 的情况下确认启动 Wi-Fi ADB；
   ④ 测完立即点击关闭。还会只读列出疑似原厂工程/维护入口。
   v10 的 Root 页新增“①b Root 深度取证”，只读收集 ro.secure/ro.debuggable、
   su/adbd 文件线索、进程 UID/CapEff、/proc/mounts 的系统分区挂载属性、
   SELinux 状态；绝不会在只读检查时尝试 su 或开启无线 ADB。
   仅当手动点击“② 测试 su 授权”后，结果才会记录并写入后续 Dump ZIP。
   没有 Root 时不会运行提权漏洞，不会强行开 ADB。
   关键 App Dump：勾选原厂商店、安装器、系统设置、System UI 和桌面，
   手动点击“导出 ZIP”，会包含可读取的 APK、同目录 ODEX、权限、签名摘要、
   公开组件清单。v9/v10 额外记录真实安装 Intent 的匹配 Activity、安装来源、
   INSTALL_PACKAGES 权限、未知来源/ADB 开关，尝试复制可读的
   /system/framework/{services,framework,android.policy} 的 JAR/ODEX、
   framework-res.apk、platform.xml 和相关厂商安装配置 XML。
   ZIP 的 install-evidence/installer-routes.txt 是组件/权限报告；
   install-evidence/system-files-manifest.txt 是系统文件复制/跳过清单。
   v10 额外加入 install-evidence/root-feasibility.txt（Root 深度只读取证）。
   普通权限看不到的内容会记录 SKIPPED，不会执行提权漏洞，
   不会抓取 /data/data 中其他 App 的私有资料。
   要传回 Mac：本机 Hook 保持运行，车机 Dump 页面填写启动页显示的 Mac
   局域网 IP，点“将 ZIP 发送到我的 Mac”再确认；无需配对码/验证码。
   Mac 收件位置是 car-market-kit/dumps/。每次最多 60 MiB，限可信私有网络，
   收件服务随 Hook 退出而关闭。
   已设置自定义 ROM 备用 Intent；若原厂系统全面拦截，日志会记录并提示。
   如原厂弹出替换不认识的原车程序，取消安装。
   车机若能直接在浏览器下载，也可访问：
   http://<Mac 当前局域网 IP>:18816/car-probe-settings.apk
   这条路径由下述 Hook 启动脚本提供，无需携带移动硬盘。

   启动器会检测当前 LAN IP，启动：市场 TCP 18611、APK HTTP/Dump 接收 18816、
   HTTP 代理 18081。Ctrl+C 停止。运行日志在 logs/market-hook.log。
   双击 Check-Car-Market-Hook.command 可核对 3 项网络接口和 APK HEAD。

3. 车机上进入设置 → Wi-Fi → 长按当前网络 → 修改网络 → 显示高级选项。
   如系统 HTTP 代理对该市场有效，主机填 Mac LAN IP，端口 18081。
   只有使用系统 HTTP 代理的客户端才会被该方案接管；不代表原厂应用
   一定采用代理。

4. 若原厂商店不遵循系统 HTTP 代理，则需在自己可控的热点/路由器上
   把 www.xygala.com 的 DNS 指到 Mac LAN IP，然后双击
   Start-Car-Market-DNS80.command，并允许 Mac 监听端口 80。
   只在可信局域网启用。Mac 自己的 /etc/hosts 更改对车机无效。

   2026-10-25 Mac 实测：原厂 http://www.xygala.com/host.php 仍返回
   {"pay":"http://pay.xygala.com/index.php","app_market":"47.98.117.248:8601"}。
   但从 Mac 连接 47.98.117.248:8601 超时（5 秒）；这不能证明
   车机侧也无法连接，亦不能证明 TCP 市场服务已永久下线。
   下次进车先尝试原厂商店，若仍加载失败再开启本地 Hook。

5. 启动车机原厂应用商店，让它按原本流程请求 host.php、TCP 目录、
   从 Mac 下载 APK 并调用系统安装器。真实的原厂系统安装需下次到车上
   才能实测；诊断包不执行自动安装、不绕开 Android 系统签名验证。

Mac 上仅携带约 52 MB 的 APK、脚本入口和日志；Dump ZIP 以后会另存到 dumps/。
移动硬盘中 7.5 GB 的 SDK/模拟器仍为离线构建、仿真开发用途，没有复制。
启动脚本仍调用 Mac 内已有 /Users/hick/github/DiPlay 源码。
