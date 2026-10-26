package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LegacyExistingWifiManagerTest {
    @Test fun android42WifiInfoIpv4UsesLittleEndianByteOrder() {
        assertEquals("192.168.31.42", legacyWifiIpv4Address(0x2a1fa8c0)?.hostAddress)
        assertEquals("10.0.0.2", legacyWifiIpv4Address(0x0200000a)?.hostAddress)
    }

    @Test fun unassignedAndUnusableWifiIpv4AddressesAreRejected() {
        assertNull(legacyWifiIpv4Address(0))
        assertNull(legacyWifiIpv4Address(0x0100007f)) // 127.0.0.1
        assertNull(legacyWifiIpv4Address(0x0100fea9)) // 169.254.0.1
    }
}
