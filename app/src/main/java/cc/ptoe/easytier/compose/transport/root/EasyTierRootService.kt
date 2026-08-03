package cc.ptoe.easytier.compose.transport.root

import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import cc.ptoe.easytier.compose.core.EasyTierJni
import cc.ptoe.easytier.compose.core.networkInfo
import cc.ptoe.easytier.compose.data.RuntimePeer
import cc.ptoe.easytier.compose.data.RuntimeState
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicReference

class EasyTierRootService : RootService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val currentStatus = AtomicReference(RootRuntimeStatus(RuntimeState.STOPPED.name, null, null, null))
    private var tunDescriptor: ParcelFileDescriptor? = null
    private var pollJob: Job? = null
    private var statusJob: Job? = null
    private var magicDnsEnabled = false
    private var savedDns1: String? = null
    private var savedDns2: String? = null
    // Tracks ip rule priorities we installed so stopRoot/failRoot can remove them.
    // Android's per-interface routing tables (wlan0, rmnet_data*, etc.) are queried
    // via ip rule before the main table, so a connected route in main (e.g.
    // 10.126.126.0/24 dev easytier0) is bypassed for traffic from root processes.
    // We insert high-priority "to <cidr> lookup main" rules to fix this.
    private val installedRules = mutableListOf<Int>()

    companion object {
        private const val TAG = "EasyTierRootService"
        private const val MAGIC_DNS_FAKE_IP = "100.100.100.101"
        private const val MAGIC_DNS_ROUTE = "100.100.100.101/32"
        // Priority for our ip rules. Android's per-interface rules start at 10000;
        // using 5000 places our TUN rules ahead of them so TUN routes win.
        private const val IP_RULE_PRIORITY_START = 5000
    }

    private val binder = object : IEasyTierRootService.Stub() {
        override fun start(profileId: String, toml: String, spec: RootTunSpec) {
            scope.launch { startRoot(profileId, toml, spec) }
        }

        override fun stop() {
            scope.launch { stopRoot() }
        }

        override fun getStatus(): RootRuntimeStatus = currentStatus.get()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        scope.launch { stopRoot() }
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun startRoot(profileId: String, toml: String, spec: RootTunSpec) {
        Log.i(TAG, "startRoot: profileId=$profileId ipv4Cidr=${spec.ipv4Cidr} mtu=${spec.mtu} manualRoutes=${spec.manualRoutes} proxyCidrs=${spec.proxyCidrs} magicDns=${spec.magicDns}")
        currentStatus.set(RootRuntimeStatus(RuntimeState.STARTING.name, null, null, null))
        runCatching {
            require(EasyTierJni.parseConfig(toml) == 0) { nativeError("EasyTier rejected configuration") }
            stopRoot()
            magicDnsEnabled = spec.magicDns
            currentStatus.set(RootRuntimeStatus(RuntimeState.STARTING.name, null, null, null))
            require(EasyTierJni.runNetworkInstance(toml) == 0) { nativeError("EasyTier failed to start") }
            if (spec.magicDns) enableMagicDnsSystemDns()
            if (!spec.ipv4Cidr.isNullOrBlank()) {
                Log.i(TAG, "startRoot: using static IPv4 ${spec.ipv4Cidr}")
                attachTun(profileId, spec.ipv4Cidr, spec)
            } else {
                Log.i(TAG, "startRoot: DHCP mode, polling for virtual IPv4")
                pollJob = scope.launch { pollDhcp(profileId, spec) }
            }
        }.onFailure {
            Log.e(TAG, "startRoot failed", it)
            failRoot(it.message ?: nativeError("Root EasyTier start failed"))
        }
    }

    private suspend fun pollDhcp(profileId: String, spec: RootTunSpec) {
        while (currentStatus.get().state == RuntimeState.STARTING.name) {
            runCatching {
                val raw = EasyTierJni.collectNetworkInfos(1)
                val info = raw?.networkInfo(profileId)
                if (info?.error?.takeIf(String::isNotBlank) != null) {
                    Log.e(TAG, "pollDhcp: EasyTier error: ${info.error}")
                    error(info.error)
                }
                val cidr = info?.virtualIpv4
                if (!cidr.isNullOrBlank()) {
                    Log.i(TAG, "pollDhcp: got virtual IPv4 $cidr, routes=${info.routes}")
                    attachTun(profileId, cidr, spec, info.routes)
                }
            }.onFailure {
                Log.e(TAG, "pollDhcp failed", it)
                failRoot(it.message ?: nativeError("Root DHCP polling failed"))
            }
            delay(2_000)
        }
    }

    private fun attachTun(profileId: String, cidr: String, spec: RootTunSpec, runtimeRoutes: List<String> = emptyList()) {
        val devName = spec.devName.ifBlank { "easytier0" }
        Log.i(TAG, "attachTun: profileId=$profileId cidr=$cidr mtu=${spec.mtu} devName=$devName")
        val fd = RootTunNative.create(cidr, spec.mtu, devName)
        tunDescriptor = ParcelFileDescriptor.adoptFd(fd)
        require(EasyTierJni.setTunFd(profileId, tunDescriptor!!.fd) == 0) { nativeError("EasyTier failed to attach root TUN") }
        syncTunRoutes(cidr, runtimeRoutes, spec)
        currentStatus.set(RootRuntimeStatus(RuntimeState.RUNNING.name, cidr, devName, null))
        Log.i(TAG, "attachTun: running, virtualIpv4=$cidr dev=$devName")
        statusJob = scope.launch { pollStatus(profileId, cidr, spec) }
    }

    /**
     * Builds the full TUN route set and syncs it to the kernel.
     *
     * Routes include:
     *  - The virtual IP subnet (e.g. 10.126.126.0/24 from 10.126.126.1/24). The
     *    kernel already creates a connected route for this via RTM_NEWADDR, but
     *    we add an explicit ip rule for it (see installTunRules) so Android's
     *    per-interface routing tables don't bypass it.
     *  - Remote peer proxy_cidrs (from collectNetworkInfos routes) — the remote
     *    networks peers advertise.
     *  - Manual routes from the profile config.
     *  - Magic DNS route (100.100.100.101/32) when enabled.
     *
     * Note: spec.proxyCidrs (local [[proxy_network]] entries) are intentionally
     * NOT added — those are networks this node advertises to peers, not networks
     * we route through the TUN.
     */
    private fun syncTunRoutes(cidr: String, runtimeRoutes: List<String>, spec: RootTunSpec) {
        val subnet = virtualIpSubnet(cidr)
        val magicDnsRoute = if (spec.magicDns) listOf(MAGIC_DNS_ROUTE) else emptyList()
        val allRoutes = (listOfNotNull(subnet) + runtimeRoutes + spec.manualRoutes + magicDnsRoute)
            .filter(String::isNotBlank).distinct().sorted()
        Log.i(TAG, "syncTunRoutes: ${allRoutes.size} routes: $allRoutes")
        runCatching { RootTunNative.syncRoutes(allRoutes.toTypedArray()) }
            .onFailure { Log.w(TAG, "syncTunRoutes: partial failure (non-fatal)", it) }
        // Install ip rule entries for each TUN route so Android's per-interface
        // routing tables (wlan0/rmnet_data*) don't intercept traffic destined
        // for the TUN. Without this, ip route get <peer_ip> returns the wlan0
        // default route because the wlan0 table is queried before main.
        installTunRules(allRoutes)
    }

    /** Computes the network subnet CIDR from an IPv4 CIDR, e.g. "10.126.126.1/24" → "10.126.126.0/24". */
    private fun virtualIpSubnet(cidr: String): String? {
        val slash = cidr.indexOf('/')
        if (slash < 0) return null
        val ip = cidr.substring(0, slash)
        val prefix = cidr.substring(slash + 1).toIntOrNull() ?: return null
        if (prefix !in 0..32) return null
        val octets = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4) return null
        var addr = ((octets[0] and 0xFF) shl 24) or ((octets[1] and 0xFF) shl 16) or
            ((octets[2] and 0xFF) shl 8) or (octets[3] and 0xFF)
        val mask = if (prefix == 0) 0 else ((-1 shl (32 - prefix)))
        addr = addr and mask
        return "${(addr shr 24) and 0xFF}.${(addr shr 16) and 0xFF}.${(addr shr 8) and 0xFF}.${addr and 0xFF}/$prefix"
    }

    /**
     * Installs `ip rule add to <cidr> lookup main` entries with priority 5000
     * (ahead of Android's per-interface rules at 10000+). Idempotent: only adds
     * rules for CIDRs not already installed. Tracks priorities for cleanup.
     */
    private fun installTunRules(cidrs: List<String>) {
        val wanted = cidrs.filter(String::isNotBlank).distinct().sorted()
        // Remove rules whose CIDR is no longer wanted.
        val removedCidrs = currentRuleCidrs() - wanted.toSet()
        for (cidr in removedCidrs) removeRuleForCidr(cidr)
        // Add rules for new CIDRs. Each gets a unique priority starting at 5000.
        val existing = currentRuleCidrs()
        var nextPriority = IP_RULE_PRIORITY_START + installedRules.size
        for (cidr in wanted) {
            if (cidr in existing) continue
            val priority = nextPriority++
            runCatching { execRoot("ip rule add to $cidr lookup main priority $priority") }
                .onFailure { Log.w(TAG, "installTunRules: add rule for $cidr failed: ${it.message}") }
                .onSuccess {
                    installedRules.add(priority)
                    Log.i(TAG, "installTunRules: added rule priority=$priority to $cidr lookup main")
                }
        }
    }

    /** Returns the set of CIDRs that currently have an ip rule installed by us. */
    private fun currentRuleCidrs(): Set<String> = runCatching {
        val output = execRoot("ip rule show") ?: return@runCatching emptySet()
        output.lineSequence()
            .mapNotNull { line ->
                // Lines look like: "5000:	from all to 10.126.126.0/24 lookup main"
                val toMatch = Regex("""to\s+(\S+)""").find(line)?.groupValues?.getOrNull(1)
                val isMain = line.contains("lookup main")
                if (toMatch != null && isMain) toMatch else null
            }
            .toSet()
    }.getOrDefault(emptySet())

    private fun removeRuleForCidr(cidr: String) {
        // Find the priority for this CIDR from ip rule show, then delete by priority.
        runCatching {
            val output = execRoot("ip rule show") ?: return@runCatching
            val priorityLine = output.lineSequence().firstOrNull { line ->
                line.contains("to $cidr") && line.contains("lookup main")
            } ?: return@runCatching
            val priority = priorityLine.substringBefore(':').trim().toIntOrNull() ?: return@runCatching
            execRoot("ip rule del priority $priority")
            installedRules.remove(priority)
            Log.i(TAG, "removeRuleForCidr: removed priority=$priority for $cidr")
        }
    }

    private fun removeAllTunRules() {
        if (installedRules.isEmpty()) return
        // Re-read ip rule show and delete all rules in our priority range that
        // point to main, to be robust against any drift in installedRules state.
        runCatching {
            val output = execRoot("ip rule show") ?: return@runCatching
            val priorities = output.lineSequence()
                .filter { line ->
                    val p = line.substringBefore(':').trim().toIntOrNull() ?: return@filter false
                    p in IP_RULE_PRIORITY_START until (IP_RULE_PRIORITY_START + 10000) && line.contains("lookup main")
                }
                .mapNotNull { it.substringBefore(':').trim().toIntOrNull() }
                .toList()
            for (p in priorities) {
                execRoot("ip rule del priority $p")
                Log.i(TAG, "removeAllTunRules: removed priority=$p")
            }
        }
        installedRules.clear()
    }

    private fun execRoot(command: String): String? = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        val output = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()
        val code = process.waitFor()
        if (code != 0) {
            Log.w(TAG, "execRoot: '$command' exit=$code err=${error.trim()}")
        }
        output
    }.getOrNull()

    private suspend fun pollStatus(profileId: String, cidr: String, spec: RootTunSpec) {
        var lastRoutes: List<String> = emptyList()
        while (currentStatus.get().state == RuntimeState.RUNNING.name) {
            var shouldFail = false
            var failMessage: String? = null
            runCatching {
                val raw = EasyTierJni.collectNetworkInfos(1)
                val info = raw?.networkInfo(profileId) ?: run {
                    Log.w(TAG, "pollStatus: networkInfo returned null for $profileId")
                    return@runCatching
                }
                if (info.error?.takeIf(String::isNotBlank) != null) {
                    Log.e(TAG, "pollStatus: EasyTier error: ${info.error}")
                    shouldFail = true
                    failMessage = info.error
                    return@runCatching
                }
                val peerIps = info.peers.mapNotNull { it.virtualIpv4 }
                Log.i(TAG, "pollStatus: peers=${info.peers.size} peerIps=$peerIps routes=${info.routes.size} routes=${info.routes}")
                // Continuously refresh TUN routes so newly-connected peers' proxy_cidrs
                // are added and disconnected peers' are removed. Skip the native call
                // when routes have not changed to reduce log noise.
                if (info.routes != lastRoutes) {
                    syncTunRoutes(cidr, info.routes, spec)
                    lastRoutes = info.routes
                }
                val peersJson = if (info.peers.isNotEmpty()) {
                    Json.encodeToString(ListSerializer(RuntimePeer.serializer()), info.peers)
                } else {
                    null
                }
                val status = currentStatus.get()
                currentStatus.set(status.copy(peersJson = peersJson, hostname = info.hostname, natType = info.natType))
            }.onFailure {
                Log.e(TAG, "pollStatus failed", it)
            }
            if (shouldFail) {
                failRoot(failMessage ?: "Root EasyTier error")
                return
            }
            delay(2_000)
        }
        Log.w(TAG, "pollStatus: loop exited, state=${currentStatus.get().state}")
    }

    private fun stopRoot() {
        Log.i(TAG, "stopRoot")
        pollJob?.cancel()
        pollJob = null
        statusJob?.cancel()
        statusJob = null
        tunDescriptor?.close()
        tunDescriptor = null
        runCatching { RootTunNative.destroy() }
        removeAllTunRules()
        runCatching { EasyTierJni.retainNetworkInstance(null) }
        if (magicDnsEnabled) restoreSystemDns()
        magicDnsEnabled = false
        currentStatus.set(RootRuntimeStatus(RuntimeState.STOPPED.name, null, null, null))
    }

    private fun failRoot(message: String) {
        Log.e(TAG, "failRoot: $message")
        pollJob?.cancel()
        pollJob = null
        statusJob?.cancel()
        statusJob = null
        tunDescriptor?.close()
        tunDescriptor = null
        runCatching { RootTunNative.destroy() }
        removeAllTunRules()
        runCatching { EasyTierJni.retainNetworkInstance(null) }
        if (magicDnsEnabled) restoreSystemDns()
        magicDnsEnabled = false
        currentStatus.set(RootRuntimeStatus(RuntimeState.ERROR.name, null, null, message))
    }

    private fun enableMagicDnsSystemDns() {
        savedDns1 = readSystemDns("dns1")
        savedDns2 = readSystemDns("dns2")
        Log.i(TAG, "enableMagicDnsSystemDns: saved dns1=$savedDns1 dns2=$savedDns2, setting to $MAGIC_DNS_FAKE_IP")
        writeSystemDns("dns1", MAGIC_DNS_FAKE_IP)
        writeSystemDns("dns2", MAGIC_DNS_FAKE_IP)
    }

    private fun restoreSystemDns() {
        Log.i(TAG, "restoreSystemDns: dns1=$savedDns1 dns2=$savedDns2")
        val d1 = savedDns1
        val d2 = savedDns2
        if (d1 != null) writeSystemDns("dns1", d1) else clearSystemDns("dns1")
        if (d2 != null) writeSystemDns("dns2", d2) else clearSystemDns("dns2")
        savedDns1 = null
        savedDns2 = null
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

    private fun nativeError(fallback: String) = EasyTierJni.getLastError() ?: fallback
}
