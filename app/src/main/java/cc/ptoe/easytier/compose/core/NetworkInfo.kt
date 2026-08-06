package cc.ptoe.easytier.compose.core

import android.content.Context
import android.os.Build
import android.provider.Settings
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.RuntimePeer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    // Peers may advertise bare IPs (e.g. "192.168.1.100" without a prefix); normalize those to
    // /32 so VpnService.Builder.addRoute / netlink route addition don't reject them. This
    // mirrors easytier-gui's getRoutesForVpn behavior.
    val routes = (info["routes"] as? JsonArray)?.flatMap { route ->
        (route.jsonObject["proxy_cidrs"] as? JsonArray)?.mapNotNull { cidr ->
            cidr.jsonPrimitive.content.takeIf(String::isNotBlank)?.let(::normalizeCidr)
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
 * Normalizes a CIDR string from peer-advertised proxy_cidrs. Peers may advertise bare IPs
 * (e.g. "192.168.1.100" without a prefix); append /32 so VpnService.Builder.addRoute and
 * netlink route addition accept them. Strings already containing "/" are returned unchanged.
 */
fun normalizeCidr(cidr: String): String =
    if (cidr.contains('/')) cidr else "$cidr/32"

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
