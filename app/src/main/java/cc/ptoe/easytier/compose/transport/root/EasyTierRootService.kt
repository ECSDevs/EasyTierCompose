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

    companion object {
        private const val TAG = "EasyTierRootService"
        private const val MAGIC_DNS_FAKE_IP = "100.100.100.101"
        private const val MAGIC_DNS_ROUTE = "100.100.100.101/32"
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
        val magicDnsRoute = if (spec.magicDns) listOf(MAGIC_DNS_ROUTE) else emptyList()
        val allRoutes = (runtimeRoutes + spec.manualRoutes + spec.proxyCidrs + magicDnsRoute)
            .filter(String::isNotBlank).distinct().sorted()
        Log.i(TAG, "attachTun: syncing ${allRoutes.size} routes: $allRoutes")
        RootTunNative.syncRoutes(allRoutes.toTypedArray())
        currentStatus.set(RootRuntimeStatus(RuntimeState.RUNNING.name, cidr, devName, null))
        Log.i(TAG, "attachTun: running, virtualIpv4=$cidr dev=$devName")
        statusJob = scope.launch { pollStatus(profileId) }
    }

    private suspend fun pollStatus(profileId: String) {
        while (currentStatus.get().state == RuntimeState.RUNNING.name) {
            var shouldFail = false
            var failMessage: String? = null
            runCatching {
                val raw = EasyTierJni.collectNetworkInfos(1)
                val info = raw?.networkInfo(profileId) ?: return@runCatching
                if (info.error?.takeIf(String::isNotBlank) != null) {
                    Log.e(TAG, "pollStatus: EasyTier error: ${info.error}")
                    shouldFail = true
                    failMessage = info.error
                    return@runCatching
                }
                val peersJson = if (info.peers.isNotEmpty()) {
                    Json.encodeToString(ListSerializer(RuntimePeer.serializer()), info.peers)
                } else {
                    null
                }
                val status = currentStatus.get()
                currentStatus.set(status.copy(peersJson = peersJson))
            }.onFailure {
                Log.e(TAG, "pollStatus failed", it)
            }
            if (shouldFail) {
                failRoot(failMessage ?: "Root EasyTier error")
                return
            }
            delay(2_000)
        }
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
