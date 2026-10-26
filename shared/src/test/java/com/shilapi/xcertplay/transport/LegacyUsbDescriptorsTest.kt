package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyUsbDescriptorsTest {
    @Test fun android42SelectsCarPlayFromTheActiveInterfaces() {
        val interfaces = listOf(
            LegacyUsbInterfaceSignature(0xff, 0xfe, 0x02), // Apple USBMUX
            LegacyUsbInterfaceSignature(0x02, 0x0d, 0x00), // CDC NCM control
            LegacyUsbInterfaceSignature(0x0a, 0x00, 0x00), // CDC NCM data
        )

        val mode = IphoneCarPlayConfiguration.classifyLegacyInterfaces(interfaces)

        assertEquals(0, mode.usbMuxIndex)
        assertTrue(mode.hasNcm)
        // Newer iOS releases do not always expose the old Apple Ethernet interface.
        assertFalse(mode.hasAppleEthernet)
        assertTrue(mode.isUsable)
    }

    @Test fun android42RejectsChargingOnlyUsbInterfaces() {
        val interfaces = listOf(
            LegacyUsbInterfaceSignature(0x06, 0x01, 0x01),
            LegacyUsbInterfaceSignature(0xff, 0xfe, 0x02),
        )

        val mode = IphoneCarPlayConfiguration.classifyLegacyInterfaces(interfaces)

        assertEquals(1, mode.usbMuxIndex)
        assertFalse(mode.hasNcm)
        assertFalse(mode.isUsable)
    }

    @Test fun android42DoesNotMistakeRandomVendorInterfaceForUsbmux() {
        val mode = IphoneCarPlayConfiguration.classifyLegacyInterfaces(
            listOf(LegacyUsbInterfaceSignature(0xff, 0xfd, 0x01), LegacyUsbInterfaceSignature(0x02, 0x0d, 0x00)),
        )
        assertEquals(null, mode.usbMuxIndex)
        assertFalse(mode.isUsable)
    }

    @Test fun android42RecoversNcmAlternateOneBulkEndpoints() {
        // AOSP 4.2 reports only interface id/class, omitting bAlternateSetting.
        // The second data descriptor is the one with the usable bulk pipes.
        val bytes = byteArrayOf(
            9, 2, 41, 0, 1, 6, 0, -128, 50,
            9, 4, 3, 0, 0, 10, 0, 0, 0,
            9, 4, 3, 1, 2, 10, 0, 0, 0,
            7, 5, -126, 2, 0, 2, 0,
            7, 5, 3, 2, 0, 2, 0,
        )

        val data = LegacyUsbDescriptorReader.ncmDataAlternate(bytes)

        assertEquals(3, data?.interfaceId)
        assertEquals(1, data?.alternateSetting)
        assertEquals(0x82, data?.bulkIn?.address)
        assertEquals(0x03, data?.bulkOut?.address)
        assertEquals(512, data?.bulkIn?.maxPacketSize)
    }

    @Test fun android42RejectsMalformedOrIncompleteNcmDescriptors() {
        val noBulkOut = byteArrayOf(
            9, 2, 25, 0, 1, 6, 0, -128, 50,
            9, 4, 3, 1, 1, 10, 0, 0, 0,
            7, 5, -126, 2, 0, 2, 0,
        )
        assertNull(LegacyUsbDescriptorReader.ncmDataAlternate(noBulkOut))
        assertNull(LegacyUsbDescriptorReader.ncmDataAlternate(byteArrayOf(9, 2, 41, 0, 1, 6, 0, -128, 50, 0, 4)))
        assertNull(LegacyUsbDescriptorReader.ncmDataAlternate(byteArrayOf()))
    }

    @Test fun android42UsesUsbStandardSetInterfaceRequest() {
        assertEquals(0x01, UsbApiCompatibility.SET_INTERFACE_REQUEST_TYPE)
        assertEquals(0x0b, UsbApiCompatibility.SET_INTERFACE_REQUEST)
        assertEquals(1, UsbApiCompatibility.NCM_DATA_ALTERNATE)
    }
}
