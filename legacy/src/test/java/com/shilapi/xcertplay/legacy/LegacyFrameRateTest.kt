package com.shilapi.xcertplay.legacy

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyFrameRateTest {
    @Test fun oldHeadUnitDefaultsToThirtyFps() {
        assertEquals(30, LegacyFrameRate.default(17))
    }
    @Test fun newerTestPhonesDefaultToSixty() {
        assertEquals(60, LegacyFrameRate.default(36))
    }
    @Test fun everySelectableFrameRateIsSupported() {
        LegacyFrameRate.OPTIONS.forEach { assertEquals(it, LegacyFrameRate.fromSaved(it)) }
    }
    @Test fun invalidSavedSettingSafelyFallsBack() {
        assertEquals(30, LegacyFrameRate.fromSaved(0))
        assertEquals(30, LegacyFrameRate.fromSaved(999))
    }
}
