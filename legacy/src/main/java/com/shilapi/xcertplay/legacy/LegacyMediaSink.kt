package com.shilapi.xcertplay.legacy

import android.content.Context
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.media.MediaCodecSupport
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** API 16 MediaCodec and pre-Lollipop AudioTrack implementation; no AndroidX or NDK. */
internal class LegacyMediaSink(
    private val context: Context,
    private val width: Int,
    private val height: Int,
    private val report: (String) -> Unit,
) : MediaSink, Closeable {
    private sealed class VideoEvent {
        data class Output(val surface: Surface?) : VideoEvent()
        data class Configure(val codec: VideoCodec, val data: ByteArray) : VideoEvent()
        data class Frame(
            val bytes: ByteArray,
            val senderNanos: Long,
            val arrivalNanos: Long,
            val enqueuedNanos: Long = System.nanoTime(),
        ) : VideoEvent()
    }

    private val closed = AtomicBoolean(false)
    private val firstVideoPacketSeen = AtomicBoolean(false)
    private val queueDroppedFrames = AtomicLong(0)
    private val videoEvents = ArrayBlockingQueue<VideoEvent>(8)
    /** Last requested output; repeated surfaceChanged/onResume must not discard IDR frames. */
    private var requestedSurface: Surface? = null // guarded by videoEvents
    private val videoThread = Thread(::renderVideo, "diplay-api17-video").apply {
        isDaemon = true
        start()
    }
    private val audio = ConcurrentHashMap<AudioStreamId, LegacyAudioRenderer>()
    private val firstAudioPacketSeen = ConcurrentHashMap<AudioStreamId, Boolean>()
    private val microphone = ConcurrentHashMap<AudioStreamId, LegacyMicrophoneSender>()
    private val audioLock = Any()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        report("AudioFocus42: Android focus changed=$change")
    }
    private var ownsAudioFocus = false
    @Volatile private var requestedCodec = VideoCodec.H264
    @Volatile private var videoDiagnostic: (String) -> Unit = {}
    @Volatile private var requestVideoKeyFrame: (() -> Unit)? = null

    fun setSurface(surface: Surface?) {
        if (closed.get()) return
        synchronized(videoEvents) {
            if (requestedSurface === surface && (surface == null || surface.isValid)) return
            requestedSurface = surface
            // Frames from the old output generation cannot be decoded on a new Surface.
            videoEvents.removeAll { it is VideoEvent.Frame }
            enqueue(VideoEvent.Output(surface))
        }
    }

    override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {
        if (type == MAIN_SCREEN) videoDiagnostic = handler
    }

    override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) {
        if (type == MAIN_SCREEN) requestVideoKeyFrame = handler
    }

    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        if (type == MAIN_SCREEN) requestedCodec = codec
    }

    override fun onVideoConfig(type: Int, codecData: ByteArray) {
        if (type == MAIN_SCREEN) enqueue(VideoEvent.Configure(requestedCodec, codecData.copyOf()))
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
        onVideoFrame(type, naluBytes, 0L, System.nanoTime())
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) {
        if (type == MAIN_SCREEN && naluBytes.isNotEmpty()) {
            if (firstVideoPacketSeen.compareAndSet(false, true)) {
                report("Android 4.2 video data reached decoder queue")
            }
            enqueue(VideoEvent.Frame(naluBytes, senderNanos, arrivalNanos))
        }
    }

    private fun enqueue(event: VideoEvent) {
        if (closed.get()) return
        synchronized(videoEvents) {
            if (videoEvents.offer(event)) return
            // On a slow 4.2 decoder only frames are disposable. Preserve surface changes and
            // SPS/PPS updates even if the queue is full of obsolete video packets.
            val oldestFrame = videoEvents.firstOrNull { it is VideoEvent.Frame }
            if (oldestFrame != null) {
                videoEvents.remove(oldestFrame)
                queueDroppedFrames.incrementAndGet()
            } else if (event is VideoEvent.Frame) {
                queueDroppedFrames.incrementAndGet()
                return
            }
            else videoEvents.poll()
            videoEvents.offer(event)
        }
    }

    private fun renderVideo() {
        var surface: Surface? = null
        var configuration: VideoEvent.Configure? = null
        var decoder: MediaCodec? = null
        var rendered = false
        var frameClock = LegacyVideoFrameClock()
        var framesReceived = 0L
        var framesQueued = 0L
        var missingInputBuffers = 0L
        var oversizedFrames = 0L
        var framesRendered = 0L
        var lastKeyframeRequestNs = 0L
        var previousOutputNanos = 0L
        var largestOutputGapMillis = 0L
        var queueDelaySumMillis = 0L
        var maxQueueDelayMillis = 0L
        var windowFrames = 0L
        var windowOutput = 0L
        var windowStarted = System.nanoTime()
        var lastReportedDrop = 0L
        val videoSync = LegacyVideoSyncState()
        var surfaceGeneration = 0L
        var keyframeWaitDrops = 0L

        fun requestKeyFrameIfDue(force: Boolean = false) {
            if (decoder == null || surface?.isValid != true || rendered) return
            val now = System.nanoTime()
            if (force || now - lastKeyframeRequestNs >= 1_200_000_000L) {
                val recovery = requestVideoKeyFrame ?: return
                lastKeyframeRequestNs = now
                runCatching { recovery.invoke() }
                    .onFailure { Log.w(TAG, "H.264 surface keyframe request failed", it) }
            }
        }

        fun drainOutput(codec: MediaCodec) {
            val info = MediaCodec.BufferInfo()
            while (true) {
                val output = codec.dequeueOutputBuffer(info, 0)
                if (output < 0) break
                codec.releaseOutputBuffer(output, true)
                framesRendered++
                windowOutput++
                val now = System.nanoTime()
                if (previousOutputNanos != 0L) {
                    val gap = (now - previousOutputNanos) / 1_000_000L
                    if (gap in 0..1_000L) largestOutputGapMillis = maxOf(largestOutputGapMillis, gap)
                }
                previousOutputNanos = now
                if (!rendered) {
                    rendered = true
                    videoDiagnostic("first frame rendered")
                }
            }
        }

        fun stop() {
            decoder?.let { codec ->
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
            decoder = null
            frameClock = LegacyVideoFrameClock()
            rendered = false
            framesReceived = 0
            framesQueued = 0
            missingInputBuffers = 0
            oversizedFrames = 0
            framesRendered = 0
            lastKeyframeRequestNs = 0
            previousOutputNanos = 0
            largestOutputGapMillis = 0
            queueDelaySumMillis = 0
            maxQueueDelayMillis = 0
            windowFrames = 0
            windowOutput = 0
            windowStarted = System.nanoTime()
            lastReportedDrop = queueDroppedFrames.get()
            videoSync.reset()
            keyframeWaitDrops = 0L
        }

        fun configure() {
            stop()
            val output = surface?.takeIf { it.isValid } ?: return
            val config = configuration ?: return
            if (config.codec != VideoCodec.H264) {
                report("Android 4.2 only supports H.264 CarPlay output")
                return
            }
            val (sps, pps) = MediaCodecSupport.avcParameterSets(config.data)
            if (sps.isEmpty() || pps.isEmpty()) {
                report("Invalid H.264 configuration received from iPhone")
                return
            }
            try {
                val format = MediaFormat.createVideoFormat("video/avc", width, height)
                format.setByteBuffer("csd-0", ByteBuffer.wrap(START_CODE + sps))
                format.setByteBuffer("csd-1", ByteBuffer.wrap(START_CODE + pps))
                val codec = MediaCodec.createDecoderByType("video/avc")
                try {
                    codec.configure(format, output, null, 0)
                    codec.start()
                    decoder = codec
                    report("Android 4.2 H.264 decoder started")
                    Log.i(TAG, "H.264 surface recovery generation=$surfaceGeneration waitingForIDR=true")
                    // The phone may still be sending P-frames from the previous
                    // decoder's reference chain. Request a fresh IDR immediately.
                    requestKeyFrameIfDue(force = true)
                } catch (error: Exception) {
                    codec.release()
                    throw error
                }
            } catch (error: Exception) {
                report("H.264 decoder unavailable: ${error.message}")
            }
        }

        try {
            while (!closed.get()) {
                val next = videoEvents.poll(250, TimeUnit.MILLISECONDS)
                if (next == null) {
                    // No incoming video still needs an IDR on returning from background.
                    requestKeyFrameIfDue()
                    decoder?.let(::drainOutput)
                    continue
                }
                when (val event = next) {
                    is VideoEvent.Output -> {
                        if (surface === event.surface && decoder != null &&
                            event.surface?.isValid == true) continue
                        surfaceGeneration++
                        surface = event.surface
                        configure()
                    }
                    is VideoEvent.Configure -> {
                        if (decoder != null && configuration?.codec == event.codec &&
                            configuration?.data?.contentEquals(event.data) == true) continue
                        configuration = event
                        configure()
                    }
                    is VideoEvent.Frame -> {
                        val codec = decoder ?: continue
                        if (surface?.isValid != true) continue
                        try {
                            if (!videoSync.canQueue(event.bytes)) {
                                keyframeWaitDrops++
                                requestKeyFrameIfDue()
                                continue
                            }
                            framesReceived++
                            windowFrames++
                            val delayMs = (System.nanoTime() - event.enqueuedNanos).coerceAtLeast(0L) / 1_000_000L
                            queueDelaySumMillis += delayMs
                            maxQueueDelayMillis = maxOf(maxQueueDelayMillis, delayMs)
                            // An immediate timeout can discard the initial IDR while the codec
                            // allocates its input buffers. Losing that keyframe leaves a black
                            // screen even though the iPhone keeps sending delta frames.
                            // Drain decoded output *before* waiting for input: a full output
                            // side can otherwise starve the input side and drop reference frames.
                            drainOutput(codec)
                            var index = codec.dequeueInputBuffer(10_000)
                            if (index < 0) {
                                drainOutput(codec)
                                index = codec.dequeueInputBuffer(5_000)
                            }
                            if (index >= 0) {
                                val input = codec.inputBuffers[index]
                                if (event.bytes.size <= input.capacity()) {
                                    input.clear()
                                    input.put(event.bytes)
                                    codec.queueInputBuffer(index, 0, event.bytes.size,
                                        frameClock.next(event.senderNanos), 0)
                                    videoSync.onQueued()
                                    framesQueued++
                                } else {
                                    oversizedFrames++
                                    codec.queueInputBuffer(index, 0, 0, frameClock.next(event.senderNanos), 0)
                                }
                            } else {
                                missingInputBuffers++
                                if (videoSync.awaitingKeyframe) requestKeyFrameIfDue()
                            }
                            drainOutput(codec)
                            val now = System.nanoTime()
                            if (now - windowStarted >= 5_000_000_000L) {
                                val secs = (now - windowStarted) / 1_000_000_000.0
                                val drops = queueDroppedFrames.get()
                                val line = "VideoPerf42: rx=%.1ffps output=%.1ffps queueAvg=%dms " +
                                    "queueMax=%dms outputGapMax=%dms queued=%d decoded=%d queueDrops=%d " +
                                    "noInput=%d pending=%d"
                                Log.i(TAG, line.format(windowFrames / secs, windowOutput / secs,
                                    queueDelaySumMillis / windowFrames.coerceAtLeast(1L), maxQueueDelayMillis,
                                    largestOutputGapMillis, framesQueued, framesRendered,
                                    drops - lastReportedDrop, missingInputBuffers, videoEvents.size) +
                                    " waitingIDR=${videoSync.awaitingKeyframe} idrWaitDrops=$keyframeWaitDrops")
                                // A dropped H.264 reference invalidates dependent pictures.
                                // Ask for a fresh IDR rather than silently displaying corrupt frames.
                                if (drops > lastReportedDrop &&
                                    now - lastKeyframeRequestNs >= 3_000_000_000L) {
                                    lastKeyframeRequestNs = now
                                    runCatching { requestVideoKeyFrame?.invoke() }
                                        .onFailure { Log.w(TAG, "H.264 queue overflow recovery failed", it) }
                                }
                                lastReportedDrop = drops
                                windowFrames = 0L
                                windowOutput = 0L
                                queueDelaySumMillis = 0L
                                maxQueueDelayMillis = 0L
                                largestOutputGapMillis = 0L
                                windowStarted = now
                                keyframeWaitDrops = 0L
                            }
                            if (!rendered && framesReceived % 90L == 0L) {
                                val status = "H.264 waiting output: input=$framesReceived queued=$framesQueued " +
                                    "noBuffer=$missingInputBuffers oversized=$oversizedFrames output=$framesRendered"
                                Log.i(TAG, status)
                                videoDiagnostic(status)
                                val now = System.nanoTime()
                                if (now - lastKeyframeRequestNs >= 3_000_000_000L) {
                                    lastKeyframeRequestNs = now
                                    runCatching { requestVideoKeyFrame?.invoke() }
                                        .onFailure { Log.w(TAG, "H.264 keyframe recovery failed", it) }
                                }
                            }
                        } catch (error: Exception) {
                            report("H.264 decoder failed: ${error.message}")
                            stop()
                        }
                    }
                }
            }
        } catch (_: InterruptedException) {
            // Owner closed the renderer.
        } finally {
            stop()
        }
    }

    override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {
        if (closed.get()) return
        synchronized(audioLock) {
            if (closed.get()) return
            audio.remove(id)?.close()
            firstAudioPacketSeen.remove(id)
            if (!ownsAudioFocus) {
                val granted = runCatching {
                    audioManager?.requestAudioFocus(
                        focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN,
                    ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                }.getOrDefault(false)
                ownsAudioFocus = granted
                report("AudioFocus42: stream=${id.type}/${id.audioType} granted=$granted")
            }
            report("Audio42: iPhone 已建立下行音频流 type=${id.type}/${id.audioType} " +
                "${format.codec} ${format.sampleRate}Hz ${format.channels}ch，等待实际音频数据")
            audio[id] = LegacyAudioRenderer(format, report)
        }
    }

    override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
        val player = audio[id] ?: return
        if (rtp.size > 12 && firstAudioPacketSeen.putIfAbsent(id, true) == null) {
            report("Audio42: 已收到 iPhone RTP 音频数据 type=${id.type}/${id.audioType} ${format.codec}")
        }
        player.offer(rtp, sample)
    }

    override fun onAudioStopped(id: AudioStreamId) {
        synchronized(audioLock) {
            audio.remove(id)?.close()
            firstAudioPacketSeen.remove(id)
            if (audio.isEmpty()) {
                releaseAudioFocus()
                report("Audio42: 已停止接收音频流，等待 iPhone 下次播放")
            }
        }
    }

    override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {
        if (closed.get()) return
        microphone.remove(id)?.close()
        microphone[id] = LegacyMicrophoneSender(context, config, report)
    }

    override fun onMicrophoneStopped(id: AudioStreamId) {
        microphone.remove(id)?.close()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        videoThread.interrupt()
        microphone.values.forEach { it.close() }
        microphone.clear()
        synchronized(audioLock) {
            audio.values.forEach { it.close() }
            audio.clear()
            firstAudioPacketSeen.clear()
            releaseAudioFocus()
        }
    }

    private fun releaseAudioFocus() {
        if (ownsAudioFocus) {
            runCatching { audioManager?.abandonAudioFocus(focusListener) }
            ownsAudioFocus = false
        }
    }

    companion object {
        private const val MAIN_SCREEN = 110
        private const val TAG = "DiPlay-Android42-Video"
        private val START_CODE = byteArrayOf(0, 0, 0, 1)
    }
}

