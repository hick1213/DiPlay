package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.Build

/**
 * Finds the NCM control/data interface pair inside an active iPhone configuration.
 *
 * LIVI claims the control interface, claims the data interface, and selects data alternate
 * setting 1, the setting that carries the bulk endpoints. This class only reads descriptors.
 */
object NcmFunctionDiscovery {
    const val CONTROL_CLASS = 0x02
    const val CONTROL_SUBCLASS = 0x0d
    const val DATA_CLASS = 0x0a
    const val APPLE_ETHERNET_CLASS = 0xff
    const val APPLE_ETHERNET_SUBCLASS = 0xfd
    const val APPLE_ETHERNET_PROTOCOL = 0x01
    const val DATA_ALTERNATE_SETTING = 1

    data class NcmFunction(
        val control: UsbInterface,
        val data: UsbInterface,
        val statusIn: UsbEndpoint?,
        val bulkIn: UsbEndpoint,
        val bulkOut: UsbEndpoint,
    )

    fun find(configuration: UsbConfiguration): NcmFunction? = findCdcNcm(interfaces(configuration))

    /** API 17 only exposes interfaces of the active USB configuration. */
    fun find(device: UsbDevice, connection: UsbDeviceConnection? = null): NcmFunction? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            IphoneCarPlayConfiguration.find(device)?.let(::find)
        } else {
            val interfaces = (0 until device.interfaceCount).map(device::getInterface)
            findCdcNcm(interfaces) ?: connection?.let { findLegacyAlternate(interfaces, it) }
        }

    private fun findLegacyAlternate(
        interfaces: List<UsbInterface>, connection: UsbDeviceConnection,
    ): NcmFunction? {
        val control = interfaces.firstOrNull {
            it.interfaceClass == CONTROL_CLASS && it.interfaceSubclass == CONTROL_SUBCLASS
        } ?: return null
        val alternate = LegacyUsbDescriptorReader.readNcmDataAlternate(connection) ?: return null
        // Some 4.2 USB stacks only expose alternate zero. The NCM control and data descriptors
        // can share the same interface number, so claim that one after selecting alternate one.
        val data = interfaces.firstOrNull {
            it.id == alternate.interfaceId && it.interfaceClass == DATA_CLASS
        } ?: control.takeIf { it.id == alternate.interfaceId } ?: return null
        val statusIn = (0 until control.endpointCount).map(control::getEndpoint).singleOrNull {
            it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_INT
        }
        return NcmFunction(
            control = control,
            data = data,
            statusIn = statusIn,
            bulkIn = alternate.bulkIn!!.asAndroidEndpoint(),
            bulkOut = alternate.bulkOut!!.asAndroidEndpoint(),
        )
    }

    private fun findCdcNcm(interfaces: List<UsbInterface>): NcmFunction? {
        val control = interfaces.firstOrNull {
            it.interfaceClass == CONTROL_CLASS && it.interfaceSubclass == CONTROL_SUBCLASS
        } ?: return null
        val data = interfaces
            .filter { it.interfaceClass == DATA_CLASS && bulkEndpoints(it) != null }
            .minByOrNull { if (UsbApiCompatibility.alternateSetting(it, DATA_ALTERNATE_SETTING) ==
                DATA_ALTERNATE_SETTING) 0 else 1 }
            ?: return null
        val endpoints = bulkEndpoints(data) ?: return null
        val statusIn = (0 until control.endpointCount)
            .map(control::getEndpoint)
            .singleOrNull {
                it.direction == UsbConstants.USB_DIR_IN &&
                    it.type == UsbConstants.USB_ENDPOINT_XFER_INT
            }
        return NcmFunction(control, data, statusIn, endpoints.first, endpoints.second)
    }

    private fun interfaces(configuration: UsbConfiguration): List<UsbInterface> =
        (0 until configuration.interfaceCount).map(configuration::getInterface)

    private fun bulkEndpoints(usbInterface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        val endpoints = (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint)
        val input = endpoints.singleOrNull {
            it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        val output = endpoints.singleOrNull {
            it.direction == UsbConstants.USB_DIR_OUT && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        return if (input != null && output != null) input to output else null
    }
}
