package com.shilapi.xcertplay.media

import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyMicrophoneOpusCodecTest {
    @Test fun twentyMillisecondSilentFrameCanBeEncodedWithoutAndroidMediaApis() {
        val encoder = LegacyMicrophoneOpusCodec(48_000)
        try {
            assertTrue(encoder.available)
            assertTrue(encoder.encode(ByteArray(960 * 2)).single().isNotEmpty())
        } finally {
            encoder.close()
        }
    }
}
