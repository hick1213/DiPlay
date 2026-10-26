# Android 4.2 车机：AirPlay 接收与 APK 安装现状

更新：2026-10-26。以下区分 **Android 16 ADB 测试手机** 与 **Android 4.2.2 真车机**。

## 1. AirPlay 与当前 LAN Cast

- 当前 `tools/lan-cast-android42` 是 DLNA/UPnP MediaRenderer，不是 AirPlay 接收器。
- iPhone AirPlay「屏幕镜像」（视频 H.264/音频）与 App 的 AirPlay 视频投放/HLS、AirPlay 音乐是不同流与控制流程；不能只加一条 mDNS 宣告就称为支持。
- 评估上游：<https://github.com/jqssun/android-airplay-server>（基于 UxPlay、JNI、MediaCodec/SurfaceView，**最低 Android 7.0**）及 <https://github.com/FDH2/UxPlay>（GPLv3、Unix/GStreamer）。未经 API 17 移植验证，不能把上游 APK 改 `minSdk` 后直接安装。
- 优先适配 **普通非 DRM 屏幕镜像 + 音频**：`_airplay._tcp` / `_raop._tcp` 发现、会话/密钥协商、H.264 交给 API17 `MediaCodec`/`Surface`，声音交给 `AudioTrack`。利用 DiPlay 已有的音视频调试经验，但不要将 CarPlay 的 iAP2/MFi 会话当成普通 AirPlay 会话。
- 后续才考虑 App 直接 AirPlay 视频和 HLS。腾讯视频/其他 DRM 内容可能有版权保护，**不得承诺**能镜像或解码。先在 Android 16 测试手机确认上游行为，再编译 32-bit ARM/API17 原生依赖，在实际车机验证。
- UxPlay 及 Android AirPlay Server 的 GPLv3 授权有公开发布源码义务；如果整合进分发的 APK，先检查许可证相容性。

## 2. 真车机诊断已具备的上传能力

诊断包：`title.navi` / v11 / API 17；在车机已安装的前提下：

1. 普通页面可「复制完整报告」或「分享报告」；**不会自动上传**。
2. 主页面「安装限制 Dump」：勾选原厂商店、安装器、系统设置及桌面，按「① 导出 App + 安装限制证据 ZIP」，手动确认。
3. Mac 与车机必须在本人可信局域网；Mac 上启动 `car-market-kit/Start-Car-Market-Hook.command`。Dump 页填 Mac 当前 Wi-Fi IP（`ipconfig getifaddr en0`），点击「② 将刚导出的 ZIP 发送到我的 Mac」并确认。
4. 车机 POST 到 `http://<Mac-IP>:18816/api/diagnostic-dump`；Mac 验证 ZIP 和 SHA-256 后保存到 `car-market-kit/dumps/`。当前目录为空，尚没有车机上传的 Dump。该目录仅在本地，不自动公开。
5. 普通硬件报告还有**可选 HTTPS 配对**（服务器和 10 位配对码），成功后可手动上传报告/应用自身日志，或明确勾选「后台监测」每五分钟上报；默认关闭，仅日志本诊断应用，不等于完整系统 logcat。需要真实可达的 HTTPS 接收服务，局域网 Dump 接口不是这个 HTTPS 配对服务。

## 3. 任意 APK 安装限制：目前没有证据证明已解除

- Android 4.2.2 真车机目前 **USB/ADB 不可用**，经局域网 HTTP 网页可访问；已有带系统允许包名的试装路径并不等于任意包名都可安装。
- Android 4.2 常规安装：系统设置 > 安全 > 未知来源（若厂商开放），再使用文件管理器/下载页面打开 APK，由系统安装器确认；若显示厂商白名单/签名拦截则停止，不要覆盖原车未知同名应用。
- 原厂商店 Hook 已能在 Mac 启动 `18816` 下载、`18611` TCP 目录、`18081` HTTP 代理，并可用 `--apk /本地目标.apk` 选择 **一个**希望测试的 APK。必须让车机原厂商店真正访问 Hook 的 `host.php`/TCP 列表、下载并弹出安装器确认；目前尚未证明这一路能在车机安装**任意包名**。不能单靠网站成功下载就声称安装成功。
- 诊断「Root / Wi-Fi ADB」页先点「① 只读检查」和「①b Root 深度取证」，然后**用户主动确认**「② 测试 su 授权」。仅当确认 `uid=0` 时，再考虑在自己隔离的热点和停车状态测试 Wi-Fi ADB/授权 `pm install`，完成后关闭 5555。拥有 Root 并不确保 OEM 安装限制都已解除。
- 无 Root、无系统 `INSTALL_PACKAGES` 权限且原厂安装器拦截时，不能从普通 APK 获得任意安装能力。下一步先分析真实上传的 `installer-routes.txt`、`root-feasibility.txt`、原厂安装器代码和权限报告，再决定是否需厂家维修模式或受控固件方案。

**安全界限：** 不自动 Root、不尝试漏洞、不修改系统白名单或移除原厂安装器；任何实际安装仍需确认设备、安装包来源、签名和回退方案。
