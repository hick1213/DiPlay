package com.shilapi.xcertplay.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.Looper
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/**
 * Android 4.2–4.4 implementation of same-LAN CarPlay.
 *
 * Network / LinkProperties / NetworkCallback are API 21; Jelly Bean must identify the
 * currently associated station through WifiInfo and the interface that owns its IPv4 address.
 * It never joins Wi-Fi or changes the route. Modern Android keeps using ExistingWifiManager.
 */
class LegacyExistingWifiManager(
    context: Context,
    private val ssid: String,
    private val passphrase: String,
    private val onDiagnostic: (String) -> Unit = {},
    private val onNetworkChanged: () -> Unit = {},
) : WirelessHotspotManager {
    private val app = context.applicationContext
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        ?: throw IOException("Android Wi-Fi service is unavailable")
    @Volatile private var closed = false
    private var registered = false
    private var selectedInterface: String? = null
    private var selectedAddress: InetAddress? = null

    init {
        require(ManualHotspotValidation.error(ssid, passphrase) == null) {
            "Invalid same-Wi-Fi SSID or password"
        }
    }

    private data class Link(
        val name: String,
        val address: Inet4Address,
        val addresses: List<InetAddress>,
        val bssid: ByteArray?,
    )

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val name = selectedInterface ?: return
            val address = selectedAddress ?: return
            if (closed) return
            val current = readLink()
            if (current?.name != name || current.address != address) {
                onDiagnostic("Android 4.2 same-Wi-Fi interface/address changed")
                onNetworkChanged()
            }
        }
    }

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "LegacyExistingWifiManager.start must run on a background thread"
        }
        val started = System.nanoTime()
        while (!closed) {
            val link = readLink()
            if (link != null) {
                selectedInterface = link.name
                selectedAddress = link.address
                val filter = IntentFilter().apply {
                    addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                    addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                }
                app.registerReceiver(receiver, filter)
                registered = true
                if (readLink()?.address != link.address) {
                    close()
                    throw IOException("Same-Wi-Fi connection changed during startup")
                }
                onDiagnostic("Android 4.2 same-Wi-Fi iface=${link.name} host=${link.address.hostAddress} channel=auto")
                return WirelessHotspotInfo(
                    ssid = ssid,
                    passphrase = passphrase,
                    security = if (passphrase.isEmpty()) Iap2WirelessSecurity.NONE else Iap2WirelessSecurity.WPA_WPA2,
                    channel = 0,
                    frequencyMHz = null,
                    bssid = null,
                    interfaceName = link.name,
                    hostAddress = link.address,
                    hostAddresses = link.addresses,
                    bandLabel = "Auto",
                    backend = WirelessHotspotBackend.EXISTING_WIFI,
                    accessPointBssid = link.bssid,
                )
            }
            if ((System.nanoTime() - started) / 1_000_000 >= timeoutMillis) {
                throw IOException("车机未连接指定 Wi-Fi，或没有有效的 IPv4 地址")
            }
            Thread.sleep(200)
        }
        throw IOException("Same-Wi-Fi connection was cancelled")
    }

    @Suppress("DEPRECATION")
    private fun readLink(): Link? = runCatching {
        if (!wifi.isWifiEnabled) return null
        val info = wifi.connectionInfo ?: return null
        if (info.supplicantState != SupplicantState.COMPLETED) return null
        val connectedSsid = info.ssid?.removeSurrounding("\"")
        if (connectedSsid.isNullOrBlank() || connectedSsid == WifiManager.UNKNOWN_SSID ||
            connectedSsid != ssid) return null
        val ip = legacyWifiIpv4Address(info.ipAddress) ?: return null
        val iface = Collections.list(NetworkInterface.getNetworkInterfaces()).firstOrNull { candidate ->
            candidate.isUp && !candidate.isLoopback &&
                Collections.list(candidate.inetAddresses).any { it == ip }
        } ?: return null
        val candidates = existingWifiHostAddresses(Collections.list(iface.inetAddresses), iface.index)
        val bssid = info.bssid?.takeIf {
            it.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) &&
                it != "02:00:00:00:00:00"
        }?.split(':')?.map { it.toInt(16).toByte() }?.toByteArray()
        Link(iface.name, ip, candidates.ifEmpty { listOf(ip) }, bssid)
    }.getOrNull()

    override fun validateReady() {
        val current = readLink()
        if (closed || current?.name != selectedInterface || current?.address != selectedAddress) {
            throw IOException("Same-Wi-Fi interface or address is no longer available")
        }
    }

    override fun connectionDiagnosticSnapshot(): String =
        "legacySameWifi=${if (!closed && readLink() != null) "attached" else "lost"}"

    override fun close() {
        closed = true
        if (registered) {
            registered = false
            runCatching { app.unregisterReceiver(receiver) }
        }
    }
}

/** Jelly Bean WifiInfo.ipAddress is a little-endian IPv4 integer, or zero if unassigned. */
internal fun legacyWifiIpv4Address(raw: Int): Inet4Address? {
    if (raw == 0) return null
    val bytes = byteArrayOf(raw.toByte(), (raw ushr 8).toByte(),
        (raw ushr 16).toByte(), (raw ushr 24).toByte())
    val ip = InetAddress.getByAddress(bytes)
    return (ip as? Inet4Address)?.takeUnless {
        it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isMulticastAddress
    }
}
