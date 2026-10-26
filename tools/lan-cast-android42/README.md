# Car LAN Cast (Android 4.2 / API 17)

独立包名：`com.uu.uunavi`。不要覆盖 DiPlay、`title.navi` 诊断工具或原车应用。

这是原生 Java 的第一阶段 DLNA Digital Media Renderer (DMR)，用于没有 ADB/USB 的旧车机。
应用启动后，在同一 Wi-Fi 下通过 SSDP 1900/UDP 广播 `Car LAN Cast`，并提供本地 HTTP
设备描述、ConnectionManager、AVTransport、RenderingControl SOAP 接口。播放使用 Android
系统的 `VideoView` / `MediaPlayer`，无需 root。

支持：能提供标准 DLNA/UPnP 和可直连 HTTP(S)/RTSP 视频 URL 的 App，以及车机解码器支持的
H.264/AAC/MP4 等媒体。兼容性由视频来源、DRM、网络和系统解码器共同决定。

**不支持**：AirPlay 屏幕镜像、Chromecast 专有 Cast 协议、Miracast、受 DRM 保护的视频。
需要这些能力时另加独立协议模块，不应以“所有主流视频都能投”误导用户。

构建：

```sh
sh tools/lan-cast-android42/build.sh /Volumes/untitled/Android42-apps
```

检查安装前确保车机原有应用未使用该包名；遇到“替换现有应用”应取消。
APK 构建/签名成功不代表已在 Android 4.2 真机成功播放，后者必须单独验证。
