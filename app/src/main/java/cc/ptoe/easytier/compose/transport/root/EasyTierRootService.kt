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
    // Manages TUN routes + ip rules; created in createTun() once devName is known.
    private var routeManager: RootTunRouteManager? = null
    // Manages system DNS switching for Magic DNS.
    private val dnsManager = SystemDnsManager()
    // Guards cleanup so stopRoot/failRoot/onDestroy are idempotent.
    @Volatile private var active = false

    companion object {
        private const val TAG = "EasyTierRootService"
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
            if (spec.magicDns) dnsManager.enableMagicDns(SystemDnsManager.MAGIC_DNS_FAKE_IP)
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
        routeManager = RootTunRouteManager(devName, ::execRoot)
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
        routeManager?.syncTunRoutes(cidr, runtimeRoutes, spec)
        updateStatus(RootRuntimeStatus(RuntimeState.RUNNING.name, profileId, cidr, devName, null, null))
        Log.i(TAG, "attachTun: running, virtualIpv4=$cidr dev=$devName")
        statusJob = scope.launch { pollStatus(profileId, cidr, spec) }
    }

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
                    routeManager?.syncTunRoutes(cidr, info.routes, spec)
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
        routeManager?.cleanupRoutesAndRules()
        execRoot("ip link del $tunDevName 2>/dev/null")
        dnsManager.restore()
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

    private fun nativeError(fallback: String) = EasyTierJni.getLastError() ?: fallback
}