/** A separate worker per audio stream so AudioTrack writes never block AirPlay reception. */
private class LegacyAudioRenderer(
    private val format: AudioFormat,
    private val report: (String) -> Unit,
) : Closeable {
    private data class Packet(val bytes: ByteArray, val sample: Int)

    private val running = AtomicBoolean(true)
    private val queue = ArrayBlockingQueue<Packet>(32)
    private val worker = Thread(::play, "diplay-api17-audio").apply { isDaemon = true; start() }

    fun offer(rtp: ByteArray, sample: Int) {
        if (rtp.size <= 12 || !running.get()) return
        if (!queue.offer(Packet(rtp, sample))) {
            queue.poll()
            queue.offer(Packet(rtp, sample))
        }
    }

    private fun play() {
        val channelMask = if (format.channels == 2) AndroidAudioFormat.CHANNEL_OUT_STEREO
            else AndroidAudioFormat.CHANNEL_OUT_MONO
        var track: AudioTrack? = null
        var decoder: MediaCodec? = null
        var firstWriteReported = false
        try {
            val opusDecoder = if (format.codec == AudioCodecKind.OPUS)
                LegacyOpusPcmDecoder(format.sampleRate, format.channels) else null
            val minimum = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, AndroidAudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) throw IllegalStateException("Unsupported PCM output rate ${format.sampleRate}")
            val bytesPerSecond = format.sampleRate * format.channels.coerceIn(1, 2) * 2
            track = AudioTrack(AudioManager.STREAM_MUSIC, format.sampleRate, channelMask,
                AndroidAudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, bytesPerSecond / 5), AudioTrack.MODE_STREAM)
            if (track.state != AudioTrack.STATE_INITIALIZED) throw IllegalStateException("AudioTrack init failed")
            if (format.codec == AudioCodecKind.AAC_LC) {
                val index = MediaCodecSupport.aacFrequencyIndex(format.sampleRate)
                val csd = (2 shl 11) or (index shl 7) or (format.channels.coerceIn(1, 7) shl 3)
                val mediaFormat = MediaFormat.createAudioFormat("audio/mp4a-latm",
                    format.sampleRate, format.channels)
                mediaFormat.setInteger(MediaFormat.KEY_IS_ADTS, 1)
                mediaFormat.setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf((csd ushr 8).toByte(), csd.toByte())))
                decoder = MediaCodec.createDecoderByType("audio/mp4a-latm").also {
                    it.configure(mediaFormat, null, null, 0)
                    it.start()
                }
            }
            track.play()
            report("Audio42: Android AudioTrack 已启动 ${format.sampleRate}Hz / ${format.channels}ch，等待 PCM 数据")

            fun writeOutput(pcm: ByteArray) {
                var offset = 0
                while (running.get() && offset < pcm.size) {
                    val written = track.write(pcm, offset, pcm.size - offset)
                    if (written <= 0) throw IllegalStateException("AudioTrack.write returned $written")
                    offset += written
                }
                if (offset > 0 && !firstWriteReported) {
                    firstWriteReported = true
                    report("Audio42: 已向 Android 扬声器提交音频 ${format.codec} " +
                        "${format.sampleRate}Hz / ${format.channels}ch")
                }
            }

            while (running.get()) {
                val packet = queue.take()
                val payload = packet.bytes.copyOfRange(12, packet.bytes.size)
                if (format.codec == AudioCodecKind.LPCM) {
                    var offset = 0
                    while (offset + 1 < payload.size) {
                        val byte = payload[offset]
                        payload[offset] = payload[offset + 1]
                        payload[offset + 1] = byte
                        offset += 2
                    }
                    writeOutput(payload)
                } else if (format.codec == AudioCodecKind.OPUS) {
                    writeOutput(checkNotNull(opusDecoder).decode(payload))
                } else {
                    val codec = decoder ?: continue
                    val adts = MediaCodecSupport.adtsFrame(payload, format.sampleRate, format.channels)
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = codec.inputBuffers[inputIndex]
                        if (adts.size <= buffer.capacity()) {
                            buffer.clear()
                            buffer.put(adts)
                            codec.queueInputBuffer(inputIndex, 0, adts.size,
                                (packet.sample.toLong() and 0xffff_ffffL) * 1_000_000 / format.sampleRate, 0)
                        } else codec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                    }
                    val info = MediaCodec.BufferInfo()
                    while (true) {
                        val outputIndex = codec.dequeueOutputBuffer(info, 0)
                        if (outputIndex < 0) break
                        val buffer = codec.outputBuffers[outputIndex]
                        val pcm = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(pcm)
                        codec.releaseOutputBuffer(outputIndex, false)
                        writeOutput(pcm)
                    }
                }
            }
        } catch (_: InterruptedException) {
            // Stream was replaced or the app is closing.
        } catch (error: Exception) {
            report("Audio42: Android 音频播放失败 ${format.codec}：${error.message ?: error.javaClass.simpleName}")
        } finally {
            decoder?.let { runCatching { it.stop() }; runCatching { it.release() } }
            track?.let { runCatching { it.pause() }; runCatching { it.flush() }; runCatching { it.release() } }
        }
    }

    override fun close() {
        running.set(false)
        worker.interrupt()
    }
}
