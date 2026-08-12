package cc.ptoe.easytier.compose.core

import android.content.Context
import androidx.annotation.StringRes
import cc.ptoe.easytier.compose.R
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.GlobalSettings
import java.net.InetAddress

sealed interface ValidationMessage {
    data class Resource(@StringRes val id: Int) : ValidationMessage

    data class Raw(val value: String) : ValidationMessage
}

fun ValidationMessage.resolve(context: Context): String = when (this) {
    is ValidationMessage.Resource -> context.getString(id)
    is ValidationMessage.Raw -> value
}

class ProfileValidator(private val nativeParser: NativeConfigParser = EasyTierNativeConfigParser) {
    fun validate(
        profile: EasyTierProfile,
        globalSettings: GlobalSettings = GlobalSettings(),
    ): Map<String, ValidationMessage> = buildMap {
        if (profile.name.isBlank()) put("name", ValidationMessage.Resource(R.string.error_profile_name_required))
        if (profile.networkName.isBlank()) put("networkName", ValidationMessage.Resource(R.string.error_network_name_required))
        if (profile.tldDnsZone.isBlank()) put("tldDnsZone", ValidationMessage.Resource(R.string.error_tld_dns_zone_required))
        if (!profile.dhcp && !profile.virtualIpv4.isValidIpv4Cidr()) {
            put("virtualIpv4", ValidationMessage.Resource(R.string.error_ipv4_cidr_required_dhcp_off))
        }
        profile.virtualIpv6?.takeIf { it.isNotBlank() }?.let {
            if (!it.isValidIpv6Cidr()) put("virtualIpv6", ValidationMessage.Resource(R.string.error_invalid_ipv6_cidr))
        }
        profile.ipv6PublicAddrPrefix?.takeIf { it.isNotBlank() }?.let {
            if (!it.isValidIpv6Cidr()) put("ipv6PublicAddrPrefix", ValidationMessage.Resource(R.string.error_invalid_ipv6_cidr))
        }
        validateStringList("listeners", profile.listeners) { it.isValidListenerUrl() }
        validateStringList("mappedListeners", profile.mappedListeners) { it.isValidListenerUrl() }
        validateStringList("manualRoutes", profile.manualRoutes) { it.isValidIpv4Cidr() }
        validateStringList("exitNodes", profile.exitNodes) { it.isValidIp() }
        validateStringList("stunServers", profile.stunServers)
        validateStringList("stunServersV6", profile.stunServersV6)
        validateStringList("tcpWhitelist", profile.tcpWhitelist)
        validateStringList("udpWhitelist", profile.udpWhitelist)
        validatePeers(profile)
        validateProxyNetworks(profile)
        validatePortForwards(profile)
        profile.vpnPortal?.let { portal ->
            when {
                !portal.clientCidr.isValidIpv4Cidr() -> put("vpnPortal", ValidationMessage.Resource(R.string.error_invalid_client_cidr))
                !portal.clientCidr.isRoutablePortalCidr() -> put(
                    "vpnPortal",
                    ValidationMessage.Resource(R.string.error_portal_cidr_not_routable),
                )
            }
            if (!portal.wireguardListen.isValidSocketAddr()) put("vpnPortal", ValidationMessage.Resource(R.string.error_invalid_wireguard_listen))
        }
        if (globalSettings.mtu !in 576..9000) {
            put("globalMtu", ValidationMessage.Resource(R.string.error_mtu_range))
        }
        if (globalSettings.multiThreadCount <= 0) {
            put("globalMultiThreadCount", ValidationMessage.Resource(R.string.error_thread_count_positive))
        }
        if (globalSettings.foreignRelayBpsLimit < 0) {
            put("globalForeignRelayBpsLimit", ValidationMessage.Resource(R.string.error_non_negative))
        }
        if (globalSettings.instanceRecvBpsLimit < 0) {
            put("globalInstanceRecvBpsLimit", ValidationMessage.Resource(R.string.error_non_negative))
        }
        // Final native validation of the generated TOML.
        val toml = TomlConfigBuilder.build(profile, globalSettings)
        nativeParser.parse(toml)?.let { put("form", it) }
    }

    private fun MutableMap<String, ValidationMessage>.validateStringList(
        field: String,
        values: List<String>,
        itemCheck: ((String) -> Boolean)? = null,
    ) {
        if (values.any { it.isBlank() }) {
            put(field, ValidationMessage.Resource(R.string.error_entries_not_blank))
        } else if (itemCheck != null && values.any { !itemCheck(it.trim()) }) {
            put(field, ValidationMessage.Resource(R.string.error_entries_invalid))
        }
    }

    private fun MutableMap<String, ValidationMessage>.validatePeers(profile: EasyTierProfile) {
        val blanks = profile.peers.count { it.uri.isBlank() }
        if (blanks > 0) {
            put("peers", ValidationMessage.Resource(R.string.error_peer_uris_not_blank))
        } else if (profile.peers.any { !it.uri.trim().isValidListenerUrl() }) {
            put("peers", ValidationMessage.Resource(R.string.error_peer_uris_invalid))
        }
    }

