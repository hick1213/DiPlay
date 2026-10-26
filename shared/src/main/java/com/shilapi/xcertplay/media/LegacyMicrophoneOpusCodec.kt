package com.shilapi.xcertplay.media

import java.io.Closeable

/** Pure-Java Opus encoder usable by the API 17 legacy host without newer Android media APIs. */
class LegacyMicrophoneOpusCodec(bitrate: Int) : Closeable {
    private val encoder = SoftwareOpusEncoder(bitrate)
    val available: Boolean get() = encoder.available

    /** A single 20ms, 48kHz, mono, 16-bit little-endian PCM frame. */
    fun encode(pcm: ByteArray): List<ByteArray> = encoder.encode(pcm)

    override fun close() = encoder.close()
}
