package com.shilapi.xcertplay.legacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyIdentityTest {
    @Test fun deviceAddressIsStableAndLocallyAdministered() {
        val key = ByteArray(32) { it.toByte() }
        val mac = LegacyState.deviceAddress(key)

        assertEquals(mac, LegacyState.deviceAddress(key.copyOf()))
        assertTrue(mac.matches(Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")))
        val first = mac.substring(0, 2).toInt(16)
        assertEquals(0x02, first and 0x03)
    }
}
