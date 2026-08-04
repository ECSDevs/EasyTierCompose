package cc.ptoe.easytier.compose.transport.root

import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteCallbackList
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
    private val currentStatus = AtomicReference(RootRuntimeStatus(RuntimeState.STOPPED.name, null, null, null, null, null))
    // Push-based status delivery: the app registers a callback once and receives
    // every status update instead of polling getStatus() over Binder every few
    // seconds. RemoteCallbackList handles DeathRecipient cleanup automatically
    // when the app process dies.
    private val callbacks = RemoteCallbackList<IRootStatusCallback>()
    private var tunDescriptor: ParcelFileDescriptor? = null
    private var tunFd: Int = -1
    private var tunDevName: String = "easytier0"
    private var pollJob: Job? = null
    private var statusJob: Job? = null
    // Tracks the startRoot coroutine so stopRoot/failRoot/onDestroy can cancel it.
    private var startJob: Job? = null
    private var magicDnsEnabled = false
    private var savedDns1: String? = null
    private var savedDns2: String? = null
    // Guards cleanup so stopRoot/failRoot/onDestroy are idempotent.
    @Volatile private var active = false
    // Tracks ip rule priorities we installed so stopRoot/failRoot can remove them.
    private val installedRules = mutableListOf<Int>()
    // TUN routes we added to easytier0, so cleanup can remove exactly the set we installed.
    private val installedRoutes = mutableSetOf<String>()

    companion object {
        private const val TAG = "EasyTierRootService"
        private const val MAGIC_DNS_FAKE_IP = "100.100.100.101"
        private const val MAGIC_DNS_ROUTE = "100.100.100.101/32"
        // Priority for our easytier0 ip rules. Android's per-interface rules start
        // at 10000; using 5000 places our TUN rules ahead of them so TUN routes win.
        private const val IP_RULE_PRIORITY_START = 5000
    }

    private val binder = object : IEasyTierRootService.Stub() {
        override fun start(profileId: String, toml: String, spec: RootTunSpec) {
            startJob?.cancel()
            startJob = scope.launch { startRoot(profileId, toml, spec) }
        }

        override fun stop() {
            scope.launch { stopRoot() }
        }

        override fun getStatus(): RootRuntimeStatus = currentStatus.get()

        override fun registerStatusCallback(cb: IRootStatusCallback?) {
            if (cb == null) return
            callbacks.register(cb)
            runCatching { cb.onStatusUpdated(currentStatus.get()) }
        }

        override fun unregisterStatusCallback(cb: IRootStatusCallback?) {
            if (cb == null) return
            callbacks.unregister(cb)
        }
    }

    private fun updateStatus(status: RootRuntimeStatus) {
        currentStatus.set(status)
        val n = callbacks.beginBroadcast()
        try {
            for (i in 0 until n) {
                runCatching { callbacks.getBroadcastItem(i).onStatusUpdated(status) }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        startJob?.cancel()
        startJob = null
        cleanupResources()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun startRoot(profileId: String, toml: String, spec: RootTunSpec) {
        Log.i(TAG, "startRoot: profileId=$profileId ipv4Cidr=${spec.ipv4Cidr} mtu=${spec.mtu} manualRoutes=${spec.manualRoutes} proxyCidrs=${spec.proxyCidrs} magicDns=${spec.magicDns}")
        active = true
        updateStatus(RootRuntimeStatus(RuntimeState.STARTING.name, profileId, null, null, null, null))
        runCatching {
            require(EasyTierJni.parseConfig(toml) == 0) { nativeError("EasyTier rejected configuration") }
            cleanupResources()
            magicDnsEnabled = spec.magicDns
            updateStatus(RootRuntimeStatus(RuntimeState.STARTING.name, profileId, null, null, null, null))
            // 1) Create easytier0.
            createTun(spec)
            // 2) Start EasyTier. The Rust core's Android filter_iface() now
            //    filters out TUN/TAP interfaces (tun0, easytier0, mihomo TUN,
            //    etc.), so with bind_device=true (default) EasyTier only
            //    SO_BINDTODEVICEs to physical interfaces (wlan0/eth0/rmnet*),
            //    bypassing VpnService and other TUNs without any netns or
            //    fwmark hacks.
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

    /** Creates easytier0 and retains its fd for setTunFd. */
    private fun createTun(spec: RootTunSpec) {
        val devName = spec.devName.ifBlank { "easytier0" }
        tunDevName = devName
        Log.i(TAG, "createTun: dev=$devName cidr=${spec.ipv4Cidr} mtu=${spec.mtu}")
        val fd = RootTunNative.create(spec.ipv4Cidr, spec.mtu, devName)
        tunFd = fd
        tunDescriptor = ParcelFileDescriptor.adoptFd(fd)
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
        val devName = tunDevName
        Log.i(TAG, "attachTun: profileId=$profileId cidr=$cidr dev=$devName")
        if (spec.ipv4Cidr.isNullOrBlank()) {
            execRoot("ip addr add $cidr dev $devName")
        }
        require(EasyTierJni.setTunFd(profileId, tunFd) == 0) { nativeError("EasyTier failed to attach root TUN") }
        syncTunRoutes(cidr, runtimeRoutes, spec)
        updateStatus(RootRuntimeStatus(RuntimeState.RUNNING.name, profileId, cidr, devName, null, null))
        Log.i(TAG, "attachTun: running, virtualIpv4=$cidr dev=$devName")
        statusJob = scope.launch { pollStatus(profileId, cidr, spec) }
    }

    /**
     * Syncs the full TUN route set to easytier0.
     *
     * Routes include:
     *  - The virtual IP subnet (e.g. 10.126.126.0/24 from 10.126.126.1/24).
     *  - Remote peer proxy_cidrs (from collectNetworkInfos routes).
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
        val devName = tunDevName
        for (r in (installedRoutes - allRoutes).toList()) {
            execRoot("ip route del $r dev $devName 2>/dev/null")
            installedRoutes.remove(r)
        }
        for (r in allRoutes - installedRoutes) {
            val out = execRoot("ip route add $r dev $devName 2>&1").orEmpty()
            if (out.isBlank() || out.contains("File exists")) {
                installedRoutes.add(r)
            } else {
                Log.w(TAG, "syncTunRoutes: add $r failed: ${out.trim()}")
            }
        }
        installTunRules(allRoutes)
    }

    /** Computes the network subnet CIDR from an IPv4 CIDR, e.g. "10.126.126.1/24" -> "10.126.126.0/24". */
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
     * (ahead of Android's per-interface rules at 10000+) so Android's
     * per-interface routing tables don't intercept traffic destined for the TUN.
     * Idempotent: only adds rules for CIDRs not already installed.
     */
    private fun installTunRules(cidrs: List<String>) {
        val wanted = cidrs.filter(String::isNotBlank).distinct().sorted()
        val removedCidrs = currentRuleCidrs() - wanted.toSet()
        for (cidr in removedCidrs) removeRuleForCidr(cidr)
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
                val toMatch = Regex("""to\s+(\S+)""").find(line)?.groupValues?.getOrNull(1)
                val isMain = line.contains("lookup main")
                if (toMatch != null && isMain) toMatch else null
            }
            .toSet()
    }.getOrDefault(emptySet())

    private fun removeRuleForCidr(cidr: String) {
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

    /** Runs a command as root in the current (main) namespace. */
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
                updateStatus(status.copy(peersJson = peersJson, hostname = info.hostname, natType = info.natType, profileId = profileId))
            }.onFailure {
                Log.e(TAG, "pollStatus failed", it)
            }
            if (shouldFail) {
                failRoot(failMessage ?: "Root EasyTier error")
                return
            }
            delay(5_000)
        }
        Log.w(TAG, "pollStatus: loop exited, state=${currentStatus.get().state}")
    }

    /**
     * Idempotent resource cleanup. Cancels poll jobs, closes the TUN descriptor,
     * releases the EasyTier instance, tears down easytier0 routes/rules/
     * interface, and restores system DNS. Safe to call multiple times.
     */
    private fun cleanupResources() {
        pollJob?.cancel()
        pollJob = null
        statusJob?.cancel()
        statusJob = null
        tunDescriptor?.close()
        tunDescriptor = null
        tunFd = -1
        runCatching { EasyTierJni.retainNetworkInstance(null) }
        cleanupTunRoutesAndRules()
        if (magicDnsEnabled) restoreSystemDns()
        magicDnsEnabled = false
    }

    /** Removes easytier0 routes, ip rules, and the interface itself. */
    private fun cleanupTunRoutesAndRules() {
        removeAllTunRules()
        val devName = tunDevName
        for (r in installedRoutes.toList()) {
            execRoot("ip route del $r dev $devName 2>/dev/null")
            installedRoutes.remove(r)
        }
        execRoot("ip link del $devName 2>/dev/null")
    }

    private fun stopRoot() {
        if (!active) return
        active = false
        startJob?.cancel()
        startJob = null
        Log.i(TAG, "stopRoot")
        cleanupResources()
        updateStatus(RootRuntimeStatus(RuntimeState.STOPPED.name, null, null, null, null, null))
        stopSelf()
    }

    private fun failRoot(message: String) {
        if (!active) return
        active = false
        startJob = null
        Log.e(TAG, "failRoot: $message")
        cleanupResources()
        updateStatus(RootRuntimeStatus(RuntimeState.ERROR.name, null, null, null, message, null))
        stopSelf()
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
