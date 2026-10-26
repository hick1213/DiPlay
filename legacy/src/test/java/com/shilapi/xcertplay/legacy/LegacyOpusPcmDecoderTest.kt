package com.shilapi.xcertplay.legacy

import org.concentus.OpusApplication
import org.concentus.OpusEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class LegacyOpusPcmDecoderTest {
    @Test
    fun decodesOpusIntoAndroidLittleEndianPcm() {
        val encoder = OpusEncoder(48_000, 1, OpusApplication.OPUS_APPLICATION_AUDIO)
        val input = ShortArray(960) { index ->
            (sin(2 * PI * 440.0 * index / 48_000) * 10_000).toInt().toShort()
        }
        val compressed = ByteArray(1_275)
        val length = encoder.encode(input, 0, input.size, compressed, 0, compressed.size)
        assertTrue(length > 0)

        val decoder = LegacyOpusPcmDecoder(48_000, 1)
        val pcm = decoder.decode(compressed.copyOf(length))
        assertEquals(960 * 2, pcm.size)
        assertTrue(pcm.any { it.toInt() != 0 })
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesUnsupportedOutputRate() {
        LegacyOpusPcmDecoder(44_100, 1)
    }
}
