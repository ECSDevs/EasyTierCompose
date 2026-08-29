package cc.ptoe.easytier.compose.core

import android.content.Context
import androidx.annotation.StringRes
import cc.ptoe.easytier.compose.R
import cc.ptoe.easytier.compose.data.Acl
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.GlobalSettings
import cc.ptoe.easytier.compose.data.ManagedCredential
import cc.ptoe.easytier.compose.data.mergeInto
import java.net.InetAddress

sealed interface ValidationMessage {
    data class Resource(@param:StringRes val id: Int) : ValidationMessage

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
        // Global overrides merge on top of the profile; validation runs on the
        // effective configuration that would actually be built.
        val effective = globalSettings.mergeInto(profile)
        if (effective.name.isBlank()) put("name", ValidationMessage.Resource(R.string.error_profile_name_required))
        if (effective.networkName.isBlank()) put("networkName", ValidationMessage.Resource(R.string.error_network_name_required))
        if (effective.tldDnsZone.isBlank()) put("tldDnsZone", ValidationMessage.Resource(R.string.error_tld_dns_zone_required))
        if (!effective.dhcp && !effective.virtualIpv4.isValidIpv4Cidr()) {
            put("virtualIpv4", ValidationMessage.Resource(R.string.error_ipv4_cidr_required_dhcp_off))
        }
        effective.virtualIpv6?.takeIf { it.isNotBlank() }?.let {
            if (!it.isValidIpv6Cidr()) put("virtualIpv6", ValidationMessage.Resource(R.string.error_invalid_ipv6_cidr))
        }
        effective.ipv6PublicAddrPrefix?.takeIf { it.isNotBlank() }?.let {
            if (!it.isValidIpv6Cidr()) put("ipv6PublicAddrPrefix", ValidationMessage.Resource(R.string.error_invalid_ipv6_cidr))
        }
        validateStringList("listeners", effective.listeners) { it.isValidListenerUrl() }
        validateStringList("mappedListeners", effective.mappedListeners) { it.isValidListenerUrl() }
        validateStringList("manualRoutes", effective.manualRoutes) { it.isValidIpv4Cidr() }
        validateStringList("exitNodes", effective.exitNodes) { it.isValidIp() }
        validateStringList("stunServers", effective.stunServers)
        validateStringList("tcpStunServers", effective.tcpStunServers)
        validateStringList("stunServersV6", effective.stunServersV6)
        validateStringList("tcpWhitelist", effective.tcpWhitelist)
        validateStringList("udpWhitelist", effective.udpWhitelist)
        validatePeers(effective)
        validateProxyNetworks(effective)
        validatePortForwards(effective)
        effective.vpnPortal?.let { portal ->
            if (!portal.wireguardListen.isValidSocketAddr()) put("vpnPortal", ValidationMessage.Resource(R.string.error_invalid_wireguard_listen))
            val clientNames = mutableSetOf<String>()
            val clientIps = mutableSetOf<String>()
            portal.clients.forEach { client ->
                val name = client.name.trim()
                when {
                    name.isEmpty() -> put(
                        "vpnPortal",
                        ValidationMessage.Resource(R.string.error_portal_client_name_required)
                    )

                    !clientNames.add(name) -> put(
                        "vpnPortal",
                        ValidationMessage.Resource(R.string.error_portal_client_duplicate_name)
                    )
                }
                val ip = client.virtualIp.trim()
                when {
                    !ip.isValidIpv4Host() -> put(
                        "vpnPortal",
                        ValidationMessage.Resource(R.string.error_portal_client_invalid_ip)
                    )

                    !clientIps.add(ip) -> put(
                        "vpnPortal",
                        ValidationMessage.Resource(R.string.error_portal_client_duplicate_ip)
                    )
                }
            }
        }
        validateAcl(effective.acl)
        validateManagedCredentials(effective.managedCredentials)
        effective.socks5Proxy?.takeIf { it.isNotBlank() }?.let {
            if (!it.isValidSocks5Proxy()) put("socks5Proxy", ValidationMessage.Resource(R.string.error_invalid_socks5_proxy))
        }
        // Device-local (engine) values of the effective profile.
        if (effective.mtu !in 576..9000) put("mtu", ValidationMessage.Resource(R.string.error_mtu_range))
        if (effective.multiThreadCount <= 0) put("multiThreadCount", ValidationMessage.Resource(R.string.error_thread_count_positive))
        if (effective.foreignRelayBpsLimit < 0) put("foreignRelayBpsLimit", ValidationMessage.Resource(R.string.error_non_negative))
        if (effective.instanceRecvBpsLimit < 0) put("instanceRecvBpsLimit", ValidationMessage.Resource(R.string.error_non_negative))
        effective.socketMark?.let {
            if (it !in 0..0xFFFFFFFFL) put("socketMark", ValidationMessage.Resource(R.string.error_socket_mark_range))
        }
        // Per-field checks for the global overrides themselves (distinct keys so a
        // bad override is distinguishable from a bad profile value).
        globalSettings.mtu?.let { if (it !in 576..9000) put("globalMtu", ValidationMessage.Resource(R.string.error_mtu_range)) }
        globalSettings.multiThreadCount?.let { if (it <= 0) put("globalMultiThreadCount", ValidationMessage.Resource(R.string.error_thread_count_positive)) }
        globalSettings.foreignRelayBpsLimit?.let { if (it < 0) put("globalForeignRelayBpsLimit", ValidationMessage.Resource(R.string.error_non_negative)) }
        globalSettings.instanceRecvBpsLimit?.let { if (it < 0) put("globalInstanceRecvBpsLimit", ValidationMessage.Resource(R.string.error_non_negative)) }
        globalSettings.socketMark?.let {
            if (it !in 0..0xFFFFFFFFL) put("globalSocketMark", ValidationMessage.Resource(R.string.error_socket_mark_range))
        }
        globalSettings.socks5Proxy?.takeIf { it.isNotBlank() }?.let {
            if (!it.isValidSocks5Proxy()) put("globalSocks5Proxy", ValidationMessage.Resource(R.string.error_invalid_socks5_proxy))
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

    private fun MutableMap<String, ValidationMessage>.validateAcl(acl: Acl?) {
        if (acl == null) return
        if (acl.chains.any { it.name.isBlank() }) {
            put("acl", ValidationMessage.Resource(R.string.error_acl_chain_name_required))
        }
        if (acl.chains.any { chain -> chain.rules.any { it.name.isBlank() } }) {
            put("acl", ValidationMessage.Resource(R.string.error_acl_rule_name_required))
        }
        if (acl.group.declares.any { it.groupName.isBlank() }) {
            put("acl", ValidationMessage.Resource(R.string.error_acl_group_name_required))
        }
        val groupNames = acl.group.declares.map { it.groupName.trim() }.filter(String::isNotEmpty)
        if (groupNames.size != groupNames.toSet().size) {
            put("acl", ValidationMessage.Resource(R.string.error_acl_group_duplicate))
        }
    }

    private fun MutableMap<String, ValidationMessage>.validateManagedCredentials(credentials: List<ManagedCredential>) {
        credentials.forEach { credential ->
            if (credential.credentialId.isBlank()) put(
                "managedCredentials",
                ValidationMessage.Resource(R.string.error_credential_id_required)
            )
            if (credential.credentialSecret.isBlank()) put(
                "managedCredentials",
                ValidationMessage.Resource(R.string.error_credential_secret_required)
            )
            if (credential.expiryUnix <= 0) put(
                "managedCredentials",
                ValidationMessage.Resource(R.string.error_credential_expiry)
            )
            if (credential.allowedProxyCidrs.any { !it.trim().isValidIpv4Cidr() }) {
                put(
                    "managedCredentials",
                    ValidationMessage.Resource(R.string.error_credential_proxy_cidrs_invalid)
                )
            }
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
 * A bare IPv4 host address (no CIDR prefix). Used for VPN portal client
 * virtual IPs, which the core parses as `std::net::Ipv4Addr`.
 */
private fun String?.isValidIpv4Host(): Boolean {
    val value = this?.trim().orEmpty()
    if (value.isEmpty() || value.contains('/')) return false
    return runCatching {
        val address = InetAddress.getByName(value)
        address.address.size == 4 && address.hostAddress == value
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

/**
 * The core parses `socks5_proxy` as a URL and requires a host and an explicit
 * port (see CoreInstanceConfig::from_toml). Accept only socks5:// URLs with a
 * host and a valid 1..65535 port.
 */
private fun String?.isValidSocks5Proxy(): Boolean {
    val value = this?.trim().orEmpty()
    if (value.isEmpty()) return false
    return runCatching {
        val uri = java.net.URI.create(value)
        uri.scheme.equals("socks5", ignoreCase = true) &&
            !uri.host.isNullOrEmpty() &&
            uri.port in 1..65535
    }.getOrDefault(false)
}
