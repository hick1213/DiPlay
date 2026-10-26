package com.shilapi.xcertplay.legacy

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.diagnostic.HeadUnitDiagnostics
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.media.MediaCodecSupport
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Simulator-only deterministic replacement for BT, Wi-Fi Direct, MFi,
 * iAP2/iPhone control and transport handshakes. It intentionally keeps the
 * real API17 SurfaceView, LegacyMediaSink H.264 codec, status/UI code and
 * Activity lifecycle exercised after the simulated connection goes active.
 *
 * NEVER inject this into a real head unit. The caller must check BuildConfig
 * and the physical emulator identity before constructing this class.
 */
internal class EmulatorMockConnectScenario(
    context: Context,
    private val sink: LegacyMediaSink,
    private val onState: (CarPlayStatus) -> Unit,
    private val onSessionActive: () -> Unit,
    private val onFirstFrame: () -> Unit,
    private val onFailed: (Throwable) -> Unit,
) : Closeable {
    private val app = context.applicationContext
    private val running = AtomicBoolean(true)
    private val thread = Thread(::runScenario, "diplay-api17-mock-session").apply {
        isDaemon = true
    }

    fun start() = thread.start()

    private fun runScenario() {
        try {
            // Every step that normally requires an external device is mocked.
            val stages = listOf(
                "BT_BOND_MOCKED" to CarPlayStatus.WaitingForPairedIphone,
                "WIFI_DIRECT_MOCKED" to CarPlayStatus.StartingHotspot,
                "MFI_COPROCESSOR_MOCKED" to CarPlayStatus.ConnectingBluetooth,
                "IAP2_PAIRING_MOCKED" to CarPlayStatus.RunningWireless,
                "AIRPLAY_TRANSPORT_MOCKED" to CarPlayStatus.WirelessActive,
                "CONTROL_HANDSHAKE_MOCKED" to CarPlayStatus.RunningControl,
            )
            for ((name, state) in stages) {
                if (!running.get()) return
                HeadUnitDiagnostics.event("MOCK_STEP", name)
                onState(state)
                Thread.sleep(160L)
            }
            if (!running.get()) return
            HeadUnitDiagnostics.event("MOCK_SESSION", "Session active, no external network or MFi")
            onSessionActive()

            // Real, not mocked: Surface, AVC CSD parsing, MediaCodec and render
            // loop are the same classes as the Android 4.2 car build.
            val bytes = app.assets.open("emulator-mock/sample.h264").use { it.readBytes() }
            val clip = EmulatorMockH264.parse(bytes)
            val parsed = MediaCodecSupport.avcParameterSets(clip.avcDecoderConfig)
            require(parsed.first.isNotEmpty() && parsed.second.isNotEmpty()) {
                "Mock AVCDecoderConfigurationRecord failed the real CarPlay parser"
            }
            HeadUnitDiagnostics.event("MOCK_VIDEO_CONFIG",
                "H264 clip ready, frames=" + clip.samples.size +
                    " codecData=" + clip.avcDecoderConfig.size)
            sink.setVideoDiagnosticHandler(110) { diagnostic ->
                HeadUnitDiagnostics.event("MOCK_RENDER", diagnostic)
                if (diagnostic == "first frame rendered") onFirstFrame()
            }
            sink.setVideoRecoveryHandler(110) {
                HeadUnitDiagnostics.event("MOCK_IDR_REQUEST", "real decoder requested keyframe")
            }
            sink.onVideoCodec(110, VideoCodec.H264)
            sink.onVideoConfig(110, clip.avcDecoderConfig)
            val periodNanos = 125_000_000L
            val epoch = System.nanoTime()
            var sent = 0L
            while (running.get()) {
                for (frame in clip.samples) {
                    if (!running.get()) return
                    val senderNanos = epoch + periodNanos * sent
                    sink.onVideoFrame(110, frame, senderNanos, System.nanoTime())
                    sent++
                    if (sent % 24L == 0L) {
                        HeadUnitDiagnostics.event("MOCK_VIDEO_SENT", "frames=" + sent)
                    }
                    Thread.sleep(125L)
                }
                HeadUnitDiagnostics.event("MOCK_VIDEO_LOOP", "restarted at IDR after frames=" + sent)
            }
        } catch (_: InterruptedException) {
            // Expected when the test driver clicks Disconnect or closes Activity.
        } catch (error: Throwable) {
            if (running.get()) {
                HeadUnitDiagnostics.recordException("Simulator mock connection", error)
                onFailed(error)
            }
        }
    }

    override fun close() {
        if (running.compareAndSet(true, false)) {
            HeadUnitDiagnostics.event("MOCK_DISCONNECT", "stop simulated session")
            thread.interrupt()
        }
    }

    companion object {
        fun isSafeEmulator(): Boolean = Build.FINGERPRINT.startsWith("generic") &&
            (Build.MODEL == "sdk" || Build.HARDWARE == "goldfish" || Build.HARDWARE == "ranchu")
    }
}
