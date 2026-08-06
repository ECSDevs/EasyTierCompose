package cc.ptoe.easytier.compose.core

import cc.ptoe.easytier.compose.data.WireGuardPortalInfo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * Fetches the WireGuard VPN portal info of a running instance via the generic
 * JSON RPC bridge ([EasyTierJni.callJsonRpc]). collectNetworkInfos does not
 * include the portal client config, so this calls the same
 * VpnPortalRpc.GetVpnPortalInfo RPC that easytier-cli uses.
 *
 * Returns null when the instance has no portal configured, the portal is not
 * started yet, or the RPC fails. Safe to call from any polling loop.
 */
object WireGuardPortalClient {
    private const val SERVICE_NAME = "api.instance.VpnPortalRpcService"
    private const val METHOD_NAME = "get_vpn_portal_info"

    fun fetch(instanceName: String): WireGuardPortalInfo? = runCatching {
        val payload =
            """{"instance":{"instance_selector":{"name":${Json.encodeToString(instanceName)}}}}"""
        val response = EasyTierJni.callJsonRpc(SERVICE_NAME, METHOD_NAME, null, payload)
            ?: return null
        val info = Json.parseToJsonElement(response).jsonObject["vpn_portal_info"]?.jsonObject
            ?: return null
        val vpnType = info["vpn_type"]?.jsonPrimitive?.content.orEmpty()
        val clientConfig = info["client_config"]?.jsonPrimitive?.content.orEmpty()
        val connectedClients = info["connected_clients"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.content.takeIf(String::isNotBlank) }
            .orEmpty()
        // Core reports vpn_type = "null" with an empty config when no portal is
        // configured, and an "ERROR: ..." placeholder when the portal has not
        // started yet. Treat both as "no info available".
        if (vpnType == "null" || clientConfig.isBlank() || clientConfig.startsWith("ERROR")) {
            return null
        }
        WireGuardPortalInfo(vpnType, clientConfig.trim(), connectedClients)
    }.getOrNull()
}

/**
 * Builds a v2rayN-style share link from the portal client config:
 * `wireguard://<privateKey>@<host>:<port>?publickey=...&address=...#<remark>`.
 *
 * Matches v2rayN's WireguardFmt.Resolve: the URL userinfo carries the WireGuard
 * private key, host/port come from the [Peer] Endpoint, and `publickey` /
 * `address` / `mtu` / `reserved` are URL-encoded query parameters. Returns null
 * when the rendered config is missing the private key or endpoint.
 */
fun WireGuardPortalInfo.toV2rayShareLink(remark: String = ""): String? = runCatching {
    val fields = clientConfig.lineSequence()
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() && !it.startsWith('[') && it.contains('=') }
        .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
    val privateKey = fields["PrivateKey"] ?: return null
    val endpoint = fields["Endpoint"] ?: return null
    // IPv6 endpoints keep their brackets ("[::1]:51820"), so split on the last colon.
    val host = endpoint.substringBeforeLast(':').trim()
    val port = endpoint.substringAfterLast(':', "").trim()
    if (host.isEmpty()) return null
    val query = buildList {
        fields["PublicKey"]?.takeIf(String::isNotEmpty)?.let { add("publickey=${urlEncode(it)}") }
        fields["Address"]?.takeIf(String::isNotEmpty)?.let { add("address=${urlEncode(it)}") }
        fields["MTU"]?.takeIf(String::isNotEmpty)?.let { add("mtu=${urlEncode(it)}") }
    }.joinToString("&")
    buildString {
        append("wireguard://")
        append(urlEncode(privateKey))
        append('@').append(host)
        if (port.isNotEmpty()) append(':').append(port)
        if (query.isNotEmpty()) append('?').append(query)
        if (remark.isNotBlank()) append('#').append(urlEncode(remark.trim()))
    }
}.getOrNull()

private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")
