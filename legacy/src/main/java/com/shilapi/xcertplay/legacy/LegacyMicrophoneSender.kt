package com.shilapi.xcertplay.legacy

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.MicrophoneCounters
import com.shilapi.xcertplay.airplay.MicrophonePacketizer
import com.shilapi.xcertplay.media.LegacyMicrophoneOpusCodec
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The regular DiPlay mic helper uses AudioRecord.Builder (API 23), unavailable on 4.2.
 * This implementation uses the API 3 AudioRecord constructor and CarPlay's existing
 * authenticated RTP packetizer. A failed/unavailable microphone sends *explicitly
 * diagnosed silence* to maintain the phone's negotiated duplex audio route.
 */
internal class LegacyMicrophoneSender(
    private val context: Context,
    private val config: MicrophoneConfig,
    private val report: (String) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var socket: DatagramSocket? = null
    private val worker = Thread(::run, "diplay-api17-mic").apply {
        isDaemon = true
        start()
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission") // Explicit checkCallingOrSelfPermission below works on API 17.
    private fun run() {
        var opus: LegacyMicrophoneOpusCodec? = null
        try {
            if (config.codec == AudioCodecKind.OPUS) {
                opus = LegacyMicrophoneOpusCodec(config.bitrate ?: 48_000)
                if (!opus.available) {
                    report("Microphone42: Opus encoder unavailable; cannot send microphone")
                    return
                }
            }
            val frameBytes = config.frameBytes
            val pcm = ByteArray(frameBytes)
            val channels = if (config.channels > 1) AudioFormat.CHANNEL_IN_STEREO
                else AudioFormat.CHANNEL_IN_MONO
            val minBuffer = AudioRecord.getMinBufferSize(config.sampleRate, channels,
                AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer > 0 && context.checkCallingOrSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED) {
                val source = if (config.audioType.equals("speechrecognition", ignoreCase = true))
                    MediaRecorder.AudioSource.VOICE_RECOGNITION
                else if (config.audioType.equals("telephony", ignoreCase = true))
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION
                else MediaRecorder.AudioSource.MIC
                val candidate = runCatching {
                    AudioRecord(source, config.sampleRate, channels,
                        AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer * 2, frameBytes * 4))
                }.getOrNull()
                if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                    recorder = candidate
                    if (runCatching { candidate.startRecording() }.isFailure) {
                        candidate.release()
                        recorder = null
                    }
                } else candidate?.release()
            }
            if (recorder == null) {
                report("Microphone42: no usable capture device; sending SILENCE (Siri/calls cannot hear driver)")
            } else {
                report("Microphone42: Android 4.2 microphone started type=${config.audioType} " +
                    "rate=${config.sampleRate} codec=${config.codec}")
            }

            val udp = DatagramSocket()
            socket = udp
            val counters = MicrophoneCounters()
            var sent = 0L
            while (!closed.get()) {
                val input = recorder
                if (input == null) {
                    pcm.fill(0)
                    Thread.sleep(config.frameMillis.toLong().coerceAtLeast(5))
                } else {
                    var offset = 0
                    while (offset < pcm.size && !closed.get()) {
                        val count = input.read(pcm, offset, pcm.size - offset)
                        if (count <= 0) break
                        offset += count
                    }
                    if (closed.get()) break
                    if (offset < pcm.size) {
                        // Some old vendor AudioRecord implementations return 0 or an error
                        // immediately when no input is routed. Never spin or flood the
                        // iPhone with microphone RTP when recording has stalled.
                        pcm.fill(0, offset, pcm.size)
                        Thread.sleep(config.frameMillis.toLong().coerceAtLeast(5))
                    }
                }
                val bodies = if (config.codec == AudioCodecKind.OPUS)
                    opus?.encode(pcm).orEmpty()
                else listOf(MicrophonePacketizer.toWirePcm(pcm))
                for (body in bodies) {
                    if (body.isEmpty() || closed.get()) continue
                    val packet = MicrophonePacketizer.sealPacket(config.key, config.payloadType,
                        counters, body, config.rtpSamplesPerPacket)
                    udp.send(DatagramPacket(packet, packet.size, config.host, config.port))
                    sent++
                    if (sent == 1L) report("Microphone42: first encrypted uplink RTP packet sent")
                }
            }
        } catch (_: InterruptedException) {
            // Normal disconnect.
        } catch (error: Exception) {
            if (!closed.get()) report("Microphone42: input stream ended: ${error.javaClass.simpleName}")
        } finally {
            runCatching { recorder?.stop() }
            recorder?.release()
            recorder = null
            socket?.close()
            socket = null
            opus?.close()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { recorder?.stop() }
        socket?.close()
        worker.interrupt()
    }
}
