package com.shilapi.xcertplay.legacy

import com.shilapi.xcertplay.media.MediaCodecSupport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class EmulatorMockH264Test {
    private val start = byteArrayOf(0, 0, 0, 1)

    @Test fun parsesAccessUnitsAndProvidesRealAvccParameters() {
        val sps = byteArrayOf(0x67, 0x42, 0, 0x1e, 0x00)
        val pps = byteArrayOf(0x68, 0xce.toByte(), 0x06)
        val input = ByteArrayOutputStream()
        repeat(16) { index ->
            listOf(byteArrayOf(9, 0xf0.toByte()),
                sps, pps, byteArrayOf(if (index % 8 == 0) 0x65 else 0x41, 1, 2))
                .forEach { input.write(start); input.write(it) }
        }
        val clip = EmulatorMockH264.parse(input.toByteArray())
        assertEquals(16, clip.samples.size)
        val (actualSps, actualPps) = MediaCodecSupport.avcParameterSets(clip.avcDecoderConfig)
        assertArrayEquals(sps, actualSps)
        assertArrayEquals(pps, actualPps)
        assertTrue(MediaCodecSupport.isRandomAccess(clip.samples[0],
            com.shilapi.xcertplay.airplay.VideoCodec.H264))
        assertTrue(MediaCodecSupport.isRandomAccess(clip.samples[8],
            com.shilapi.xcertplay.airplay.VideoCodec.H264))
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsMissingH264Configuration() {
        EmulatorMockH264.parse(byteArrayOf(0, 0, 0, 1, 0x65, 0, 1, 2))
    }
}
