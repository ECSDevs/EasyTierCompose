package cc.ptoe.easytier.compose.core

import android.app.Activity
import android.content.Context
import android.os.Build
import android.provider.Settings
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.GlobalSettings
import cc.ptoe.easytier.compose.data.RuntimePeer
import cc.ptoe.easytier.compose.data.RuntimeState
import cc.ptoe.easytier.compose.data.RuntimeStatus
import cc.ptoe.easytier.compose.data.TunMode
import cc.ptoe.easytier.compose.transport.RuntimeEffect
import cc.ptoe.easytier.compose.transport.root.RootTunTransport
import cc.ptoe.easytier.compose.transport.vpn.VpnTunTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class EasyTierRuntimeCoordinator(private val context: Context, activity: Activity) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val vpn = VpnTunTransport(context, activity)
    private val root = RootTunTransport(context)
    private val mutableStatus = MutableStateFlow(RuntimeStatus.Stopped)
    val status: StateFlow<RuntimeStatus> = mutableStatus.asStateFlow()
    private val mutableEffects = MutableSharedFlow<RuntimeEffect>()
    val effects: SharedFlow<RuntimeEffect> = mutableEffects.asSharedFlow()
    private var activeProfile: EasyTierProfile? = null
    private var pollJob: Job? = null

    init {
        scope.launch { vpn.effects.collect { mutableEffects.emit(it) } }
    }

    suspend fun start(profile: EasyTierProfile, globalSettings: GlobalSettings = GlobalSettings()): RuntimeStatus = mutex.withLock {
        // Auto-fill hostname from the Android device name when the profile leaves it blank,
        // so peers see a recognizable identity without forcing the user to configure it.
        val effectiveProfile = profile.withDeviceHostnameIfBlank(context)
        val errors = ProfileValidator().validate(effectiveProfile, globalSettings)
        if (errors.isNotEmpty()) return@withLock failure(effectiveProfile, errors.values.first())
        val toml = TomlConfigBuilder.build(effectiveProfile, globalSettings)
        stopLocked()
        activeProfile = effectiveProfile
        mutableStatus.value = RuntimeStatus(RuntimeState.STARTING, effectiveProfile.id, effectiveProfile.tunMode, null, null, null)
        return@withLock when (effectiveProfile.tunMode) {
            TunMode.VPN_SERVICE -> startVpn(effectiveProfile, toml, globalSettings)
            TunMode.ROOT_TUN -> startRoot(effectiveProfile, toml, globalSettings)
        }
    }

    suspend fun stop(): RuntimeStatus = mutex.withLock { stopLocked() }

    suspend fun onVpnPermissionResult(granted: Boolean): RuntimeStatus = mutex.withLock {
        val result = vpn.onPermissionResult(granted)
        mutableStatus.value = result
        if (result.state == RuntimeState.RUNNING || result.state == RuntimeState.STARTING) pollVpn()
        result
    }

    private suspend fun startVpn(profile: EasyTierProfile, toml: String, globalSettings: GlobalSettings): RuntimeStatus = try {
        EasyTierJni.retainNetworkInstance(null)
        require(EasyTierJni.runNetworkInstance(toml) == 0) { nativeError("EasyTier failed to start") }
        vpn.start(profile, toml, globalSettings).also {
            mutableStatus.value = it
            if (it.state == RuntimeState.STARTING || it.state == RuntimeState.RUNNING) pollVpn()
        }
    } catch (error: Throwable) {
        vpn.stop()
        runCatching { EasyTierJni.retainNetworkInstance(null) }
        failure(profile, error.message ?: nativeError("EasyTier failed to start"))
    }

    private suspend fun startRoot(profile: EasyTierProfile, toml: String, globalSettings: GlobalSettings): RuntimeStatus {
        val status = root.start(profile, toml, globalSettings)
        mutableStatus.value = status
        if (status.state == RuntimeState.RUNNING || status.state == RuntimeState.STARTING) pollRoot()
        return status
    }

    private suspend fun stopLocked(): RuntimeStatus {
        pollJob?.cancel()
        pollJob = null
        val profile = activeProfile
        when (profile?.tunMode) {
            TunMode.VPN_SERVICE -> {
                mutableStatus.value = mutableStatus.value.copy(state = RuntimeState.STOPPING)
                vpn.stop()
                runCatching { EasyTierJni.retainNetworkInstance(null) }
            }
            TunMode.ROOT_TUN -> root.stop()
            null -> return mutableStatus.value.takeIf { it.state == RuntimeState.STOPPED } ?: RuntimeStatus.Stopped
        }
        activeProfile = null
        return RuntimeStatus.Stopped.also { mutableStatus.value = it }
    }

    private fun pollVpn() {
        pollJob?.cancel()
        val profile = activeProfile ?: return
        pollJob = scope.launch {
            while (mutableStatus.value.state in setOf(RuntimeState.STARTING, RuntimeState.RUNNING)) {
                runCatching { EasyTierJni.collectNetworkInfos(1)?.networkInfo(profile.id) }.getOrNull()?.let { info ->
                    if (!info.error.isNullOrBlank()) {
                        failure(profile, info.error)
                        vpn.stop()
                        EasyTierJni.retainNetworkInstance(null)
                        return@launch
                    }
                    if (!info.virtualIpv4.isNullOrBlank()) {
                        val status = vpn.establishWhenResolved(profile, info.virtualIpv4, info.routes)
                        mutableStatus.value = status.copy(peers = info.peers, hostname = info.hostname, natType = info.natType)
                    } else {
                        mutableStatus.value = mutableStatus.value.copy(hostname = info.hostname, natType = info.natType)
                    }
                }
                delay(2_000)
            }
        }
    }

    private fun pollRoot() {
        pollJob?.cancel()
        pollJob = scope.launch { root.status.collect { mutableStatus.value = it } }
    }

    private fun failure(profile: EasyTierProfile, error: String): RuntimeStatus =
        RuntimeStatus(RuntimeState.ERROR, profile.id, profile.tunMode, null, null, error).also { mutableStatus.value = it }

    private fun nativeError(fallback: String) = EasyTierJni.getLastError() ?: fallback
}

