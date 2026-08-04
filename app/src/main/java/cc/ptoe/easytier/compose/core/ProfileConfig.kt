package cc.ptoe.easytier.compose.core

import cc.ptoe.easytier.compose.data.CompressionAlgo
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.EncryptionAlgorithm
import cc.ptoe.easytier.compose.data.GlobalSettings
import cc.ptoe.easytier.compose.data.TunMode
import cc.ptoe.easytier.compose.transport.root.RootTunSpec
import java.net.InetAddress

fun interface NativeConfigParser {
    /** Returns null when the document is valid, otherwise the native failure text. */
    fun parse(toml: String): String?
}

object EasyTierNativeConfigParser : NativeConfigParser {
    override fun parse(toml: String): String? = try {
        if (EasyTierJni.parseConfig(toml) == 0) null else EasyTierJni.getLastError() ?: "EasyTier rejected the configuration"
    } catch (error: RuntimeException) {
        EasyTierJni.getLastError() ?: error.message ?: "EasyTier rejected the configuration"
    }
}

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

object TomlConfigBuilder {
    /**
     * fwmark applied to every EasyTier socket in Root TUN mode (0x20000).
     *
     * Android's VpnService installs ip rule:
     *   13000: from all fwmark 0x0/0x20000 uidrange 0-99999 lookup tun0
     * matching any socket whose fwmark bit 17 is 0. Setting bit 17 = 1
     * bypasses this rule, and the system's own rule:
     *   31000: from all fwmark 0x0/0xffff lookup wlan0
     * routes the traffic through the physical interface (wlan0/eth0).
     */
    const val ROOT_TUN_SOCKET_MARK = 131072 // 0x20000

