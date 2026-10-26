package com.shilapi.xcertplay.legacy

import org.concentus.OpusDecoder

/**
 * Software Opus fallback for Android 4.2, which has no platform Opus decoder.
 * Returns little-endian signed 16-bit PCM suitable for STREAM_MUSIC AudioTrack.
 */
internal class LegacyOpusPcmDecoder(sampleRate: Int, private val channels: Int) {
    init {
        require(sampleRate == 48_000) { "CarPlay Opus output must decode at 48 kHz" }
        require(channels in 1..2) { "Unsupported Opus channel count: $channels" }
    }

    private val decoder = OpusDecoder(sampleRate, channels)
    private val samples = ShortArray(MAX_FRAME_SAMPLES * channels)

    fun decode(packet: ByteArray): ByteArray {
        require(packet.isNotEmpty()) { "Empty Opus RTP payload" }
        val count = decoder.decode(packet, 0, packet.size, samples, 0, MAX_FRAME_SAMPLES, false)
        require(count in 1..MAX_FRAME_SAMPLES) { "Invalid decoded Opus frame length: $count" }
        val pcm = ByteArray(count * channels * 2)
        for (index in 0 until count * channels) {
            val value = samples[index].toInt()
            pcm[index * 2] = value.toByte()
            pcm[index * 2 + 1] = (value ushr 8).toByte()
        }
        return pcm
    }

    private companion object {
        // Opus permits packets up to 120 ms at its 48 kHz output rate.
        const val MAX_FRAME_SAMPLES = 5_760
    }
}
