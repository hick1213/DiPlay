package com.shilapi.xcertplay.legacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyVideoFrameClockTest {
    @Test fun usesIphoneTimingAndPreservesSkippedFrames() {
        val clock = LegacyVideoFrameClock()
        val base = 3_900_000_000_000_000_000L
        assertEquals(0L, clock.next(base))
        assertEquals(16_666L, clock.next(base + 16_666_000L))
        assertEquals(49_999L, clock.next(base + 49_999_000L))
    }

    @Test fun neverGoesBackwardsWhenPhoneClockRepeatsOrMovesBackward() {
        val clock = LegacyVideoFrameClock()
        val base = 3_900_000_000_000_000_000L
        assertEquals(0L, clock.next(base))
        assertEquals(33_333L, clock.next(base + 33_333_000L))
        assertEquals(33_334L, clock.next(base + 33_333_000L))
        assertTrue(clock.next(base - 1L) > 33_334L)
    }

    @Test fun missingTimingFallsBackToThirtyHertzWithoutOverflow() {
        val clock = LegacyVideoFrameClock()
        assertEquals(33_332L, clock.next(0L))
        assertEquals(66_665L, clock.next(0L))
        assertEquals(99_998L, clock.next(0L))
    }
}
