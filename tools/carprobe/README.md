# Android 4.2 车机只读诊断 APK

**当前发布位置（2026-10-26）：** 所有最新可安装 APK 统一在
`/Users/hick/github/DiPlay/car-market-kit/APKs/`，只保留 DiPlay v13、
LAN Cast v5、车机深度诊断 v11、TVBox v5。之前移动硬盘上的
`/Volumes/untitled/DiPlay-car-probe/` 是已经清理的旧实验目录，
下文引用此目录的早期版本命令仅为历史技术记录，**不要作为当前构建/安装入口**。
当前原厂商店、安装器与 ODEX 的独有上传取证资料（不作为待安装应用）
保存在本地 `car-market-kit/dumps/oem-installer-uploads-20261025.zip`。
当前启动请使用 `car-market-kit/Start-Car-Market-Hook.command`。

这个独立 APK 不依赖 DiPlay/AndroidX，不含 native library，最低系统 API 17。它用于**安装通道打通后**了解车机，不能自动绕过厂商应用安装限制。v10 的常规诊断、Root 深度取证和安装限制取证仍只读；Root 授权测试和 Wi-Fi ADB 开关需要分别主动点击并确认，不自动运行提权漏洞。关键 App Dump 也需要手动确认。

## 功能与权限

- 显示 Android/固件 Build 信息、CPU ABI、显示尺寸、内存等基础信息。
- 深度诊断版额外记录 CPU 频率、内核、温度驱动、内存/存储、网络接口、Wi-Fi Direct、蓝牙、硬件 H.264 解码器，以及 ADB、root 和日志权限的**只读线索**。
- 显示当前应用的安装来源（`PackageManager.getInstallerPackageName`）。
- 查询系统可处理 APK 安装与 `market://` 的应用及系统/用户包名列表。
- 提供本地“复制报告”“手动分享报告”按钮。
- **不申请任何权限，不主动联网或上传，不修改车机设置**。报告可能包含固件指纹和应用清单，请分享前检查隐私。

## 构建

使用 macOS 的 Android SDK build-tools 34、Android 平台 SDK 21、JDK 17 与现有 Android debug keystore；构建文件保存在移动硬盘上：

```sh
sh tools/carprobe/build.sh /Volumes/untitled/DiPlay-car-probe
```

构建脚本拒绝覆盖现有版本，针对 Android 4.2 使用 APK v1/JAR 签名。发布正式包需要另外管理签名密钥；debug 签名只能用于 PoC。

## 原厂安装器白名单的独立 Demo

从上传的 `PackageInstaller.odex` 反汇编看到，新安装 APK 的包名会经过
`AllowSetupApp(String)` 检查；其白名单包含 `title.navi`。原厂商店缓存路径也是特殊放行来源。
定义在类中的 `/storage/sdcard0/` 常量没有用于这个放行分支，因此把 APK 移到 SD 卡根目录不足以解决限制。

另一个独立构建使用测试包名 `title.navi`，不覆盖上面的 `com.hick.carprobe` 诊断版；相同测试包名的旧版 `title.navi` 可以用同一签名升级：

```sh
sh tools/carprobe/build-whitelist.sh /Volumes/untitled/DiPlay-car-probe
```

当前 APK 文件名为 `car-probe-whitelist-title-navi-v4.apk`，通过现有页面的
`/car-probe-whitelist.apk` 下载。它申请 `INTERNET`、`ACCESS_NETWORK_STATE`、
`ACCESS_WIFI_STATE` 和 `BLUETOOTH` 常规权限，不申请系统签名权限：

- 先展示完整诊断报告，本地记录应用自身运行事件，异常退出时保留 Java 崩溃信息。
- 用户输入 10 位一次性配对码、点击配对后，可分别上传硬件报告或应用自身日志。
- 提供 Android 4.2 官方开发者选项和系统设置 Intent 快捷入口，不直接改变 ADB 状态；
  OEM 固件可能拦截这些入口，不能保证可打开。
- 用户**自行勾选**后台监测，才会启动带常驻通知的前台服务。此服务在用户切到
  CarPlay 画面后仍每 5 分钟尝试将硬件状态和应用日志上传，通知栏提供停止按钮。
  未配对或没有网络时不会发送；没有设置开机自启，旧车机若回收服务可能需要重新打开。
