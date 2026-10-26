# Car LAN Cast (Android 4.2 / API 17)

独立包名：`com.uu.uunavi`。不要覆盖 DiPlay、`title.navi` 诊断工具或原车应用。

这是原生 Java 的 DLNA Digital Media Renderer (DMR)，用于没有 ADB/USB 的旧车机。
应用启动后，在同一 Wi-Fi 下通过 SSDP 1900/UDP 广播 `Car LAN Cast`，并提供本地 HTTP
设备描述、ConnectionManager、AVTransport、RenderingControl SOAP 接口。播放使用 Android
系统的 `VideoView` / `MediaPlayer`，无需 root。

支持：能提供标准 DLNA/UPnP 和可直连 HTTP(S)/RTSP 视频 URL 的 App，以及车机解码器支持的
H.264/AAC/MP4 等媒体。兼容性由视频来源、DRM、网络和系统解码器共同决定。

**不支持**：AirPlay 屏幕镜像、Chromecast 专有 Cast 协议、Miracast、受 DRM 保护的视频。
需要这些能力时另加独立协议模块，不应以“所有主流视频都能投”误导用户。

### v2：DLNA 控制端兼容性与诊断（2026-10-26）

某次实测 Bilibili 已经发来 MP4 URL 并成功播放，但腾讯视频失败时，旧接收端未记录到
`SetAVTransportURI` 请求。故障可能发生在设备能力探测、事件订阅或腾讯内容自身投屏限制，
尚不能仅凭这一点确定是哪个环节。

v2 修复了旧版收到 `SUBSCRIBE` 却永远不发送事件的 UPnP/GENA 实现：
对 AVTransport / RenderingControl 发送带正确 SID、SEQ 的初始 `NOTIFY`，
状态发生变化时发送后续通知；支持订阅续期、退订，并拒绝向非订阅来源的主机回调。
增加 `HEAD` 设备描述支持，以及脱敏的 SSDP、SOAP、媒体地址域名与播放器错误码日志。

已在 Android 16 测试手机上用局域网 H.264/AAC 测试片段实际通过
`SetAVTransportURI → Play → MediaPlayer prepared → completed`；
但腾讯视频投屏和 Android 4.2 车机的硬件解码能力仍须真机重测。

v3 在 v2 基础上补了播放器保持屏幕唤醒、视频播放完毕自动回到接收界面。
在测试手机上观察到：视频结束后播放器仍停留在前台，手机熄屏进入 Dozing 时，
HTTP 8200 虽仍处于 LISTEN，却无法从 LAN 新建连接；唤醒后恢复。v3 防止播放期间
自动熄屏，并通过回到具备 KEEP_SCREEN_ON 的接收界面减少两次投屏之间的休眠风险。
用户主动锁屏、厂商省电策略仍可能停止网络，不承诺锁屏后台持续可用。

v4 修复腾讯发送端请求 `GET //renderingcontrol.xml` 却收到 404 的问题。
原因是旧版设备描述 `URLBase` 以 `/` 结尾，同时 `SCPDURL` 又以 `/` 开头，
部分 DLNA 控制端直接拼接成 `//renderingcontrol.xml`。新版将服务路径声明为
相对路径，同时接收端兼容多余的前导斜杠；增加了双斜杠及 URL 解析回归测试。
`STOPPED` 是 AVTransport 的正常空闲状态，不代表接收服务进程已经停止，
界面现在明确显示「等待投屏（DLNA STOPPED，接收服务仍在线）」。

默认安装包为 `lan-cast-android42-v4.apk`，包名不变，版本号升至 4，
使用相同签名覆盖安装；原 v1 / v2 / v3 文件请单独保留以便回退。

可在已经启动的测试接收器上执行专用协议测试（测试会短暂改变接收器播放状态）：

```sh
LAN_CAST_TEST_HOST=192.168.1.110 LAN_CAST_TEST_CALLBACK_HOST=192.168.1.109 \
  python3 tools/lan-cast-android42/test_protocol_integration.py -v
```

若不提供测试地址，该测试会跳过，不会主动访问外部设备。

构建：

```sh
sh tools/lan-cast-android42/build.sh /Volumes/untitled/Android42-apps
```

检查安装前确保车机原有应用未使用该包名；遇到“替换现有应用”应取消。
APK 构建/签名成功不代表已在 Android 4.2 真机成功播放，后者必须单独验证。
