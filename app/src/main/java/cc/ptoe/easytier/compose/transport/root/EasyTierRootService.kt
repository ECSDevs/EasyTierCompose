package cc.ptoe.easytier.compose.transport.root

import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import cc.ptoe.easytier.compose.core.EasyTierJni
import cc.ptoe.easytier.compose.data.RuntimeState
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicReference

class EasyTierRootService : RootService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val currentStatus = AtomicReference(RootRuntimeStatus(RuntimeState.STOPPED.name, null, null, null))
    private var tunDescriptor: ParcelFileDescriptor? = null
    private var pollJob: Job? = null

    companion object {
        private const val TAG = "EasyTierRootService"
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
        Log.i(TAG, "startRoot: profileId=$profileId ipv4Cidr=${spec.ipv4Cidr} mtu=${spec.mtu} manualRoutes=${spec.manualRoutes} proxyCidrs=${spec.proxyCidrs}")
        currentStatus.set(RootRuntimeStatus(RuntimeState.STARTING.name, null, null, null))
        runCatching {
            require(EasyTierJni.parseConfig(toml) == 0) { nativeError("EasyTier rejected configuration") }
            stopRoot()
            currentStatus.set(RootRuntimeStatus(RuntimeState.STARTING.name, null, null, null))
            require(EasyTierJni.runNetworkInstance(toml) == 0) { nativeError("EasyTier failed to start") }
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
                val info = raw?.rootInfo(profileId)
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
        val allRoutes = (runtimeRoutes + spec.manualRoutes + spec.proxyCidrs)
            .filter(String::isNotBlank).distinct().sorted()
        Log.i(TAG, "attachTun: syncing ${allRoutes.size} routes: $allRoutes")
        RootTunNative.syncRoutes(allRoutes.toTypedArray())
        currentStatus.set(RootRuntimeStatus(RuntimeState.RUNNING.name, cidr, devName, null))
        Log.i(TAG, "attachTun: running, virtualIpv4=$cidr dev=$devName")
    }

    private fun stopRoot() {
        Log.i(TAG, "stopRoot")
        pollJob?.cancel()
        pollJob = null
        tunDescriptor?.close()
        tunDescriptor = null
        runCatching { RootTunNative.destroy() }
        runCatching { EasyTierJni.retainNetworkInstance(null) }
        currentStatus.set(RootRuntimeStatus(RuntimeState.STOPPED.name, null, null, null))
    }

    private fun failRoot(message: String) {
        Log.e(TAG, "failRoot: $message")
        pollJob?.cancel()
        pollJob = null
        tunDescriptor?.close()
        tunDescriptor = null
        runCatching { RootTunNative.destroy() }
        runCatching { EasyTierJni.retainNetworkInstance(null) }
        currentStatus.set(RootRuntimeStatus(RuntimeState.ERROR.name, null, null, message))
    }

    private fun nativeError(fallback: String) = EasyTierJni.getLastError() ?: fallback
}

private data class RootNetworkInfo(val virtualIpv4: String?, val routes: List<String>, val error: String?)

private fun String.rootInfo(profileId: String): RootNetworkInfo? = runCatching {
    val map = Json.parseToJsonElement(this).jsonObject["map"]?.jsonObject ?: return null
    val info = map[profileId]?.jsonObject ?: return null
    val error = info["error_msg"]?.jsonPrimitive?.content
    val node = info["my_node_info"]?.jsonObject
    val ipv4 = node?.get("virtual_ipv4")?.jsonObject?.let(::ipv4InetToCidr)
    // Each route entry advertises proxy_cidrs (repeated string of CIDRs like "192.168.0.0/16").
    // Those are the remote networks reachable via peers — the kernel routes we need on the TUN.
    val routes = (info["routes"] as? JsonArray)?.flatMap { route ->
        (route.jsonObject["proxy_cidrs"] as? JsonArray)?.mapNotNull { cidr ->
            cidr.jsonPrimitive.content.takeIf(String::isNotBlank)
        } ?: emptyList()
    } ?: emptyList()
    RootNetworkInfo(ipv4, routes, error)
}.getOrNull()

// EasyTier serializes common.Ipv4Inet as { "address": { "addr": <uint32 big-endian> }, "network_length": <uint32> }.
// The u32 is the IPv4 address packed big-endian (u32::from_be_bytes(octets) in Rust), so it must be
// unpacked back to dotted-decimal before passing to inet_pton in the root TUN JNI helper.
private fun ipv4InetToCidr(inet: JsonObject): String? {
    val addr = inet["address"]?.jsonObject?.get("addr")?.jsonPrimitive?.content?.toLongOrNull() ?: return null
    val prefix = inet["network_length"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
    if (prefix !in 0..32 || addr !in 0L..0xFFFFFFFFL) return null
    val dotted = "${(addr shr 24) and 0xFF}.${(addr shr 16) and 0xFF}.${(addr shr 8) and 0xFF}.${addr and 0xFF}"
    return "$dotted/$prefix"
}