    fun build(
        profile: EasyTierProfile,
        globalSettings: GlobalSettings = GlobalSettings(),
    ): String = buildString {
        appendTomlString("instance_name", profile.id)
        profile.hostname?.trim()?.takeIf { it.isNotEmpty() }?.let { appendTomlString("hostname", it) }
        append("dhcp = ${profile.dhcp}\n")
        profile.virtualIpv4?.trim()?.takeIf { it.isNotEmpty() }?.let { appendTomlString("ipv4", it) }
        profile.virtualIpv6?.trim()?.takeIf { it.isNotEmpty() }?.let { appendTomlString("ipv6", it) }
        appendTomlArray("listeners", profile.listeners)
        appendTomlArray("mapped_listeners", profile.mappedListeners)
        appendTomlArray("exit_nodes", profile.exitNodes)
        appendTomlArray("routes", profile.manualRoutes)
        appendTomlArray("stun_servers", profile.stunServers)
        appendTomlArray("stun_servers_v6", profile.stunServersV6)
        appendTomlArray("tcp_whitelist", profile.tcpWhitelist)
        appendTomlArray("udp_whitelist", profile.udpWhitelist)
        if (profile.ipv6PublicAddrProvider) append("ipv6_public_addr_provider = true\n")
        if (profile.ipv6PublicAddrAuto) append("ipv6_public_addr_auto = true\n")
        profile.ipv6PublicAddrPrefix?.trim()?.takeIf { it.isNotEmpty() }?.let { appendTomlString("ipv6_public_addr_prefix", it) }
        val socks5Host = if (globalSettings.socks5AllowLan) "0.0.0.0" else "127.0.0.1"
        appendTomlString("socks5_proxy", "socks5://$socks5Host:${globalSettings.socks5Port}")

        append("\n[network_identity]\n")
        appendTomlString("network_name", profile.networkName.trim())
        appendTomlString("network_secret", profile.networkSecret)

        profile.peers.map { it.uri.trim() }.filter(String::isNotEmpty).forEach { uri ->
            append("\n[[peer]]\n")
            appendTomlString("uri", uri)
        }

        profile.proxyNetworks.forEach { network ->
            val cidr = network.cidr.trim()
            if (cidr.isEmpty()) return@forEach
            append("\n[[proxy_network]]\n")
            appendTomlString("cidr", cidr)
            network.mappedCidr?.trim()?.takeIf { it.isNotEmpty() }?.let { appendTomlString("mapped_cidr", it) }
            if (network.allow.isNotEmpty()) {
                appendTomlArray("allow", network.allow)
            }
        }

        profile.portForwards.forEach { forward ->
            append("\n[[port_forward]]\n")
            appendTomlString("bind_addr", forward.bindAddr.trim())
            appendTomlString("dst_addr", forward.dstAddr.trim())
            appendTomlString("proto", forward.proto.trim().lowercase())
        }

        profile.vpnPortal?.let { portal ->
            append("\n[vpn_portal_config]\n")
            appendTomlString("client_cidr", portal.clientCidr.trim())
            appendTomlString("wireguard_listen", portal.wireguardListen.trim())
        }

        if (profile.secureMode.enabled) {
            append("\n[secure_mode]\n")
            append("enabled = true\n")
            profile.secureMode.localPrivateKey?.takeIf { it.isNotEmpty() }?.let { appendTomlString("local_private_key", it) }
            profile.secureMode.localPublicKey?.takeIf { it.isNotEmpty() }?.let { appendTomlString("local_public_key", it) }
        }

        append("\n[flags]\n")
        appendTomlString("default_protocol", profile.defaultProtocol.trim())
        appendTomlString("dev_name", globalSettings.tunDeviceName.trim())
        append("enable_encryption = ${profile.enableEncryption}\n")
        append("enable_ipv6 = ${profile.enableIpv6}\n")
        append("mtu = ${profile.mtu}\n")
        append("latency_first = ${profile.latencyFirst}\n")
        append("enable_exit_node = ${profile.enableExitNode}\n")
        append("no_tun = ${globalSettings.noTun}\n")
        append("use_smoltcp = false\n")
        appendTomlString("relay_network_whitelist", profile.relayNetworkWhitelist)
        append("disable_p2p = ${profile.disableP2p}\n")
        append("p2p_only = ${profile.p2pOnly}\n")
        append("lazy_p2p = ${profile.lazyP2p}\n")
        append("relay_all_peer_rpc = ${profile.relayAllPeerRpc}\n")
        append("disable_tcp_hole_punching = ${profile.disableTcpHolePunching}\n")
        append("disable_udp_hole_punching = ${profile.disableUdpHolePunching}\n")
        append("disable_sym_hole_punching = ${profile.disableSymHolePunching}\n")
        append("disable_upnp = ${profile.disableUpnp}\n")
        append("multi_thread = ${profile.multiThread}\n")
        append("multi_thread_count = ${profile.multiThreadCount}\n")
        append("data_compress_algo = \"${profile.dataCompressAlgo.name}\"\n")
        // Root TUN mode: disable bind_device (SO_BINDTODEVICE) and set a fwmark
        // so Android's policy routing routes traffic through the physical
        // interface, bypassing VpnService/mihomo TUNs.
        //
        // Android's VpnService installs ip rule:
        //   13000: from all fwmark 0x0/0x20000 uidrange 0-99999 lookup tun0
        // This matches any socket whose fwmark bit 17 is 0, forcing traffic
        // through tun0. By setting socket_mark = 0x20000 (bit 17 = 1), we
        // bypass this rule and fall through to:
        //   31000: from all fwmark 0x0/0xffff lookup wlan0
        // (0x20000 & 0xffff == 0), which routes through the physical interface.
        //
        // bind_device=false avoids SO_BINDTODEVICE to tun0 and lets the fwmark
        // handle routing entirely. No Rust core changes or .so rebuild needed.
        if (profile.tunMode == TunMode.ROOT_TUN) {
            append("bind_device = false\n")
            append("socket_mark = ${ROOT_TUN_SOCKET_MARK}\n")
        } else {
            append("bind_device = ${profile.bindDevice}\n")
        }
        append("enable_kcp_proxy = ${profile.enableKcpProxy}\n")
        append("disable_kcp_input = ${profile.disableKcpInput}\n")
        append("disable_relay_kcp = ${profile.disableRelayKcp}\n")
        append("enable_relay_foreign_network_kcp = ${profile.enableRelayForeignNetworkKcp}\n")
        append("proxy_forward_by_system = ${profile.proxyForwardBySystem}\n")
        append("accept_dns = ${profile.enableMagicDns}\n")
        append("private_mode = ${profile.privateMode}\n")
        append("enable_quic_proxy = ${profile.enableQuicProxy}\n")
        append("disable_quic_input = ${profile.disableQuicInput}\n")
        append("disable_relay_quic = ${profile.disableRelayQuic}\n")
        append("enable_relay_foreign_network_quic = ${profile.enableRelayForeignNetworkQuic}\n")
        append("foreign_relay_bps_limit = ${profile.foreignRelayBpsLimit}\n")
        append("instance_recv_bps_limit = ${profile.instanceRecvBpsLimit}\n")
        appendTomlString("encryption_algorithm", profile.encryptionAlgorithm.toTomlString())
        appendTomlString("tld_dns_zone", profile.tldDnsZone.trim())
        append("disable_relay_data = ${profile.disableRelayData}\n")
        append("enable_udp_broadcast_relay = ${profile.enableUdpBroadcastRelay}\n")
    }

    fun rootTunSpec(
        profile: EasyTierProfile,
        globalSettings: GlobalSettings = GlobalSettings(),
    ): RootTunSpec = RootTunSpec(
        ipv4Cidr = profile.virtualIpv4?.trim(),
        mtu = profile.mtu,
        manualRoutes = profile.manualRoutes.map(String::trim),
        proxyCidrs = profile.proxyNetworks.map { it.cidr.trim() },
        devName = globalSettings.tunDeviceName.trim().ifBlank { "easytier0" },
        magicDns = profile.enableMagicDns,
    )

    private fun StringBuilder.appendTomlArray(key: String, values: List<String>) {
        val trimmed = values.map(String::trim).filter(String::isNotEmpty)
        if (trimmed.isNotEmpty()) append("$key = [${trimmed.joinToString { "\"${it.tomlEscape()}\"" }}]\n")
    }

    private fun StringBuilder.appendTomlString(key: String, value: String) {
        append("$key = \"${value.tomlEscape()}\"\n")
    }

    private fun String.tomlEscape() = replace("\\", "\\\\").replace("\"", "\\\"")

    private fun EncryptionAlgorithm.toTomlString(): String = when (this) {
        EncryptionAlgorithm.Xor -> "xor"
        EncryptionAlgorithm.AesGcm -> "aes-gcm"
        EncryptionAlgorithm.Aes256Gcm -> "aes-256-gcm"
        EncryptionAlgorithm.ChaCha20 -> "chacha20"
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
