package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.Build
import android.util.Log

/** The USB interface fields Android 4.2 exposes, without API 21's UsbConfiguration. */
internal data class LegacyUsbInterfaceSignature(val kind: Int, val subclass: Int, val protocol: Int)

internal data class LegacyCarPlayUsbMode(
    val usbMuxIndex: Int?,
    val hasNcm: Boolean,
    val hasAppleEthernet: Boolean,
) {
    val isUsable: Boolean get() = usbMuxIndex != null && hasNcm
}

/**
 * Descriptor-based discovery of the iPhone's CarPlay configuration.
 *
 * Configuration ids differ between iPhone models, so the configuration is identified by its
 * interfaces: Apple USB Multiplexor (USBMUX) plus the NCM/Ethernet function CarPlay uses.
 */
object IphoneCarPlayConfiguration {
    const val TAG = "xcertplay-usb"

    private const val USBMUX_CLASS = 0xff
    private const val USBMUX_SUBCLASS = 0xfe
    private const val USBMUX_PROTOCOL = 0x02
    private const val APPLE_ETHERNET_CLASS = 0xff
    private const val APPLE_ETHERNET_SUBCLASS = 0xfd
    private const val APPLE_ETHERNET_PROTOCOL = 0x01
    private const val NCM_CONTROL_CLASS = 0x02
    private const val NCM_CONTROL_SUBCLASS = 0x0d
    private const val PREFERRED_USBMUX_OUT = 0x04
    private const val PREFERRED_USBMUX_IN = 0x85

    /** API 17 can enumerate the active interfaces but cannot enumerate USB configurations. */
    internal fun classifyLegacyInterfaces(interfaces: List<LegacyUsbInterfaceSignature>): LegacyCarPlayUsbMode =
        LegacyCarPlayUsbMode(
            usbMuxIndex = interfaces.indexOfFirst {
                it.kind == USBMUX_CLASS && it.subclass == USBMUX_SUBCLASS && it.protocol == USBMUX_PROTOCOL
            }.takeIf { it >= 0 },
            hasNcm = interfaces.any { it.kind == NCM_CONTROL_CLASS && it.subclass == NCM_CONTROL_SUBCLASS },
            hasAppleEthernet = interfaces.any {
                it.kind == APPLE_ETHERNET_CLASS && it.subclass == APPLE_ETHERNET_SUBCLASS &&
                    it.protocol == APPLE_ETHERNET_PROTOCOL
            },
        )

    private fun activeLegacyMode(device: UsbDevice): LegacyCarPlayUsbMode = classifyLegacyInterfaces(
        (0 until device.interfaceCount).map { index ->
            device.getInterface(index).let {
                LegacyUsbInterfaceSignature(it.interfaceClass, it.interfaceSubclass, it.interfaceProtocol)
            }
        },
    )

    fun isLegacyCarPlayMode(device: UsbDevice): Boolean = activeLegacyMode(device).isUsable

    fun legacyUsbMuxInterface(device: UsbDevice): UsbInterface? =
        activeLegacyMode(device).takeIf { it.isUsable }?.usbMuxIndex?.let(device::getInterface)

    fun find(device: UsbDevice): UsbConfiguration? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
        val configurations = (0 until device.configurationCount).map(device::getConfiguration)
        val chosen = configurations.firstOrNull { usbMuxInterface(it) != null && hasCdcNcm(it) && hasAppleEthernet(it) }
            ?: configurations.firstOrNull { usbMuxInterface(it) != null && hasCdcNcm(it) }
        Log.i(
            TAG,
            "carplay config chosen=${chosen?.id} " +
                "available=${configurations.map { it.id }} detail=${chosen?.let(::describe)}",
        )
        return chosen
    }

    /** Only CarPlay mode adds Apple Ethernet; iOS 17+ offers USBMUX and NCM without it (#634). */
    fun isCarPlayMode(configuration: UsbConfiguration): Boolean =
        usbMuxInterface(configuration) != null && hasCdcNcm(configuration) && hasAppleEthernet(configuration)

    fun describe(configuration: UsbConfiguration): String =
        (0 until configuration.interfaceCount).joinToString(",") { index ->
            val usbInterface = configuration.getInterface(index)
            "${usbInterface.id}/${usbInterface.alternateSetting}" +
                ":${usbInterface.interfaceClass.toString(16)}" +
                ".${usbInterface.interfaceSubclass.toString(16)}" +
                ".${usbInterface.interfaceProtocol.toString(16)}" +
                "x${usbInterface.endpointCount}"
        }

    fun usbMuxInterface(configuration: UsbConfiguration): UsbInterface? =
        (0 until configuration.interfaceCount).map(configuration::getInterface).firstOrNull {
            it.interfaceClass == USBMUX_CLASS &&
                it.interfaceSubclass == USBMUX_SUBCLASS &&
                it.interfaceProtocol == USBMUX_PROTOCOL
        }

    fun usbMuxEndpoints(usbInterface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        val endpoints = (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint)
        val out = endpoints.firstOrNull {
            it.address == PREFERRED_USBMUX_OUT &&
                it.direction == UsbConstants.USB_DIR_OUT &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        } ?: endpoints.singleOrNull {
            it.direction == UsbConstants.USB_DIR_OUT &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        val input = endpoints.firstOrNull {
            it.address == PREFERRED_USBMUX_IN &&
                it.direction == UsbConstants.USB_DIR_IN &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        } ?: endpoints.singleOrNull {
            it.direction == UsbConstants.USB_DIR_IN &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        return if (out != null && input != null) out to input else null
    }

    private fun hasCdcNcm(configuration: UsbConfiguration): Boolean =
        (0 until configuration.interfaceCount).map(configuration::getInterface).any {
            it.interfaceClass == NCM_CONTROL_CLASS && it.interfaceSubclass == NCM_CONTROL_SUBCLASS
        }

    private fun hasAppleEthernet(configuration: UsbConfiguration): Boolean =
        (0 until configuration.interfaceCount).map(configuration::getInterface).any {
            it.interfaceClass == APPLE_ETHERNET_CLASS &&
                it.interfaceSubclass == APPLE_ETHERNET_SUBCLASS &&
                it.interfaceProtocol == APPLE_ETHERNET_PROTOCOL
        }
}
