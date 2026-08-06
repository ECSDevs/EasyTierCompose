package cc.ptoe.easytier.compose.transport.root

import android.util.Log

/**
 * Switches the system DNS to the Magic DNS fake IP (100.100.100.101) while the
 * root EasyTier daemon is running, and restores the original values on stop.
 *
 * Android's `settings put global dns1/dns2` is used because Root TUN mode does
 * not go through VpnService (which would set DNS via the platform VPN APIs).
 *
 * The class is idempotent: calling [enableMagicDns] multiple times only saves
 * the original DNS once, and [restore] is safe to call when not enabled.
 */
internal class SystemDnsManager {
    private var enabled = false
    private var savedDns1: String? = null
    private var savedDns2: String? = null

    /** Switches system DNS to [fakeIp], saving the current values for later restore. */
    fun enableMagicDns(fakeIp: String) {
        if (enabled) return
        savedDns1 = readSystemDns("dns1")
        savedDns2 = readSystemDns("dns2")
        Log.i(TAG, "enableMagicDns: saved dns1=$savedDns1 dns2=$savedDns2, setting to $fakeIp")
        writeSystemDns("dns1", fakeIp)
        writeSystemDns("dns2", fakeIp)
        enabled = true
    }

    /** Restores the original system DNS values if [enableMagicDns] was called. */
    fun restore() {
        if (!enabled) return
        Log.i(TAG, "restore: dns1=$savedDns1 dns2=$savedDns2")
        val d1 = savedDns1
        val d2 = savedDns2
        if (d1 != null) writeSystemDns("dns1", d1) else clearSystemDns("dns1")
        if (d2 != null) writeSystemDns("dns2", d2) else clearSystemDns("dns2")
        savedDns1 = null
        savedDns2 = null
        enabled = false
    }

    private fun readSystemDns(key: String): String? = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("settings", "get", "global", key))
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        output.takeIf { it.isNotEmpty() && it != "null" }
    }.getOrNull()

    private fun writeSystemDns(key: String, value: String) {
        runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("settings", "put", "global", key, value))
            process.waitFor()
        }
    }

    private fun clearSystemDns(key: String) {
        runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("settings", "delete", "global", key))
            process.waitFor()
        }
    }

    companion object {
        private const val TAG = "SystemDnsManager"
        /** Magic DNS fake IP used by EasyTier. */
        const val MAGIC_DNS_FAKE_IP = "100.100.100.101"
    }
}
