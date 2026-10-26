package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.os.Build

/** USB host calls absent from Android 4.2, kept behind a single version boundary. */
internal object UsbApiCompatibility {
    // USB 2.0 SET_INTERFACE: host-to-device, standard request, interface recipient.
    internal const val SET_INTERFACE_REQUEST_TYPE = 0x01
    internal const val SET_INTERFACE_REQUEST = 0x0b
    internal const val NCM_DATA_ALTERNATE = 1
    private const val USB_SETUP_TIMEOUT_MS = 1_000

    /** bAlternateSetting was not exposed by the Android USB Java API before API 21. */
    fun alternateSetting(intf: UsbInterface, oldApiAssumption: Int = 0): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) intf.alternateSetting
        else oldApiAssumption

    /** Before API 21 the app cannot reliably identify alternate 1; select it explicitly. */
    fun selectNcmDataAlternate(connection: UsbDeviceConnection, data: UsbInterface): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            connection.setInterface(data)
        } else {
            connection.controlTransfer(
                SET_INTERFACE_REQUEST_TYPE,
                SET_INTERFACE_REQUEST,
                NCM_DATA_ALTERNATE,
                data.id,
                null,
                0,
                USB_SETUP_TIMEOUT_MS,
            ) == 0
        }
}
