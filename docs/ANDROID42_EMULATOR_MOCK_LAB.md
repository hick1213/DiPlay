# DiPlay / LAN Cast：Android 4.2.2 ARM32 模拟器排障实验（2026-10-27）

## 隔离环境与验证条件

- **唯一使用的测试系统：** 原移动硬盘中的 Android 4.2.2 / API 17 / armeabi-v7a。
- 系统镜像：`/Volumes/untitled/DiPlayAndroidSdk/system-images/android-17/default/armeabi-v7a/system.img`
- 模拟器：Google Android Emulator 28.0.25，经典 ARM QEMU2，Intel 版经 Rosetta 运行。
- 可写 AVD：`/Volumes/DiPlay42API17Lab/DiPlay_Android42_Lab.avd/`
- Mac 外置盘 APFS 容器：`/Volumes/untitled/DiPlay42API17_Lab.sparsebundle`。
- 模拟器串号：`emulator-5580`；LCD：800×480。
- 不改旧的 `/Volumes/untitled/DiPlay_Android42.avd/` 数据。
- 确认系统：`adb -s emulator-5580 shell getprop ro.build.version.release` 返回 `4.2.2`，
  `ro.build.version.sdk` 返回 `17`。

## 黑屏定位、解决方式与边界

LAN Cast v6 使用原生 Android `MediaPlayer`。同一段 H.264 Baseline + AAC LC MP4
送入 Android 4.2.2 后：

1. `SetAVTransportURI` / `Play` 返回 HTTP 200。
2. `onPrepared` 成功，媒体尺寸为 320×180。
3. 播放器报 `what=1 extra=-19`，无首帧，屏幕黑。
4. 只有 AAC 音轨的测试文件也报 `-19`。
5. Android 系统 `dumpsys media.audio_policy` 输出
   `Primary Output: 0` 和 `Output devices: 00000000`。

尝试过 `-audio coreaudio` 与开机属性
`-prop ro.hardware.audio.primary=goldfish`；属性设置成功，
但 AudioPolicy 仍没有输出设备。原 Android 4.2.2 镜像使用 Goldfish/Ranchu
混合硬件环境，QEMU2 的 Ranchu 虚拟机音频设备没有成功初始化。
**音频 HAL 尚未修复**，不应声称车机硬件音频不可用。

**可稳定使用的模拟器视频测试修复：保留原 H.264 字节流，只去掉 AAC 音轨。**

```sh
tools/carprobe/api17-video-remux.sh \
  /path/to/original-h264-aac.mp4 \
  /path/to/api17-video-only.mp4
```

脚本要求两个文件的 H.264 Annex-B SHA-256 一致。
已用相同源视频验证：视频解码成功、收到 `MEDIA_INFO_VIDEO_RENDERING_START`、
截图中心区域有多种颜色，不是黑屏。这个修复仅用于模拟器测试，
不修改 LAN Cast 的正式 APK，也不假装支持 DRM 音视频。

## 专用 DiPlay Mock APK

**绝不能安装到真实车机。** 本版只用于在 API17 虚拟机排查
“点击连接之后”的 UI、Surface、MediaCodec 和生命周期是否崩溃。

- 本地 APK：
  `/Volumes/DiPlay42API17Lab/mock-apks/DiPlay-API17-MOCK-connect.apk`
- 独立包名：`com.hick.diplay.simulator.mock`
- 版本号：90013；最低 API：17；独立桌面标签「DiPlay 模拟器 MOCK」。
- 通过 `BuildConfig.EMULATOR_MOCK_CONNECT` 编译期开关隔离，并在
  Android 模拟器以外的环境拒绝模拟连接。
- 不打包 `offline-mfi/identity.pk8` 或 `certificate.p7b`，完全跳过
  BluetoothAdapter、Wi-Fi Direct、MFi、iAP2、真实 iPhone、真实 CarPlayController。
- 保留真实主界面、状态回调、`LegacyMediaSink`、Surface 生命周期、
  MediaCodec H.264 解码、关键帧恢复、断开资源释放。
- 模拟 H.264 片段由 ffmpeg 创建，含 SPS/PPS、AUD 和周期性 IDR。
  文件只作为这个模拟器 APK 的 `assets/emulator-mock/sample.h264`，
  不是私有 CarPlay 材料。

### 复现 / 重新构建

外置盘 APFS 实验卷必须已挂载，JDK25/Android37 工具链可用：

```sh
cd /Users/hick/github/DiPlay
unset DIPLAY_AUTH_ASSETS_DIR
sh tools/carprobe/build-api17-simulator-mock.sh /Volumes/DiPlay42API17Lab
adb -s emulator-5580 install -r \
  /Volumes/DiPlay42API17Lab/mock-apks/DiPlay-API17-MOCK-connect.apk
adb -s emulator-5580 shell am start -n \
  com.hick.diplay.simulator.mock/com.shilapi.xcertplay.legacy.LegacyCarPlayActivity
```

按「连接」可运行一次完整模拟握手；按「6轮连接测试」
则依次执行六次模拟会话激活、真正的 H.264 首帧渲染、断开释放。
也可以手动按「断开 MOCK」后再次连接。

Mock 应用的持久化日志位于模拟器：
`/data/data/com.hick.diplay.simulator.mock/files/headunit-diagnostics/events.log`。
可使用 ADB 拉取，不依赖车机 ADB。

### 已完成实测

| 指标 | 结果 |
|---|---|
| API17 模拟握手 | 6 次成功进入 `MOCK_SESSION` |
| **真实** `MediaCodec` 首帧 | 6 次均出现 `MOCK_FIRST_FRAME` |
| 断开 / 释放资源 | 6 次 `MOCK_RESET` |
| 整体压力测试完成 | `MOCK_STRESS_COMPLETE` 出现 1 次 |
| Java fatal / EXCEPTION / INCIDENT | 均为 0 |
| 应用进程 | 压测期间 PID 不变，无异常退出 |
| 持续视频 | 约 7.0～7.4 FPS 输出，后续采样的队列丢帧为 0 |
| 前后台 Surface 切换 | Home 后恢复，旧 Surface 销毁、新 Surface 创建 |
| 前台恢复时的解码器 | `DECODER_START generation=3`，请求新 IDR |
| 前台恢复时的首帧 | 再次出现 `MOCK_FIRST_FRAME`，进程 PID 未变化 |

外部盘原始日志：
`/Volumes/DiPlay42API17Lab/API17-regression-evidence-20261027/diplay-mock-6-rounds-events.log`。

**验证边界：** 本实验表明 UI、真实 API17 H.264 解码器和模拟的连接后状态
不会自行在六轮循环及一次后台恢复中闪退；不等价于验证真正的 CarPlayController、
蓝牙、Wi-Fi Direct、MFi 或真实 iPhone 握手。车机生产 APK 不因此改动，
实际连接闪退仍需 DiPlay v13 日志在车机上取证。