    private fun MutableMap<String, ValidationMessage>.validateProxyNetworks(profile: EasyTierProfile) {
        val blanks = profile.proxyNetworks.count { it.cidr.isBlank() }
        if (blanks > 0) {
            put("proxyNetworks", ValidationMessage.Resource(R.string.error_proxy_cidrs_not_blank))
            return
        }
        if (profile.proxyNetworks.any { !it.cidr.trim().isValidIpv4Cidr() }) {
            put("proxyNetworks", ValidationMessage.Resource(R.string.error_proxy_cidrs_invalid))
            return
        }
        if (profile.proxyNetworks.any { it.mappedCidr != null && it.mappedCidr.isNotBlank() && !it.mappedCidr.trim().isValidIpv4Cidr() }) {
            put("proxyNetworks", ValidationMessage.Resource(R.string.error_mapped_cidrs_invalid))
            return
        }
        val allowedProtos = setOf("tcp", "udp", "icmp")
        if (profile.proxyNetworks.any { net -> net.allow.any { it.trim().lowercase() !in allowedProtos } }) {
            put("proxyNetworks", ValidationMessage.Resource(R.string.error_proxy_allow_protocols))
        }
    }

    private fun MutableMap<String, ValidationMessage>.validatePortForwards(profile: EasyTierProfile) {
        val blanks = profile.portForwards.count { it.bindAddr.isBlank() || it.dstAddr.isBlank() || it.proto.isBlank() }
        if (blanks > 0) {
            put("portForwards", ValidationMessage.Resource(R.string.error_port_forward_fields_required))
            return
        }
        if (profile.portForwards.any { !it.bindAddr.trim().isValidSocketAddr() || !it.dstAddr.trim().isValidSocketAddr() }) {
            put("portForwards", ValidationMessage.Resource(R.string.error_port_forward_addresses_invalid))
            return
        }
        if (profile.portForwards.any { it.proto.trim().lowercase() !in setOf("tcp", "udp") }) {
            put("portForwards", ValidationMessage.Resource(R.string.error_port_forward_protocol))
        }
    }
}

private fun String?.isValidIpv4Cidr(): Boolean {
    val value = this?.trim().orEmpty()
    val parts = value.split('/', limit = 2)
    val prefix = parts.getOrNull(1)?.toIntOrNull() ?: return false
    if (parts.size != 2 || prefix !in 0..32) return false
    return runCatching {
        val address = InetAddress.getByName(parts[0])
        address.address.size == 4 && address.hostAddress == parts[0]
    }.getOrDefault(false)
}

/**
 * The portal client CIDR is advertised to every peer as a reachable network
 * and becomes the source address of portal client traffic. Local-only ranges
 * (0.0.0.0/8, loopback 127/8, link-local 169.254/16, multicast 224/4 and
 * 255/8) would make return packets get swallowed by peers' local routing
 * tables, so they are rejected. The prefix must also leave room for client
 * addresses.
 */
private fun String?.isRoutablePortalCidr(): Boolean {
    val value = this?.trim().orEmpty()
    val parts = value.split('/', limit = 2)
    val prefix = parts.getOrNull(1)?.toIntOrNull() ?: return false
    if (prefix !in 8..28) return false
    val bytes = runCatching { InetAddress.getByName(parts[0]).address }.getOrNull() ?: return false
    if (bytes.size != 4) return false
    val first = bytes[0].toInt() and 0xFF
    val second = bytes[1].toInt() and 0xFF
    return when {
        first == 0 -> false                    // 0.0.0.0/8
        first == 127 -> false                  // loopback
        first == 169 && second == 254 -> false // link-local
        first >= 224 -> false                  // multicast + reserved (incl. broadcast)
        else -> true
    }
}

private fun String?.isValidIpv6Cidr(): Boolean {
    val value = this?.trim().orEmpty()
    val parts = value.split('/', limit = 2)
    val prefix = parts.getOrNull(1)?.toIntOrNull() ?: return false
    if (parts.size != 2 || prefix !in 0..128) return false
    return runCatching {
        val address = InetAddress.getByName(parts[0])
        address.address.size == 16
    }.getOrDefault(false)
}

private fun String?.isValidIp(): Boolean {
    val value = this?.trim().orEmpty()
    if (value.isEmpty()) return false
    return runCatching { InetAddress.getByName(value); true }.getOrDefault(false)
}

private fun String?.isValidSocketAddr(): Boolean {
    val value = this?.trim().orEmpty()
    val colon = value.lastIndexOf(':')
    if (colon <= 0) return false
    val host = value.substring(0, colon)
    val port = value.substring(colon + 1).toIntOrNull() ?: return false
    if (port !in 0..65535) return false
    return runCatching { InetAddress.getByName(host); true }.getOrDefault(false)
}

private fun String?.isValidListenerUrl(): Boolean {
    val value = this?.trim().orEmpty()
    if (value.isEmpty()) return false
    return value.contains("://")
}