data class NetworkInfo(
    val virtualIpv4: String?,
    val routes: List<String>,
    val error: String?,
    val peers: List<RuntimePeer>,
    val hostname: String?,
    val natType: String?,
)

/** Parses the JSON returned by [EasyTierJni.collectNetworkInfos] for a specific profile instance. */
fun String.networkInfo(profileId: String): NetworkInfo? = runCatching {
    val map = Json.parseToJsonElement(this).jsonObject["map"]?.jsonObject ?: return null
    val info = map[profileId]?.jsonObject ?: return null
    val error = info["error_msg"]?.jsonPrimitive?.content
    val myNode = info["my_node_info"]?.jsonObject
    val virtual = myNode?.get("virtual_ipv4")?.jsonObject?.let(::ipv4InetToCidr)
    val hostname = myNode?.get("hostname")?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
    val natType = myNode?.get("stun_info")?.jsonObject?.get("udp_nat_type")?.let(::natTypeName)
    // Each route entry advertises proxy_cidrs (repeated string of CIDRs like "192.168.0.0/16").
    // Those are the remote networks reachable via peers — the kernel routes we need on the TUN.
    val routes = (info["routes"] as? JsonArray)?.flatMap { route ->
        (route.jsonObject["proxy_cidrs"] as? JsonArray)?.mapNotNull { cidr ->
            cidr.jsonPrimitive.content.takeIf(String::isNotBlank)
        } ?: emptyList()
    }?.distinct()?.sorted() ?: emptyList()
    val peers = info["peer_route_pairs"]?.jsonArray?.mapNotNull { it.peerRoutePair() }.orEmpty()
    NetworkInfo(virtual, routes, error, peers, hostname, natType)
}.getOrNull()

