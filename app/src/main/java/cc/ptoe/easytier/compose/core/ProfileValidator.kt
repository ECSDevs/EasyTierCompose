package cc.ptoe.easytier.compose.core

import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.GlobalSettings
import java.net.InetAddress

class ProfileValidator(private val nativeParser: NativeConfigParser = EasyTierNativeConfigParser) {
    fun validate(
        profile: EasyTierProfile,
        globalSettings: GlobalSettings = GlobalSettings(),
    ): Map<String, String> = buildMap {
        if (profile.name.isBlank()) put("name", "Profile name is required")
        if (profile.networkName.isBlank()) put("networkName", "Network name is required")
        if (profile.tldDnsZone.isBlank()) put("tldDnsZone", "TLD DNS zone is required")
        if (profile.mtu !in 576..9000) put("mtu", "MTU must be between 576 and 9000")
        if (profile.multiThreadCount <= 0) put("multiThreadCount", "Thread count must be greater than 0")
        if (profile.foreignRelayBpsLimit < 0) put("foreignRelayBpsLimit", "Cannot be negative")
        if (profile.instanceRecvBpsLimit < 0) put("instanceRecvBpsLimit", "Cannot be negative")
        if (!profile.dhcp && !profile.virtualIpv4.isValidIpv4Cidr()) {
            put("virtualIpv4", "A valid IPv4 CIDR is required when DHCP is off")
        }
        profile.virtualIpv6?.takeIf { it.isNotBlank() }?.let {
            if (!it.isValidIpv6Cidr()) put("virtualIpv6", "Invalid IPv6 CIDR")
        }
        profile.ipv6PublicAddrPrefix?.takeIf { it.isNotBlank() }?.let {
            if (!it.isValidIpv6Cidr()) put("ipv6PublicAddrPrefix", "Invalid IPv6 CIDR")
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
            if (!portal.clientCidr.isValidIpv4Cidr()) put("vpnPortal", "Invalid client CIDR")
            if (!portal.wireguardListen.isValidSocketAddr()) put("vpnPortal", "Invalid WireGuard listen address")
        }
        if (globalSettings.socks5Port !in 1..65535) {
            put("globalSettings", "SOCKS5 port must be between 1 and 65535")
        }
        // Final native validation of the generated TOML.
        val toml = TomlConfigBuilder.build(profile, globalSettings)
        nativeParser.parse(toml)?.let { put("form", it) }
    }

    private fun MutableMap<String, String>.validateStringList(
        field: String,
        values: List<String>,
        itemCheck: ((String) -> Boolean)? = null,
    ) {
        if (values.any { it.isBlank() }) {
            put(field, "Entries cannot be blank")
        } else if (itemCheck != null && values.any { !itemCheck(it.trim()) }) {
            put(field, "One or more entries are invalid")
        }
    }

    private fun MutableMap<String, String>.validatePeers(profile: EasyTierProfile) {
        val blanks = profile.peers.count { it.uri.isBlank() }
        if (blanks > 0) {
            put("peers", "Peer URIs cannot be blank")
        } else if (profile.peers.any { !it.uri.trim().isValidListenerUrl() }) {
            put("peers", "One or more peer URIs are invalid")
        }
    }

    private fun MutableMap<String, String>.validateProxyNetworks(profile: EasyTierProfile) {
        val blanks = profile.proxyNetworks.count { it.cidr.isBlank() }
        if (blanks > 0) {
            put("proxyNetworks", "Proxy CIDRs cannot be blank")
            return
        }
        if (profile.proxyNetworks.any { !it.cidr.trim().isValidIpv4Cidr() }) {
            put("proxyNetworks", "One or more proxy CIDRs are invalid")
            return
        }
        if (profile.proxyNetworks.any { it.mappedCidr != null && it.mappedCidr.isNotBlank() && !it.mappedCidr.trim().isValidIpv4Cidr() }) {
            put("proxyNetworks", "One or more mapped CIDRs are invalid")
            return
        }
        val allowedProtos = setOf("tcp", "udp", "icmp")
        if (profile.proxyNetworks.any { net -> net.allow.any { it.trim().lowercase() !in allowedProtos } }) {
            put("proxyNetworks", "Allow entries must be tcp, udp, or icmp")
        }
    }

    private fun MutableMap<String, String>.validatePortForwards(profile: EasyTierProfile) {
        val blanks = profile.portForwards.count { it.bindAddr.isBlank() || it.dstAddr.isBlank() || it.proto.isBlank() }
        if (blanks > 0) {
            put("portForwards", "Bind, destination, and protocol are required")
            return
        }
        if (profile.portForwards.any { !it.bindAddr.trim().isValidSocketAddr() || !it.dstAddr.trim().isValidSocketAddr() }) {
            put("portForwards", "One or more port-forward addresses are invalid")
            return
        }
        if (profile.portForwards.any { it.proto.trim().lowercase() !in setOf("tcp", "udp") }) {
            put("portForwards", "Protocol must be tcp or udp")
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