- 接收端报告留存在 `receiver/reports/`，包括带 `app-log` 和 `monitor` 标记的记录，
  不向公网提供报告读取接口。配对凭据在手机应用私有存储中保存，服务端只保存令牌哈希。
- 不采集 IMEI、序列号、SSID、MAC 地址和账号；**普通 APK 不能抓取其他应用所有 logcat**。
  如果需要 CarPlay 自身详细日志，必须再给 CarPlay 应用接入同样的上报能力，或获得
  设备正式授权的 ADB/系统日志权限。

注意：如果车机提示“替换已有应用”，应立即取消。白名单包名只解决原厂安装器
的初步来源拦截，不保证所有车机上的签名校验、文件解析或系统行为都通过。
临时 Cloudflare 域名可能变化，需要在应用中修改地址或重建 APK。

### 2026-10-25 原厂商店缓存安装验证 v5

将 APK **保留原包名、原签名**传给系统安装器的独立测试入口，基于原厂商店
`com.acloud.stub.store` 的离线代码分析实现。无需 root，也不会修改原厂安装器：

```sh
sh tools/carprobe/build-whitelist-v5.sh /Volumes/untitled/DiPlay-car-probe
```

产物 `/Volumes/untitled/DiPlay-car-probe/car-probe-whitelist-title-navi-v5.apk`，
发布路由 `/car-probe-cache.apk`（需要重启新版 `car_install_probe.py` 接收进程
才生效）。保留 v4 原 APK 和原路由。v5 的包名仍为 `title.navi`，可用同一签名
更新我们自己的 v4；提示替换不认识的原厂应用时**取消**。升级时不自动开启后台监测。

- 只读检查 `/data/data/com.acloud.stub.store/cache/Data/` 的存在、读取及写入权限。
- 用户手动输入 HTTP(S) APK URL，二次确认后才开始下载；限制 150 MiB，流式处理、
  SHA-256、`PackageManager.getPackageArchiveInfo` 检查，记录原始 `packageName`。
- 用户第三次确认才触发 `ACTION_VIEW` 系统 APK 安装器；不执行静默安装，
  不修改安装包名和签名，不执行 chmod/root/ADB 命令。
- 如车机原厂商店私有目录不可穿越、不可写入，立即停止并将拒绝信息记录到
  诊断应用日志，供用户手动配对后上传。原厂商店 APK 自身的缓存写权限**不等于**
  任意第三方应用都拥有同等权限。

Android 4.2.2 API17 ARM **模拟器**：v5 安装、启动和缓存检测界面成功。
在模拟器中人为创建与原厂路径相同的可写缓存目录后，成功经 HTTP 下载
原包名 APK、计算摘要、展示包名、打开 AOSP `PackageInstallerActivity`，
安装确认界面出现；测试选择了取消，没有安装替换。
这是受控模拟目录验证，**没有证明真实车机的私有目录可写，也没有验证车机定制
`PackageInstaller.odex` 的特殊放行分支**。这些需要真车报告。

原厂商店 APK 已确认调用 `getCacheDir()/Data/`、`chmod 777`，并用
`ACTION_VIEW` 打开 APK；其应用目录访问权限受实际固件和运行时状态影响。

### 车机诊断 v6：设置与 Wi-Fi 代理快捷入口

在“读取车辆信息”页**报告滚动区上方**新增并排的“系统设置 / Wi-Fi 代理”
和“直接打开 Wi-Fi”按钮。优先使用 Android 4.2 官方 Settings Intent；
若 ROM 拦截，将依次尝试其他网络设置 Intent 和 `com.android.settings/.Settings`
明确组件。所有尝试仅打开设置 UI，不自动修改代理、网络或权限。若均失败，
显示 Toast 并写入诊断日志，必须用真车反馈判断厂商的额外拦截。

```sh
sh tools/carprobe/build-whitelist-v6.sh /Users/hick/github/DiPlay/car-market-kit/APKs
```

新版包名依旧 `title.navi`，`versionCode=6`、`minSdk=17`、v1 签名，
使用与 v5 相同的本机签名密钥。可在 Android 4.2 模拟器安装启动，
但测试版不得覆盖不认识的原厂同名应用。保留 v5 的 APK 作为回滚备份。

### 车机诊断 v7：Root / Wi-Fi ADB / 安装权限研究

