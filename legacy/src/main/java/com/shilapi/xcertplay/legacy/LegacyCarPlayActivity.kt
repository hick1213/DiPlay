package com.shilapi.xcertplay.legacy

import android.app.Activity
import android.app.AlertDialog
import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig

/** Android 4.2 head-unit host: wireless CarPlay, H.264, PCM/AAC and touch, with native Views. */
class LegacyCarPlayActivity : Activity(), SurfaceHolder.Callback, View.OnTouchListener {
    private lateinit var video: SurfaceView
    private lateinit var status: TextView
    private lateinit var connectButton: Button
    private lateinit var modeButton: Button
    private lateinit var setupButton: Button
    private lateinit var fpsButton: Button
    private lateinit var intro: TextView
    private lateinit var controls: LinearLayout
    private var renderer: LegacyMediaSink? = null
    private var controller: CarPlayController? = null
    // CarPlayController.close() releases Wi-Fi Direct on a worker thread. Starting a
    // new controller before that worker finishes can close the *new* P2P group when
    // Android processes the previous WifiP2pManager.Channel's binder teardown.
    private var disconnecting = false
    @Volatile private var active = false
    private var firstVideoFrame = false
    private var iphoneAudioStatus = "尚未协商 iPhone 音频流"
    private var androidAudioStatus = "尚无 Android 音频播放结果"
    private var testToneStatus = "尚未测试车机本地声音"
    private var microphoneStatus = "等待 iPhone 的麦克风输入请求"
    private var mainAudioOwnerStatus = "未知；等待 iPhone 资源通知"
    private var carPlayAppStateStatus = "等待 CarPlay 语音状态通知"
    private val uiHandler = Handler(Looper.getMainLooper())
    private var toolbarTouchInProgress = false
    private val hideControls = Runnable {
        if (active && firstVideoFrame && ::controls.isInitialized && hasWindowFocus() &&
            !toolbarTouchInProgress) controls.visibility = View.GONE
    }
    private val state by lazy { LegacyState(applicationContext) }
    private val prefs by lazy { getSharedPreferences("legacy-wireless", MODE_PRIVATE) }
    private var mode = WirelessHotspotMode.WIFI_P2P
    private val selectedFps: Int
        get() = LegacyFrameRate.fromSaved(prefs.getInt("carplay-display-fps", LegacyFrameRate.default(Build.VERSION.SDK_INT)))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterFullscreen()
        mode = runCatching {
            WirelessHotspotMode.valueOf(prefs.getString("mode", WirelessHotspotMode.WIFI_P2P.name)!!)
        }.getOrDefault(WirelessHotspotMode.WIFI_P2P)
        if (mode == WirelessHotspotMode.LOCAL_ONLY_HOTSPOT) mode = WirelessHotspotMode.WIFI_P2P
        buildUi()
        val mfiCheck = runCatching { state.ensureLocalAuthentication() }
        val mfiSummary = mfiCheck.fold(
            onSuccess = { directory -> "MFi 自检通过（${directory.name}）" },
            onFailure = { error -> "MFi 自检失败：${error.message ?: error.javaClass.simpleName}" },
        )
        Log.i(TAG, "MFi preflight build=$BUILD_LABEL result=$mfiSummary")
        intro.append("\n\n$BUILD_LABEL · $mfiSummary")
        updateStatus("$BUILD_LABEL · ${modeLabel(mode)} · $mfiSummary")
    }

    private fun buildUi() {
        video = SurfaceView(this)
        video.holder.addCallback(this)
        video.setOnTouchListener(this)
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(14f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(0xb9000000.toInt())
            setOnClickListener { showAudioDiagnostics() }
        }
        connectButton = Button(this).apply {
            text = "连接"
            setOnClickListener {
                if (controller == null) startConnection() else disconnect()
            }
        }
        modeButton = Button(this).apply {
            setOnClickListener { chooseMode() }
        }
        setupButton = Button(this).apply {
            text = "网络参数"
            setOnClickListener {
                if (mode == WirelessHotspotMode.WIFI_P2P) {
                    startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                } else {
                    configureNetwork()
                }
            }
        }
        fpsButton = Button(this).apply {
            text = "${selectedFps} FPS"
            setOnClickListener { chooseFps() }
        }
        updateModeButton()
        val phoneButton = Button(this).apply {
            text = "选择手机"
            setOnClickListener { chooseIphone { } }
        }
        val siriButton = Button(this).apply {
            text = "Siri"
            setOnClickListener { controller?.requestSiri() }
        }
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x90000000.toInt())
            addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(modeButton)
            addView(setupButton)
            addView(fpsButton)
            addView(phoneButton)
            addView(siriButton)
            addView(connectButton)
        }
        intro = TextView(this).apply {
            text = "DiPlay  ·  无线 CarPlay\n\n" +
                "1. 打开车机 Wi-Fi 和蓝牙，并与 iPhone 完成蓝牙配对\n" +
                "2. 选择无线网络方式和要连接的 iPhone\n" +
                "3. 点击「连接」；配对后从 iPhone 同意使用 CarPlay\n" +
                "4. 点击顶部状态文字可测试车机声音；全屏时按 Back 显示控制栏，3 秒无操作自动隐藏"
            setTextColor(Color.WHITE)
            setTextSize(20f)
            gravity = Gravity.CENTER
            setPadding(dp(36), dp(24), dp(36), dp(24))
            setBackgroundColor(0xcc172436.toInt())
        }
        val root = FrameLayout(this)
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        root.addView(intro, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        setContentView(root)
    }

    private fun modeLabel(value: WirelessHotspotMode): String = when (value) {
        WirelessHotspotMode.WIFI_P2P -> "Wi-Fi Direct"
        WirelessHotspotMode.MANUAL -> "车机热点"
        WirelessHotspotMode.EXISTING_WIFI -> "同一 Wi-Fi"
        WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> "不支持"
    }

    private fun updateModeButton() {
        modeButton.text = modeLabel(mode)
        setupButton.text = if (mode == WirelessHotspotMode.WIFI_P2P) "Wi-Fi设置" else "网络参数"
    }

    private fun chooseFps() {
        val choices = LegacyFrameRate.OPTIONS
        AlertDialog.Builder(this)
            .setTitle("CarPlay 画面帧率（选择后下次连接生效）")
            .setSingleChoiceItems(choices.map { "$it FPS" }.toTypedArray(),
                choices.indexOf(selectedFps)) { dialog, index ->
                val chosen = choices[index]
                prefs.edit().putInt("carplay-display-fps", chosen).apply()
                fpsButton.text = "$chosen FPS"
                dialog.dismiss()
                updateStatus(if (controller == null) "已选择 $chosen FPS，连接后生效" else
                    "已选择 $chosen FPS，断开并重新连接后生效（当前连接不变）")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun chooseMode() {
        if (controller != null) {
            updateStatus("请先断开，再切换无线网络方式")
            return
        }
        val choices = arrayOf(WirelessHotspotMode.WIFI_P2P, WirelessHotspotMode.MANUAL,
            WirelessHotspotMode.EXISTING_WIFI)
        AlertDialog.Builder(this)
            .setTitle("无线 CarPlay 连接方式")
            .setItems(choices.map(::modeLabel).toTypedArray()) { _, choice ->
                mode = choices[choice]
                prefs.edit().putString("mode", mode.name).apply()
                updateModeButton()
                updateStatus("无线方式：${modeLabel(mode)}")
                if (mode != WirelessHotspotMode.WIFI_P2P) configureNetwork()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun configureNetwork(afterSaved: () -> Unit = {}) {
        if (mode == WirelessHotspotMode.WIFI_P2P) {
            afterSaved()
            return
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
        }
        val ssid = EditText(this).apply {
            hint = "Wi-Fi 名称 (SSID)"
            setSingleLine(true)
            setText(prefs.getString("ssid-${mode.name}", ""))
        }
        val password = EditText(this).apply {
            hint = "Wi-Fi 密码（开放网络留空）"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("pass-${mode.name}", ""))
        }
        layout.addView(ssid)
        layout.addView(password)
        AlertDialog.Builder(this)
            .setTitle("设置${modeLabel(mode)}")
            .setMessage(if (mode == WirelessHotspotMode.MANUAL)
                "先在车机系统中打开热点，再填写真实热点名称和密码。"
                else "车机与 iPhone 需要连接同一个 Wi-Fi，填写该网络的名称和密码。")
            .setView(layout)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val name = ssid.text.toString().trim()
                        val pass = password.text.toString()
                        if (name.isBlank() || name.toByteArray(Charsets.UTF_8).size > 32 ||
                            (pass.isNotEmpty() && pass.length !in 8..63)) {
                            updateStatus("网络名称必须为 1–32 字节；密码为空或 8–63 位")
                            return@setOnClickListener
                        }
                        prefs.edit().putString("ssid-${mode.name}", name)
                            .putString("pass-${mode.name}", pass).apply()
                        dialog.dismiss()
                        updateStatus("已保存 ${modeLabel(mode)}：$name")
                        afterSaved()
                    }
                }
                dialog.show()
            }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun chooseIphone(onSelected: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_PERMISSIONS)
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            updateStatus("车机没有可用蓝牙适配器，无法启动无线 CarPlay")
            return
        }
        if (!adapter.isEnabled) {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            updateStatus("请先在系统蓝牙设置中开启蓝牙并与 iPhone 配对")
            return
        }
        val devices = runCatching {
            adapter.bondedDevices.orEmpty().sortedBy { it.name ?: it.address }
        }.getOrElse {
            updateStatus("读取蓝牙配对列表失败：${it.message}")
            return
        }
        if (devices.isEmpty()) {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            updateStatus("请在系统蓝牙设置中先配对 iPhone，再返回 DiPlay")
            return
        }
        val saved = prefs.getString("iphone-address", null)
        AlertDialog.Builder(this)
            .setTitle("选择已蓝牙配对的 iPhone")
            .setSingleChoiceItems(devices.map { "${it.name ?: "蓝牙设备"} · ${it.address}" }.toTypedArray(),
                devices.indexOfFirst { it.address == saved }) { dialog, index ->
                val device = devices[index]
                prefs.edit().putString("iphone-address", device.address).apply()
                dialog.dismiss()
                updateStatus("已选择手机：${device.name ?: device.address}")
                onSelected()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startConnection() {
        if (disconnecting) {
            updateStatus("正在释放上一次 Wi-Fi Direct 连接，请稍候…")
            return
        }
        if (controller != null) return
        if (mode != WirelessHotspotMode.WIFI_P2P &&
            prefs.getString("ssid-${mode.name}", "").isNullOrBlank()) {
            configureNetwork { startConnection() }
            return
        }
        if (prefs.getString("iphone-address", null).isNullOrBlank()) {
            chooseIphone { startConnection() }
            return
        }
        if (Build.VERSION.SDK_INT >= 23) {
            val needed = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 31) needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) {
                requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
                return
            }
        }
        connect()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startConnection()
        } else {
            updateStatus("CarPlay 音频需要麦克风权限；请授权后重新连接（拒绝时不会录音）")
        }
    }

    private fun connect() {
        if (controller != null || disconnecting) return
        updateStatus("正在初始化无线 CarPlay…")
        try {
            val mfiDirectory = state.ensureLocalAuthentication()
            val identity = state.identity()
            val mac = LegacyState.deviceAddress(identity.publicKey)
            val size = resources.displayMetrics
            val width = size.widthPixels.coerceIn(320, 1280) and -2
            val height = size.heightPixels.coerceIn(240, 720) and -2
            val config = CarPlayRuntimeConfig(
                mfiTarget = MfiTarget.LOCAL,
                localMfiDirectory = mfiDirectory.absolutePath,
                transport = CarPlayTransport.WIRELESS,
                wirelessHotspotMode = mode,
                wirelessBluetoothDeviceAddress = prefs.getString("iphone-address", null),
                manualHotspotSsid = prefs.getString("ssid-${WirelessHotspotMode.MANUAL.name}", ""),
                manualHotspotPassphrase = prefs.getString("pass-${WirelessHotspotMode.MANUAL.name}", ""),
                manualHotspotSecurity = if (prefs.getString("pass-${WirelessHotspotMode.MANUAL.name}", "").isNullOrEmpty())
                    ManualHotspotSecurity.OPEN else ManualHotspotSecurity.WPA2,
                existingWifiSsid = prefs.getString("ssid-${WirelessHotspotMode.EXISTING_WIFI.name}", "").orEmpty(),
                existingWifiPassphrase = prefs.getString("pass-${WirelessHotspotMode.EXISTING_WIFI.name}", "").orEmpty(),
                identification = Iap2IdentificationConfig(
                    name = "DiPlay",
                    modelIdentifier = "DiPlay-Android42",
                    manufacturer = "DiPlay",
                    serialNumber = "DIPLAY-" + mac.replace(":", ""),
                    firmwareVersion = "0.2.24",
                    hardwareVersion = "1.0",
                    carPlayUsbInterfaceNumber = 3,
                ),
                label = "DiPlay",
                hostName = "diplay-" + mac.replace(":", "").lowercase(),
                hostMac = mac.split(":").map { it.toInt(16).toByte() }.toByteArray(),
            )
            val airPlayConfig = AirPlayConfig(
                deviceName = "DiPlay",
                deviceId = mac,
                btMac = mac,
                sourceVersion = "950.7.1",
                main = AirPlayDisplayConfig(widthPixels = width, heightPixels = height,
                    fps = selectedFps),
                hevc = false,
                // An output-only receiver may be treated by iOS as a display-only CarPlay
                // accessory. Advertise a real microphone input and request its permission.
                microphone = true,
                // Concentus pure-Java encoder handles Opus microphone input on API 17.
                microphoneOpus = true,
                // Android 4.2 lacks a platform Opus decoder; our bundled Concentus
                // fallback now decodes Opus into PCM for Android AudioTrack.
                opusAudioOutput = true,
                // Wireless CarPlay may send AAC music as buffered stream 103 instead of
                // realtime stream 100/102. The legacy MediaSink can decode both.
                mainBufferedAudio = true,
                // No native FM/Android playback competes with CarPlay in this view.
                // iPhone must own mainAudio to send music packets to Android.
                accessoryOwnsMainAudio = false,
                manufacturer = "DiPlay",
                model = "DiPlay-Android42",
                oemLabel = "DiPlay",
            )
            val sink = LegacyMediaSink(this, width, height, ::report)
            if (video.holder.surface.isValid) sink.setSurface(video.holder.surface)
            renderer = sink
            val next = CarPlayController(
                context = this,
                config = config,
                airPlayConfig = airPlayConfig,
                identity = identity,
                pairings = state.pairings(),
                listener = object : AirPlaySessionListener {
                    override fun onSessionActive(session: AirPlaySession) {
                        active = true
                        runOnUiThread {
                            firstVideoFrame = false
                            iphoneAudioStatus = "CarPlay 已连接；等待 iPhone 建立音频流"
                            androidAudioStatus = "尚未启动 Android 音频播放"
                            mainAudioOwnerStatus = "未知；等待 iPhone 资源通知"
                            microphoneStatus = "等待 iPhone 的麦克风输入请求"
                            intro.visibility = View.GONE
                            showControlsTemporarily()
                        }
                        updateStatus("CarPlay 已连接")
                    }

                    override fun onVideoFrameRendered(session: AirPlaySession) {
                        runOnUiThread {
                            if (active && !firstVideoFrame) {
                                firstVideoFrame = true
                                updateStatus("CarPlay 画面已显示")
                                showControlsTemporarily(FIRST_FRAME_CONTROLS_MILLIS)
                            }
                        }
                    }

                    override fun onSessionEnded(session: AirPlaySession) {
                        active = false
                        runOnUiThread {
                            firstVideoFrame = false
                            uiHandler.removeCallbacks(hideControls)
                            controls.visibility = View.VISIBLE
                            intro.visibility = View.VISIBLE
                            if (!disconnecting) updateStatus("无线 CarPlay 已断开，可重新连接")
                        }
                    }

                    override fun onTransportError(message: String) = report(message)

                    override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
                        if (type != "modesChanged") return
                        val states = (params["appStates"] as? List<*>)?.mapNotNull { state ->
                            val fields = state as? Map<*, *> ?: return@mapNotNull null
                            val id = (fields["appStateID"] as? Number)?.toInt() ?: return@mapNotNull null
                            "id=$id state=${fields["state"] ?: "-"} speechMode=${fields["speechMode"] ?: "-"}"
                        }?.joinToString("; ")
                        if (!states.isNullOrBlank()) {
                            Log.i(TAG, "CarPlay application states: $states")
                            runOnUiThread { carPlayAppStateStatus = states }
                        }
                        val resources = params["resources"] as? List<*> ?: return
                        val mainAudio = resources.asSequence().mapNotNull { it as? Map<*, *> }
                            .firstOrNull { (it["resourceID"] as? Number)?.toInt() == 2 } ?: return
                        // CarPlay modesChanged uses entity/permanentEntity, never "owner".
                        val ownerCode = (mainAudio["entity"] as? Number)?.toInt()
                        val permanentOwner = (mainAudio["permanentEntity"] as? Number)?.toInt()
                        val owner = when (ownerCode) {
                            1 -> "iPhone（可发送音乐到 Android）"
                            2 -> "Android（音频资源仍被车机占用）"
                            else -> "未知（${ownerCode ?: "无 entity 字段"}）"
                        }
                        runOnUiThread { mainAudioOwnerStatus = owner }
                        Log.i(TAG, "CarPlay mainAudio resourceID=2 entity=${ownerCode ?: "unknown"} " +
                            "permanentEntity=${permanentOwner ?: "unknown"}")
                    }

                    override fun onDebugLog(message: String) {
                        if (message.startsWith("airplay audio stream type=") ||
                            message.startsWith("airplay buffered audio stream accepted=")) {
                            // These session messages contain only negotiated stream types and ports.
                            // Avoid exposing raw SETUP payloads or authentication data in the UI.
                            runOnUiThread { iphoneAudioStatus = "iPhone 音频协商：${message.take(160)}" }
                        }
                    }
                },
                media = CarPlayMediaEngine(sink, microphoneEnabled = true),
                reportStatus = { status -> onConnectionStatus(status) },
                loadPairRecord = state::pairRecord,
                savePairRecord = state::savePairRecord,
            )
            controller = next
            connectButton.text = "断开"
            next.start()
        } catch (error: Exception) {
            Log.e(TAG, "Legacy CarPlay failed to initialize", error)
            updateStatus("无线 CarPlay 初始化失败：${error.message ?: error.javaClass.simpleName}（需合法 MFi 身份文件）")
            disconnect()
        }
    }

    private fun onConnectionStatus(next: CarPlayStatus) {
        when (next) {
            is CarPlayStatus.Failed -> updateStatus("CarPlay 连接失败：${next.message}")
            CarPlayStatus.StartingHotspot -> updateStatus("正在准备 ${modeLabel(mode)} 无线网络…")
            is CarPlayStatus.HotspotReady -> updateStatus("无线热点就绪：${next.ssid} · ${next.band} · ${next.address}")
            CarPlayStatus.WaitingForPairedIphone -> updateStatus("等待已配对的 iPhone 蓝牙连接…")
            CarPlayStatus.ConnectingBluetooth -> updateStatus("正在建立 iPhone 蓝牙 iAP2 连接…")
            CarPlayStatus.RunningWireless -> updateStatus("无线握手进行中，等待 iPhone 切换到 Wi-Fi…")
            CarPlayStatus.WirelessActive, CarPlayStatus.WirelessActiveFallback ->
                updateStatus("无线 CarPlay 已就绪，等待画面…")
            CarPlayStatus.RunningControl -> updateStatus("iPhone 控制通道已连接，等待 CarPlay 显示…")
            else -> report("CarPlay state: $next")
        }
    }

    private fun updateStatus(value: String) {
        runOnUiThread { status.text = value }
    }

    private fun report(value: String) {
        Log.i(TAG, value)
        if (value.startsWith("Microphone42: ")) {
            runOnUiThread { microphoneStatus = value.removePrefix("Microphone42: ") }
        }
        if (value.startsWith("Audio42: ")) {
            val detail = value.removePrefix("Audio42: ")
            runOnUiThread {
                when {
                    detail.startsWith("车机本地测试音") -> testToneStatus = detail
                    detail.startsWith("Android ") || detail.startsWith("已向 Android") ->
                        androidAudioStatus = detail
                    else -> iphoneAudioStatus = detail
                }
            }
        }
    }

    private fun enterFullscreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LOW_PROFILE or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    override fun onResume() {
        super.onResume()
        enterFullscreen()
        // SurfaceView may have disposed and recreated its Surface while in background.
        // The new attachment triggers decoder reinitialization and an IDR request,
        // without closing the paired iPhone's Wi-Fi or Bluetooth connection.
        if (::video.isInitialized && video.holder.surface.isValid) {
            renderer?.setSurface(video.holder.surface)
        }
    }

    override fun onStop() {
        uiHandler.removeCallbacks(hideControls)
        toolbarTouchInProgress = false
        // SurfaceView can return a different native output buffer after Home/Recents.
        // Retire the decoder while keeping the CarPlay session and audio alive.
        renderer?.setSurface(null)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enterFullscreen()
            scheduleControlsHide()
        } else uiHandler.removeCallbacks(hideControls)
    }

    private fun showControlsTemporarily(delayMillis: Long = FIRST_FRAME_CONTROLS_MILLIS) {
        controls.visibility = View.VISIBLE
        scheduleControlsHide(delayMillis)
    }

    private fun scheduleControlsHide(delayMillis: Long = FIRST_FRAME_CONTROLS_MILLIS) {
        uiHandler.removeCallbacks(hideControls)
        if (active && firstVideoFrame && ::controls.isInitialized &&
            controls.visibility == View.VISIBLE && !toolbarTouchInProgress && hasWindowFocus()) {
            uiHandler.postDelayed(hideControls, delayMillis)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (active && ::controls.isInitialized && controls.visibility == View.VISIBLE) {
            val bar = Rect()
            val inside = controls.getGlobalVisibleRect(bar) &&
                bar.contains(event.rawX.toInt(), event.rawY.toInt())
            if (inside) {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) toolbarTouchInProgress = true
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL) toolbarTouchInProgress = false
                scheduleControlsHide()
            } else if (toolbarTouchInProgress &&
                (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL)) {
                toolbarTouchInProgress = false
                scheduleControlsHide()
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun showAudioDiagnostics() {
        val manager = getSystemService(AUDIO_SERVICE) as? AudioManager
        val volume = manager?.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = manager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        AlertDialog.Builder(this)
            .setTitle("Android 音频输出验证")
            .setMessage("① CarPlay 主音频资源：$mainAudioOwnerStatus\n" +
                "② iPhone 下行：$iphoneAudioStatus\n" +
                "③ Android 播放：$androidAudioStatus\n" +
                "④ 本机测试音：$testToneStatus\n" +
                "⑤ 麦克风输入：$microphoneStatus\n" +
                "⑥ CarPlay 语音状态：$carPlayAppStateStatus\n" +
                "Android 媒体音量：${volume ?: "未知"}/${maxVolume ?: "未知"}\n\n" +
                "PCM、AAC 和兼容回退的 Opus 均通过 Android STREAM_MUSIC 输出。" +
                "若 iPhone 仍自己发声，先看是否协商并收到 iPhone 的实际音频包；" +
                "没有下行音频包时，Android 无法自行抓取 iPhone 本地播放的声音。")
            .setPositiveButton("播放车机测试音") { _, _ -> playLocalTestTone() }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun playLocalTestTone() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            val started = tone.startTone(ToneGenerator.TONE_PROP_BEEP, 600)
            report("Audio42: 车机本地测试音 ${if (started) "已发送" else "播放失败"}，请确认扬声器是否响起")
            uiHandler.postDelayed({ tone.release() }, 800)
        } catch (error: RuntimeException) {
            report("Audio42: 车机本地测试音失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun disconnect() {
        uiHandler.removeCallbacks(hideControls)
        val oldController = controller
        controller = null
        renderer?.close()
        renderer = null
        active = false
        firstVideoFrame = false
        controls.visibility = View.VISIBLE
        intro.visibility = View.VISIBLE
        if (oldController == null) return
        disconnecting = true
        connectButton.isEnabled = false
        connectButton.text = "断开中…"
        updateStatus("正在释放 Wi-Fi Direct 和蓝牙连接，请稍候…")
        oldController.close()
        Thread({
            val released = oldController.awaitClosed(DISCONNECT_TIMEOUT_MILLIS)
            runOnUiThread {
                if (isFinishing || isDestroyed || !disconnecting) return@runOnUiThread
                if (released) {
                    disconnecting = false
                    connectButton.isEnabled = true
                    connectButton.text = "连接"
                    updateStatus("无线连接已完全释放，可以重新连接")
                } else {
                    // Keep the button locked: opening a new P2P group while the old
                    // Channel is still shutting down can make Android remove it.
                    connectButton.text = "清理超时"
                    updateStatus("无线资源释放超时，请重新打开 DiPlay 后再连接")
                }
            }
        }, "diplay-legacy-disconnect").apply { isDaemon = true; start() }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        renderer?.setSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        renderer?.setSurface(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        renderer?.setSurface(null)
    }

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        if (!active) return true
        val count = event.pointerCount.coerceAtMost(2)
        val releasedPointer = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP ||
            event.actionMasked == MotionEvent.ACTION_UP) event.getPointerId(event.actionIndex) else -1
        val contacts = (0 until count).mapNotNull { index ->
            val down = event.actionMasked != MotionEvent.ACTION_CANCEL &&
                event.getPointerId(index) != releasedPointer
            if (!down) null else AirPlayContact(
                id = event.getPointerId(index),
                x = (event.getX(index) / view.width.coerceAtLeast(1)).toDouble().coerceIn(0.0, 1.0),
                y = (event.getY(index) / view.height.coerceAtLeast(1)).toDouble().coerceIn(0.0, 1.0),
                down = true,
            )
        }
        val accepted = controller?.sendTouch(contacts,
            coalesceMove = event.actionMasked == MotionEvent.ACTION_MOVE) ?: false
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            // A coarse coordinate/time marker lets diagnostics correlate tapping an iPhone
            // app's voice/speaker button with its later modesChanged / audio SETUP events.
            // Never record text from CarPlay or a user's spoken audio.
            Log.i(TAG, "CarPlay tap released xPct=${(event.x * 100 / view.width.coerceAtLeast(1)).toInt()} " +
                "yPct=${(event.y * 100 / view.height.coerceAtLeast(1)).toInt()} accepted=$accepted")
        }
        return true
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(hideControls)
        disconnect()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (active && controls.visibility != View.VISIBLE) {
            showControlsTemporarily()
        } else {
            super.onBackPressed()
        }
    }

    private fun dp(value: Int) = (resources.displayMetrics.density * value + 0.5f).toInt()

    companion object {
        private const val TAG = "DiPlay-Android42"
        private const val BUILD_LABEL = "Android 4.2 控制栏自动隐藏 / 语音诊断 v12"
        private const val REQUEST_PERMISSIONS = 42
        private const val FIRST_FRAME_CONTROLS_MILLIS = 3_000L
        private const val DISCONNECT_TIMEOUT_MILLIS = 20_000L
    }
}
