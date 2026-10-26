package com.shilapi.xcertplay.legacy

import android.content.Context
import android.util.Base64
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.transport.LockdownPairRecord
import java.io.File
import java.security.MessageDigest

/** Small private preferences store, independent of API 21's noBackupFilesDir. */
internal class LegacyState(private val context: Context) {
    private val prefs = context.getSharedPreferences("legacy-carplay", Context.MODE_PRIVATE)

    fun identity(): AirPlayIdentity {
        val secret = decode(prefs.getString("identity-private", null))
        val public = decode(prefs.getString("identity-public", null))
        val pairingId = prefs.getString("identity-pairing-id", null)
        if (secret?.size == 32 && public?.size == 32 && !pairingId.isNullOrBlank()) {
            return AirPlayIdentity(secret, public, pairingId)
        }
        return AirPlayIdentity.generate().also {
            prefs.edit()
                .putString("identity-private", encode(it.privateKey))
                .putString("identity-public", encode(it.publicKey))
                .putString("identity-pairing-id", it.pairingId)
                .commit()
        }
    }

    fun pairings(): PairingStore = PairingStore { identifier, key ->
        prefs.edit().putString("airplay-pair:$identifier", encode(key)).commit()
    }.also { store ->
        prefs.all.forEach { (name, value) ->
            if (name.startsWith("airplay-pair:") && value is String) {
                decode(value)?.let { store.save(name.removePrefix("airplay-pair:"), it) }
            }
        }
    }

    fun pairRecord(): LockdownPairRecord? = runCatching {
        val fields = PAIR_BYTES.map { decode(prefs.getString("lockdown-$it", null)) ?: return null }
        LockdownPairRecord.restore(
            hostId = prefs.getString("lockdown-host-id", null) ?: return null,
            systemBuid = prefs.getString("lockdown-system-buid", null) ?: return null,
            wifiMacAddress = prefs.getString("lockdown-wifi-mac", null) ?: return null,
            devicePublicKeyPem = fields[0],
            deviceCertificatePem = fields[1],
            hostPrivateKeyPem = fields[2],
            hostCertificatePem = fields[3],
            rootPrivateKeyPem = fields[4],
            rootCertificatePem = fields[5],
        )
    }.getOrNull()

    fun savePairRecord(record: LockdownPairRecord) {
        val bytes = listOf(record.devicePublicKeyPem, record.deviceCertificatePem,
            record.hostPrivateKeyPem, record.hostCertificatePem,
            record.rootPrivateKeyPem, record.rootCertificatePem)
        val editor = prefs.edit()
            .putString("lockdown-host-id", record.hostId)
            .putString("lockdown-system-buid", record.systemBuid)
            .putString("lockdown-wifi-mac", record.wifiMacAddress)
        PAIR_BYTES.zip(bytes).forEach { (name, value) -> editor.putString("lockdown-$name", encode(value)) }
        editor.commit()
    }

    /** Uses only caller-supplied APK assets, never downloads or generates an accessory identity. */
    fun ensureLocalAuthentication(): File {
        val target = File(context.filesDir, LocalMfiAuthenticationClient.DIRECTORY)
        if (!listOf("identity.pk8", "certificate.p7b").all { File(target, it).isFile }) {
            check(target.isDirectory || target.mkdirs()) { "Cannot create private MFi directory" }
            for (name in listOf("identity.pk8", "certificate.p7b")) {
                val file = File(target, name)
                if (file.isFile) continue
                context.assets.open("offline-mfi/$name").use { input ->
                    File(target, "$name.tmp").also { temp ->
                        try {
                            temp.outputStream().use(input::copyTo)
                            check(temp.renameTo(file)) { "Cannot save private MFi asset $name" }
                        } finally {
                            temp.delete()
                        }
                    }
                }
            }
        }
        LocalMfiAuthenticationClient.load(target)
        return target.canonicalFile
    }

    companion object {
        private val PAIR_BYTES = listOf("device-key", "device-cert", "host-key", "host-cert", "root-key", "root-cert")
        private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
        private fun decode(value: String?): ByteArray? =
            value?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }

        fun deviceAddress(publicKey: ByteArray): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(publicKey).copyOfRange(0, 6)
            bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
            return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
        }
    }
}