新增“Root / Wi-Fi ADB / 安装限制研究”独立页面，保留 v6 的设置/Wi-Fi
快捷入口、商店缓存验证和上传报告。普通诊断会读取系统属性、网络 5555
监听状态、应用自身系统权限和 su 文件线索，**不请求 root**。
当前版已将 Root / Wi-Fi ADB 入口放在主页面的固定按钮区域，适合 800×480
横屏；原来的“分享报告”按钮移到下方滚动区。

- 点击“测试 su 授权”时，才尝试 `su -c id`，超时停止；返回 `uid=0`
  才视为真实获得 root shell，而不是仅因发现 su 文件就假定可 Root。
- 点击“启动 Wi-Fi ADB”时先显示风险确认，再验证 `su -c id`，仅在已获
  root 的前提下尝试 `setprop service.adb.tcp.port 5555` 与重启 `adbd`；
  显示实际属性和本机 TCP 5555 状态，**不承诺原厂 ROM 会接受**。
- 提供“关闭 Wi-Fi ADB / 恢复原端口”，尽量恢复操作前记录的端口；
  如果原车不支持 `stop/start adbd`，会显示执行结果。请只在**停车、
  自己控制的热点**操作，完成测试立即关闭 5555；旧 Android 的无线 ADB
  认证和暴露范围由原厂实现决定，不能当成安全的长期开关。
- 显示 `INSTALL_PACKAGES` 等权限；Root/ADB 检测不会执行 `pm install`、
  修改安装器、提权 exploit 或静默安装。`PackageInstaller.odex` 中的
  `AllowSetupApp` 是安装 UI 的白名单检查，取得 root 后调用 `pm install`
  **可能**绕过这个 UI 层，但仍受底层包管理服务、签名和系统策略约束。
- 扫描已安装原厂工程/调试工具的包名，以及 `com.android.settings`、
  `com.acloud.stub.store`、`com.android.packageinstaller` 中名称含调试/工程/
  安装的已导出 Activity，形成无 Root 时可以进一步研究的维护入口清单；
  不自动调用这些入口，也不对未知系统服务发送命令。
- 研究页的最近一份检测/操作结果保存在应用私有配置里，返回主诊断页时
  自动重新生成主报告，因此可以经原有“复制完整报告”或手动云端上报带回分析。

```sh
sh tools/carprobe/build-whitelist-v7.sh /Users/hick/github/DiPlay/car-market-kit/APKs
```

新包为 `title.navi`、`versionCode=7`、`minSdk=17`、v1 签名，与 v6 使用同一个
签名证书；v5/v6 APK 保留不覆盖。当前 Demo 首页的 `/car-probe-settings.apk`
现已改为 v8（保留 v7 供对照），车机真实 root/ADB 能力仍需现场确认。

API 17 ARM 模拟器补充验证：v6 原地升级 v7 成功，主页面固定入口、Root
研究页启动与私有诊断日志持久化成功；模拟器 `/system/xbin/su` 虽存在，
但普通应用 UID 10048 执行 `su -c id` 被拒绝，开启 Wi-Fi ADB 按钮正确
阻止了属性修改，`service.adb.tcp.port` 仍为空。模拟器结果不代表车机授权状态。