// peer_route_pairs entries combine a Route (hostname, ipv4, cost, latency, NAT) with a Peer
// (connections carrying latency_us / loss_rate / tunnel type). For relay peers (cost > 1)
// `peer` is typically null — only the route carries the path latency / hostname / NAT.
// Mirrors easytier-cli's PeerTableItem::from(PeerRoutePair).
fun JsonElement.peerRoutePair(): RuntimePeer? {
    val pair = jsonObject
    val route = pair["route"]?.jsonObject
    val peer = pair["peer"]?.jsonObject
    val hostname = route?.get("hostname")?.jsonPrimitive?.content?.ifBlank { null } ?: "unknown"
    val ipv4 = route?.get("ipv4_addr")?.jsonObject?.let(::ipv4InetToCidr)
    val cost = route?.get("cost")?.jsonPrimitive?.content?.toIntOrNull() ?: 1
    val pathLatency = route?.get("path_latency")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
    val pathLatencyLatencyFirst = route?.get("path_latency_latency_first")?.jsonPrimitive?.content?.toIntOrNull()
    val natType = route?.get("stun_info")?.jsonObject?.get("udp_nat_type").let(::natTypeName)

    val conns = (peer?.get("conns") as? JsonArray).orEmpty()
    val defaultConnId = peer?.get("default_conn_id")?.toString()
    var defaultConn: JsonObject? = null
    var fallbackConn: JsonObject? = null
    var minLatency = Long.MAX_VALUE
    for (connEl in conns) {
        val conn = connEl.jsonObject
        if (conn["conn_id"]?.toString() == defaultConnId) defaultConn = conn
        val lat = conn["stats"]?.jsonObject?.get("latency_us")?.jsonPrimitive?.content?.toLongOrNull() ?: Long.MAX_VALUE
        if (lat < minLatency) { minLatency = lat; fallbackConn = conn }
    }
    val selectedConn = defaultConn ?: fallbackConn
    val latencyUs = selectedConn?.get("stats")?.jsonObject?.get("latency_us")?.jsonPrimitive?.content?.toLongOrNull()
    // cost == 1: direct connection, latency from latency_us. cost > 1: relay, latency from
    // path_latency_latency_first (latency-first mode) falling back to path_latency, both in ms.
    val latencyMs = if (cost == 1) {
        latencyUs?.let { it / 1000.0 }
    } else {
        (pathLatencyLatencyFirst?.takeIf { it > 0 } ?: pathLatency).takeIf { it > 0 }?.toDouble()
    }
    val lossRate = selectedConn?.get("loss_rate")?.jsonPrimitive?.content?.toFloatOrNull()?.toDouble()
    val tunnelProtos = conns.mapNotNull { c ->
        c.jsonObject["tunnel"]?.jsonObject?.get("tunnel_type")?.jsonPrimitive?.content?.let(::normalizeTunnelType)
    }.distinct()
    val connectionType = if (cost == 1) "P2P" else "Relay ($cost)"
    return RuntimePeer(hostname, ipv4, latencyMs, connectionType, tunnelProtos, lossRate, natType, cost)
}

// EasyTier serializes common.Ipv4Inet as { address: { addr: <u32 big-endian> }, network_length }.
fun ipv4InetToCidr(inet: JsonObject): String? {
    val addr = inet["address"]?.jsonObject?.get("addr")?.jsonPrimitive?.content?.toLongOrNull() ?: return null
    val prefix = inet["network_length"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
    if (prefix !in 0..32 || addr !in 0L..0xFFFFFFFFL) return null
    val dotted = "${(addr shr 24) and 0xFF}.${(addr shr 16) and 0xFF}.${(addr shr 8) and 0xFF}.${addr and 0xFF}"
    return "$dotted/$prefix"
}

fun natTypeName(raw: JsonElement?): String {
    val p = raw?.jsonPrimitive ?: return "Unknown"
    return if (p.isString) p.content else when (p.content.toIntOrNull()) {
        0 -> "Unknown"; 1 -> "OpenInternet"; 2 -> "NoPAT"; 3 -> "FullCone"
        4 -> "Restricted"; 5 -> "PortRestricted"; 6 -> "Symmetric"
        7 -> "SymUdpFirewall"; 8 -> "SymmetricEasyInc"; 9 -> "SymmetricEasyDec"
        else -> "Unknown"
    }
}

// tunnel_type may be a bare scheme ("tcp") or a full URL ("tcp://1.2.3.4:11010"); keep the scheme.
fun normalizeTunnelType(raw: String): String =
    raw.substringBefore("://").ifBlank { raw }

/**
 * Returns [EasyTierProfile.hostname] when set, otherwise fills it from the Android device name
 * (Settings.Global.DEVICE_NAME, falling back to Build.MODEL). The result is normalized to match
 * EasyTier Core's `get_hostname`: ISO control characters are stripped and the value is capped at
 * 32 characters. Returns the original profile unchanged when a non-blank hostname is already
 * present or no device name can be resolved.
 */
fun EasyTierProfile.withDeviceHostnameIfBlank(context: Context): EasyTierProfile {
    val current = hostname?.trim()
    if (!current.isNullOrEmpty()) return this
    val raw = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        ?.trim()?.takeIf { it.isNotEmpty() }
        ?: Build.MODEL.trim().takeIf { it.isNotEmpty() }
        ?: return this
    val normalized = raw.filter { !it.isISOControl() }.take(32).trim()
    if (normalized.isEmpty()) return this
    return copy(hostname = normalized)
}
