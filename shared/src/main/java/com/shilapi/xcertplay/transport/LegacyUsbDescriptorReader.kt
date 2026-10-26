package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.os.Parcel

/** An endpoint in a USB configuration descriptor; no API 21 USB configuration types required. */
internal data class LegacyEndpointDescriptor(
    val address: Int,
    val attributes: Int,
    val maxPacketSize: Int,
    val interval: Int,
) {
    val bulkIn: Boolean get() = attributes and 3 == 2 && address and 0x80 != 0
    val bulkOut: Boolean get() = attributes and 3 == 2 && address and 0x80 == 0

    // Android 4.2's UsbEndpoint constructor is hidden, but its public Parcelable CREATOR uses
    // these same four fields. Creating an endpoint is safe: native bulkTransfer uses its address.
    fun asAndroidEndpoint(): UsbEndpoint {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeInt(address)
            parcel.writeInt(attributes)
            parcel.writeInt(maxPacketSize)
            parcel.writeInt(interval)
            parcel.setDataPosition(0)
            UsbEndpoint.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }
}

internal data class LegacyDataAlternate(
    val interfaceId: Int,
    val alternateSetting: Int,
    val endpoints: List<LegacyEndpointDescriptor>,
) {
    val bulkIn: LegacyEndpointDescriptor? get() = endpoints.singleOrNull { it.bulkIn }
    val bulkOut: LegacyEndpointDescriptor? get() = endpoints.singleOrNull { it.bulkOut }
}

/**
 * Android 4.2 flattens USB interfaces and loses bAlternateSetting. Query the actual USB
 * configuration descriptor to recover CDC-NCM alternate 1 and its two bulk endpoint addresses.
 */
internal object LegacyUsbDescriptorReader {
    private const val USB_DEVICE_IN = 0x80
    private const val GET_DESCRIPTOR = 0x06
    private const val GET_CONFIGURATION = 0x08
    private const val CONFIGURATION_DESCRIPTOR = 0x02
    private const val INTERFACE_DESCRIPTOR = 0x04
    private const val ENDPOINT_DESCRIPTOR = 0x05
    private const val MAX_CONFIG_BYTES = 8 * 1024
    private const val MAX_CONFIGURATIONS = 16
    private const val USB_TIMEOUT_MS = 1_000

    /** Pure parser; malformed descriptors fail closed and never allow an unbounded allocation. */
    fun ncmDataAlternate(bytes: ByteArray): LegacyDataAlternate? {
        if (bytes.size < 9 || u8(bytes[1]) != CONFIGURATION_DESCRIPTOR) return null
        val alternates = mutableListOf<LegacyDataAlternate>()
        var index = 0
        var active: LegacyDataAlternate? = null
        var endpoints = mutableListOf<LegacyEndpointDescriptor>()
        fun flush() {
            active?.let { alternates.add(it.copy(endpoints = endpoints.toList())) }
        }
        while (index + 2 <= bytes.size) {
            val length = u8(bytes[index])
            if (length < 2 || index + length > bytes.size) break
            when (u8(bytes[index + 1])) {
                INTERFACE_DESCRIPTOR -> if (length >= 9) {
                    flush()
                    active = if (u8(bytes[index + 5]) == 0x0a) {
                        LegacyDataAlternate(
                            interfaceId = u8(bytes[index + 2]),
                            alternateSetting = u8(bytes[index + 3]),
                            endpoints = emptyList(),
                        )
                    } else null
                    endpoints = mutableListOf()
                }
                ENDPOINT_DESCRIPTOR -> if (length >= 7 && active != null) {
                    endpoints.add(LegacyEndpointDescriptor(
                        address = u8(bytes[index + 2]),
                        attributes = u8(bytes[index + 3]),
                        maxPacketSize = u8(bytes[index + 4]) or (u8(bytes[index + 5]) shl 8),
                        interval = u8(bytes[index + 6]),
                    ))
                }
            }
            index += length
        }
        flush()
        return alternates.firstOrNull { it.alternateSetting == 1 && it.bulkIn != null && it.bulkOut != null }
    }

    /** Reads the active configuration, which may not be descriptor index zero on iPhones. */
    fun readNcmDataAlternate(connection: UsbDeviceConnection): LegacyDataAlternate? {
        val active = ByteArray(1)
        val readActive = connection.controlTransfer(USB_DEVICE_IN, GET_CONFIGURATION,
            0, 0, active, 1, USB_TIMEOUT_MS)
        if (readActive != 1) return null
        val configValue = u8(active[0])
        val total = (connection.rawDescriptors.getOrNull(17)?.let(::u8) ?: 1)
            .coerceIn(1, MAX_CONFIGURATIONS)
        for (configIndex in 0 until total) {
            val header = ByteArray(9)
            val requested = (CONFIGURATION_DESCRIPTOR shl 8) or configIndex
            if (connection.controlTransfer(USB_DEVICE_IN, GET_DESCRIPTOR, requested,
                    0, header, header.size, USB_TIMEOUT_MS) != header.size) continue
            if (u8(header[1]) != CONFIGURATION_DESCRIPTOR || u8(header[5]) != configValue) continue
            val byteCount = u8(header[2]) or (u8(header[3]) shl 8)
            if (byteCount !in 9..MAX_CONFIG_BYTES) continue
            val bytes = ByteArray(byteCount)
            val received = connection.controlTransfer(USB_DEVICE_IN, GET_DESCRIPTOR, requested,
                0, bytes, byteCount, USB_TIMEOUT_MS)
            if (received >= 9) ncmDataAlternate(bytes.copyOf(received))?.let { return it }
        }
        return null
    }

    private fun u8(value: Byte): Int = value.toInt() and 0xff
}