独立于 7.5 GB 外置开发工具链的小体积随车包位于 Mac
`/Users/hick/github/DiPlay/car-market-kit/`；其一键入口源文件在
`tools/carprobe/mac-kit/`。运行 Hook 时使用本地 `APKs/` 和 `logs/`，
新版 Demo 首页汇总 DiPlay v13 (`/diplay-android42-toolbar-voice-v13.apk`）、
LAN Cast v5 (`/lan-cast-android42.apk`）、车机深度诊断 v11
(`/car-probe-settings.apk`）以及 TVBox 4.2 v5
(`/tvbox-android42.apk`）四个固定下载入口；页面不改变 APK 的原包名和签名。
无身份基线 APK 不再作为网页下载提供，但本地保留构建与回退文件。
即使卸下移动硬盘也可以启动网络服务。实际原厂商店流量是否走代理、
是否要求真实车机专有权限，仍需现场验证。
商店先请求 HTTP `http://www.xygala.com/host.php` 获取 `app_market`
后端 `host:port`，再通过 TCP JSON 目录协议提供下载条目和 APK URL。
若 v5 反馈缓存不可写，下一条可行路径是经用户控制的热点/网络代理在离线模拟器里
测试**原厂商店自己下载并请求安装**，而不是直接在 `/data/data` 里写文件。
该路径仍未在真实车机验证，必须避免修改或卸载原厂商店。

### 车机诊断 v8：关键 App Dump（可直接传回 Mac，无验证码）

- 主页面的“Root / Wi-Fi ADB”与“关键 App Dump”并排固定显示，800×480 不必滚动。
- Dump 页提供原厂商店 com.acloud.stub.store、安装器
  com.android.packageinstaller、系统设置、System UI、桌面等选项，
  并发现少量含 acloud/factory/engineer 的工程候选包。
- 手动确认后导出 ZIP：每个包的版本、UID、请求权限、APK 路径、
  SHA-256 签名摘要、Activity/Service/Receiver 导出状态，以及当前
  普通应用可读的 /system/app、/data/app 下 APK 和同目录 ODEX。
  不读其他应用的私有数据；遇权限拒绝会在 metadata/ 说明并继续。
- 采用无压缩 ZIP 便于低性能车机工作；单文件最多 32 MiB、整体读取上限
  52 MiB，ZIP 上传限制 60 MiB。优先存车机应用的外部专用目录，
  若没有挂载则保存到自身私有目录，不能直接从文件管理器取出时可上传。
- 手动上传输入 Mac 私有 IPv4 地址，发送到 Hook 的
  http://Mac-IP:18816/api/diagnostic-dump，按用户要求不设置验证码。
  仅可信 LAN IP 可调用，收到的原始 ZIP 不解压，存入
  car-market-kit/dumps，单次限制 60 MiB、最多保存 20 包或累计 512 MiB，
  不会后台上传。Hook 关闭后接收端也关闭。

构建：sh tools/carprobe/build-whitelist-v8.sh /Users/hick/github/DiPlay/car-market-kit/APKs

发布文件 car-probe-whitelist-title-navi-v8.apk，仍用 title.navi + 既有
debug 证书，可在 API 17 直接升级 v7。ARM Android 4.2 模拟器实测已导出
安装器、设置、System UI、桌面的 APK、ODEX、metadata，ZIP 可正常读取。
真车原厂 App 是否可读需现场检测。

### 车机诊断 v9：安装限制取证（v8 的只读增强）

- v9 发布时 Mac 页面提供 v9，原包名 title.navi、原调试签名、API 17，直接升级 v8，
  原 v8 安装包仍保留便于回退。保留系统设置 / Wi-Fi 和 Root / Wi-Fi ADB 入口。
- “安装限制 Dump”仍需先勾选关键 App、点确认，**不自动执行安装或提权**。
  ZIP 新增 install-evidence/installer-routes.txt：对 APK 安装 MIME 的 VIEW、
  INSTALL_PACKAGE Intent 分别查询 file/content 的默认与全部匹配处理者，
  并记录相关组件的导出标志、组件权限、系统安装来源、包签名信息、
  INSTALL_PACKAGES/DELETE_PACKAGES 权限、未知来源和 ADB 状态。
- ZIP 新增 install-evidence/system-files-manifest.txt 和按需读取的
  install-evidence/system/framework/ 及 system/etc/permissions/：
  只尝试白名单中的 services/framework/android.policy JAR/ODEX、
  framework-res.apk、platform.xml 和文件名明确与安装相关的 XML。
  限制单文件 32 MiB，所有原始文件合计 52 MiB，上传 ZIP 上限 60 MiB；
  超限/无权限写 SKIPPED，绝不访问其他 App 的私有目录。
- 用户指定可信临时 Wi-Fi 不需验证码：ZIP 仍只能通过手动输入 Mac IPv4、
  点击上传和再次确认发送，Mac 接收仅允许已配置的可信局域网地址；
  HTTP 收件服务随 Hook 关闭而关闭。
- 构建：sh tools/carprobe/build-whitelist-v9.sh /Users/hick/github/DiPlay/car-market-kit/APKs
  发布文件 car-probe-whitelist-title-navi-v9.apk。需要在真车导出后分析，
  不能从模拟器结果推断原厂安装白名单已被解除。

### 车机诊断 v10：Root 深度取证（保留 v9 的安装路径研究）

- 主页面沿用原 Root / Wi-Fi ADB 和安装限制 Dump 按钮；Root 页增加
  “①b Root 深度取证（只读）”，检查 Boot/ADB 系统属性、su/adbd/installd
  可执行文件的存在及权限、当前进程的 UID/CapEff、系统分区挂载方式和
  SELinux 约束，并评估“已有 su”“调试构建”“普通应用权限不足”等路径。
  结果是线索，不是已获得 Root 的证明。
- 关键 App ZIP 默认增加 install-evidence/root-feasibility.txt，与此前
  installer-routes.txt、system-files-manifest.txt 和可读取 APK/ODEX 一起上传。
  这份 Root 报告不执行 su；仅在用户单独确认“② 测试 su 授权”后，
  后续 ZIP 才包含此前 su -c id 的退出码/UID 信息及时间戳。
- 如 Root 不可用，报告会说明当前普通应用权限无法直接修改 adbd 或
  静默安装。不会自动尝试 CVE、内核漏洞、未知第三方 Root 工具或写入 /system。
- 同包名 title.navi / 同原调试签名 / API 17，可从 v9 覆盖升级。
  Mac 自动 Demo 的默认下载已更新为 v10，无验证码的可信 LAN ZIP 上传不变。
- 构建：sh tools/carprobe/build-whitelist-v10.sh /Users/hick/github/DiPlay/car-market-kit/APKs
  发布文件 car-probe-whitelist-title-navi-v10.apk。

### 车机诊断 v11：蓝车图标与手动桌面快捷方式

- 复用已有 DiPlay 的 `common/src/main/res/drawable/ic_carplay.png`，
  作为 Android 4.2 兼容的传统 PNG Launcher 图标；不依赖矢量图或自适应图标。
- 保留 `MAIN/LAUNCHER`、包名 `title.navi`、API 17 和原调试签名，
  可升级 v5-v10，不清除此前诊断报告。
- 在首页增加“添加桌面图标”按钮，用户主动点击后向 Android 4.2
  Launcher 发送一次标准快捷方式请求，禁用重复创建；仅表示发出请求，
  原厂桌面如有应用白名单或不支持此广播，依然可能不显示。
- 构建：`sh tools/carprobe/build-whitelist-v11.sh /Users/hick/github/DiPlay/car-market-kit/APKs`
  生成 `car-probe-whitelist-title-navi-v11.apk`，旧版本保留。
  本地校验：APK v1 签名通过、证书指纹与 v10 一致、资源表含 PNG 图标
  与可启动 Activity、ZIP 结构完整。真车桌面是否接受快捷方式仍需实测。
- Mac Demo 的 `/car-probe-settings.apk` 当前提供 v11。

## 独立 CarPlay Android 4.2 测试包

诊断 Demo 使用 `title.navi`。本测试包使用另一个出现在原厂安装器
`AllowSetupApp` 白名单中的包名 `sogou.map`，因此和诊断 Demo **互不覆盖**。
当前局域网页展示的 `:legacy` APK 为实验性构建，缺少
`offline-mfi/identity.pk8` 和 `certificate.p7b`，不能完成无线
CarPlay 认证；只有通过合法身份或授权硬件认证适配后，才能作为连接版本交付。

当外部 Gradle 工具链不可用时，可对现有 `:legacy` 编译产物做确定性的
仅清单包名重封装，保留原始三个 DEX、资源和应用类名，再做 API 17 可验证的
APK v1 签名：

```sh
sh tools/carprobe/repackage-carplay.sh /Volumes/untitled/DiPlay-car-probe
```

输出 `diplay-carplay-android42-sogou-v2.apk`，在既有测试页通过
`/diplay-carplay.apk` 下载（仅为旧测试产物）。如果安装器显示正在替换
任何原厂或不认识的应用，应取消，避免更改原车数据。

### 2026-10-25 正式 Android 4.2 移植版本

正式源码位于 `legacy/`（`:legacy`，`minSdk=17`），2026-10-23 的原始
编译产物为 `legacy/build/outputs/apk/debug/legacy-debug.apk`，默认包名
`com.shihab.diplay.legacy`。有完整 SDK/NDK/Gradle 工具链时，可以在
正式构建阶段设置车机允许的独立包名：

```sh
./gradlew :legacy:assembleDebug -PdiplayLegacyApplicationId=sogou.map
```

当前 Mac 的 Gradle 9.5 缓存及 NDK 28 目录指向已卸载的
`/Volumes/DiPlayToolchain`，因而无法完成全新源码编译；为保留现有
正式编译的代码，先备份原 APK 到
`/Volumes/untitled/DiPlay-car-probe/diplay-legacy-android42-original-20261023.apk`，
再运行：

```sh
sh tools/carprobe/package-legacy-android42.sh /Volumes/untitled/DiPlay-car-probe
```

生成 `diplay-legacy-android42-sogou-map-formal-20261025.apk`，现由
`/diplay-carplay.apk` 提供。此工具始终从正式 `:legacy` 原始 APK 开始，
仅将 Manifest 中的安装包名改为 `sogou.map`；为兼容旧 Apktool 链接器，
同时移除了只对高版本 Android 有意义的 `usesPermissionFlags` 属性。
已核对三个 DEX 的 SHA-256 与原包完全一致，APK 通过 API 17 签名验证。
签名证书也与之前自行安装的 `sogou.map` 测试包一致，因此可尝试更新它；
诊断应用 `title.navi` 的包名和文件都不改变。

当前 `:legacy` 原始编译产物不含 MFi 认证身份，只能先验证安装、启动和连接 UI，
不能据此宣称真实 CarPlay 配对完成。只有经过授权、妥善管理的身份材料才可用于
进一步连接测试，且不应通过公开下载链接分发含私钥的 APK。

## 接管原厂应用市场的前提

### 可操作的网络 Hook（无需修改原厂 APK）

**已准备移动硬盘双入口**：

- `/Volumes/untitled/DiPlay-car-probe/Start-Car-Market-Hook.command`：Mac 和车机同网后双击，自动检测 `en0` IP，启动原厂商店 TCP 兼容后端、APK 下载及 HTTP 代理；日后改接手机热点也无需手改 IP。
- `/Volumes/untitled/DiPlay-car-probe/Start-Car-Market-DNS80.command`：在**已控制路由器 DNS**之后，单独通过 Mac 管理员权限启动仅处理 `www.xygala.com/host.php` 的端口 80 服务；不能替代路由器下发 DNS。
- `/Volumes/untitled/DiPlay-car-probe/Check-Car-Market-Hook.command`：Mac 端快速核对 HTTP、TCP、下载和代理；日志在 `market-hook/market-hook.log`。

对应统一命令行：`python3 -m scripts.car_market_hook run --trusted-lan`；
`check` 用于检查正在运行的服务，`prepare` 用于只验证 APK 并打印接管说明。
它们都不会修改车机、路由器或系统安装器。内置两种网络路由的方法只是提前
**准备好 Hook 端点**，没有车机网络接入时无法声称已经接管真机。

已从 `com.acloud.stub.store` 的代码确认：它先 GET
`http://www.xygala.com/host.php`，从 `app_market` 读取 TCP `IP:PORT`，
再通过换行分隔的 JSON 收发 `get_access_token` / `get_app` 等命令。
商店自行下载应用到 `/data/data/com.acloud.stub.store/cache/Data/`，之后使用
`file://` + `ACTION_VIEW` 调起原厂 `PackageInstaller`。本机现有
`scripts/car_market_bridge.py` 模拟以上 HTTP/TCP 端点，并**原样分发**指定 APK；
`scripts/car_market_proxy.py --market-override` 则是可选的、仅匹配这一条
`http://www.xygala.com/host.php` 请求的 HTTP 代理。

Mac 和车机先接入**同一个可控局域网**，用 `ipconfig getifaddr en0` 核实 Mac
局域网地址，用原车设备实际 IP 限制代理访问。在仓库目录运行（示例
`192.168.31.209` 每次可能变化）：

```sh
python3 -u scripts/car_market_bridge.py \
  --apk /Volumes/untitled/Android42-apps/tvbox-android42-full-native-v5.apk \
  --name 'TVBox Android 4.2' \
  --bind 192.168.31.209 --advertise-host 192.168.31.209 \
  --http-port 18816 --tcp-port 18611
```

端口 `18816/TCP` 用于本地 APK 下载，`18611/TCP` 用于原厂商店的应用列表。
本地回环自测实例使用 `18806/18601`，以上另用端口，避免冲突。**以下两种 Hook
选一种**：

1. **车机 Wi-Fi 支持手动 HTTP 代理**：Mac 另启（`CAR_IP` 换成车机 IP）：

   ```sh
   python3 -u scripts/car_market_proxy.py \
     --host 0.0.0.0 --port 18081 --allow-client CAR_IP \
     --market-override 192.168.31.209:18611
   ```

   车机当前 Wi-Fi 的 HTTP 代理填 `192.168.31.209:18081`。只有商店的
   `host.php` 被替换，别的 HTTP/HTTPS 流量仍转发（HTTPS 仅透明隧道）。
   商店的后续原生 TCP socket **不会经过 HTTP 代理**，必须能直连 `18611`。
   验证 Mac 上 `curl -x http://127.0.0.1:18081
   http://www.xygala.com/host.php` 得到 `{"app_market":"192.168.31.209:18611"}`。
   若 OEM HttpClient 忽略 Wi-Fi 代理，此法不能接管商店流量。

2. **掌控路由器 / OpenWrt 热点的 DNS**：仅将域名 `www.xygala.com`
   解析到 Mac 的 `192.168.31.209`，例如 dnsmasq 自定义
   `address=/www.xygala.com/192.168.31.209`。注意硬编码的是
   **`http://www.xygala.com/host.php` 的 80 端口**，不能只启动 `18806`：
   需把本地 Hook HTTP 服务监听 80（运行 bridge 使用 `--http-port 80`，
   需要相应系统权限），或仅将车机针对本机的 TCP 80 映射至 Mac 的
   `18816`。后续 TCP `18611` 和 APK 下载 URL 中的 HTTP `18816` 必须可达。
   DNS 需实际下发给车机；只编辑 Mac 自己的 `/etc/hosts` 没有效果。

在车机重开原厂市场后，应在服务端依次看到 `GET /host.php`、
`TCP CONNECT`、`get_access_token`、`get_app`、`GET /apk/...`。
以上完成后从原厂市场点「安装」，记录系统提示，不需要修改 APK 包名或签名。
原厂安装器的缓存路径放行仅跳过 **一个来源白名单分支**，无法绕过 APK
最低 API、ABI、已装包签名一致性与用户确认。车机真机验证尚未完成，
不能将模拟器测试当作安装成功。

本机离线回归命令：

```sh
python3 -m unittest tools/carprobe/test_market_bridge.py tools/carprobe/test_market_proxy.py tools/carprobe/test_market_hook.py
```

### 网络环境与权限前提

网页托管不是网络接管。当前 Cloudflare 临时隧道只为网页提供 HTTP(S) 入口，不能直接接收 Android 原生应用的任意 TCP/HTTPS CONNECT 流量。接管前需要拿到：

1. 原厂应用商店的准确身份（车机品牌/型号、应用名称、包名或原厂 APK）。
2. 实际后端域名、请求协议、商店列表/下载 API（通过已授权网络代理观察，或对原厂 APK 做离线分析）。
3. 确认车机能否将原厂商店的流量定向到我们可控的服务，以及原厂安装器是否验证 APK 签名、包名和清单。

如发现 HTTPS 证书校验、签名或系统白名单拦截，不能仅靠替换网页、包名或下载地址解决。不得删除或替换原厂市场及系统安装器。

## 上传原厂 APK（公网测试页）

公网诊断首页已增加“上传文件（6 位上传码）”按钮，不需要输入包含一次性令牌的长 URL。

- 接收服务启动参数：`--state-dir /Volumes/untitled/DiPlay-car-probe/receiver`。
- 6 位上传码保存在移动硬盘的 `receiver/upload-code.txt`，自服务启动起 24 小时有效；连续输错 10 次锁定。成功上传一次后失效。
- **不限制文件类型或扩展名**（ZIP、DEX、ODEX、APK 均可），大小不超过 64 MiB，保存到 `receiver/apk-inbox/`，同时生成含文件名、大小和 SHA-256 的同名 JSON。
- 上传的文件 **只保存在外置硬盘供离线检查**，接收服务不安装、不运行，也不对公网提供文件读取 API。
- 新启服务会生成新的上传码。临时 Cloudflare 隧道重启后公网地址也可能变化。
