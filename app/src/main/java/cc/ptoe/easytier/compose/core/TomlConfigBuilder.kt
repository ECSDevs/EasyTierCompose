package cc.ptoe.easytier.compose.core

import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.GlobalSettings
import cc.ptoe.easytier.compose.data.TunMode
import cc.ptoe.easytier.compose.data.mergeInto
import cc.ptoe.easytier.compose.transport.root.RootTunSpec

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
    ): String {
        // Global overrides are merged on top of the profile first; the builder
        // then serializes the effective configuration.
        val p = globalSettings.mergeInto(profile)
        return buildString {
            appendTomlString("instance_name", p.id)
            p.hostname?.trim()?.takeIf { it.isNotEmpty() }?.let { appendTomlString("hostname", it) }
            append("dhcp = ${p.dhcp}\n")
            p.virtualIpv4?.trim()?.takeIf { it.isNotEmpty() }?.let { appendTomlString("ipv4", it) }
            p.virtualIpv6?.trim()?.takeIf { it.isNotEmpty() }?.let { appendTomlString("ipv6", it) }
            appendTomlArray("listeners", p.listeners)
            appendTomlArray("mapped_listeners", p.mappedListeners)
            appendTomlArray("exit_nodes", p.exitNodes)
            appendTomlArray("routes", p.manualRoutes)
            appendTomlArray("stun_servers", p.stunServers)
            appendTomlArray("tcp_stun_servers", p.tcpStunServers)
            appendTomlArray("stun_servers_v6", p.stunServersV6)
            appendTomlArray("tcp_whitelist", p.tcpWhitelist)
            appendTomlArray("udp_whitelist", p.udpWhitelist)
            if (p.ipv6PublicAddrProvider) append("ipv6_public_addr_provider = true\n")
            if (p.ipv6PublicAddrAuto) append("ipv6_public_addr_auto = true\n")
            p.ipv6PublicAddrPrefix?.trim()?.takeIf { it.isNotEmpty() }?.let { appendTomlString("ipv6_public_addr_prefix", it) }

            append("\n[network_identity]\n")
            appendTomlString("network_name", p.networkName.trim())
            appendTomlString("network_secret", p.networkSecret)

            p.peers.map { it.uri.trim() }.filter(String::isNotEmpty).forEach { uri ->
                append("\n[[peer]]\n")
                appendTomlString("uri", uri)
            }

            p.proxyNetworks.forEach { network ->
                val cidr = network.cidr.trim()
                if (cidr.isEmpty()) return@forEach
                append("\n[[proxy_network]]\n")
                appendTomlString("cidr", cidr)
                network.mappedCidr?.trim()?.takeIf { it.isNotEmpty() }?.let { appendTomlString("mapped_cidr", it) }
                if (network.allow.isNotEmpty()) {
                    appendTomlArray("allow", network.allow)
                }
            }

            p.portForwards.forEach { forward ->
                append("\n[[port_forward]]\n")
                appendTomlString("bind_addr", forward.bindAddr.trim())
                appendTomlString("dst_addr", forward.dstAddr.trim())
                appendTomlString("proto", forward.proto.trim().lowercase())
            }

            p.socks5Proxy?.trim()?.takeIf { it.isNotEmpty() }?.let { appendTomlString("socks5_proxy", it) }

            p.vpnPortal?.let { portal ->
                append("\n[vpn_portal_config]\n")
                appendTomlString("wireguard_listen", portal.wireguardListen.trim())
                portal.wireguardPrivateKey?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { appendTomlString("wireguard_private_key", it) }
                portal.clients.forEach { client ->
                    val name = client.name.trim()
                    if (name.isEmpty()) return@forEach
                    append("\n[[vpn_portal_config.clients]]\n")
                    appendTomlString("name", name)
                    appendTomlString("virtual_ip", client.virtualIp.trim())
                    if (client.groups.isNotEmpty()) {
                        appendTomlArray("groups", client.groups)
                    }
                }
            }

            p.acl?.let { acl ->
                append("\n[acl.acl_v1]\n")
                acl.chains.forEach { chain ->
                    append("\n[[acl.acl_v1.chains]]\n")
                    appendTomlString("name", chain.name.trim())
                    append("chain_type = ${chain.chainType.value}\n")
                    chain.description.trim().takeIf { it.isNotEmpty() }
                        ?.let { appendTomlString("description", it) }
                    append("enabled = ${chain.enabled}\n")
                    append("default_action = ${chain.defaultAction.value}\n")
                    chain.rules.forEach { rule ->
                        val ruleName = rule.name.trim()
                        if (ruleName.isEmpty()) return@forEach
                        append("\n[[acl.acl_v1.chains.rules]]\n")
                        appendTomlString("name", ruleName)
                        rule.description.trim().takeIf { it.isNotEmpty() }
                            ?.let { appendTomlString("description", it) }
                        append("priority = ${rule.priority}\n")
                        append("enabled = ${rule.enabled}\n")
                        append("protocol = ${rule.protocol.value}\n")
                        if (rule.ports.isNotEmpty()) appendTomlArray("ports", rule.ports)
                        if (rule.sourceIps.isNotEmpty()) appendTomlArray(
                            "source_ips",
                            rule.sourceIps
                        )
                        if (rule.destinationIps.isNotEmpty()) appendTomlArray(
                            "destination_ips",
                            rule.destinationIps
                        )
                        if (rule.sourcePorts.isNotEmpty()) appendTomlArray(
                            "source_ports",
                            rule.sourcePorts
                        )
                        append("action = ${rule.action.value}\n")
                        if (rule.rateLimit > 0) append("rate_limit = ${rule.rateLimit}\n")
                        if (rule.burstLimit > 0) append("burst_limit = ${rule.burstLimit}\n")
                        if (rule.stateful) append("stateful = true\n")
                        if (rule.sourceGroups.isNotEmpty()) appendTomlArray(
                            "source_groups",
                            rule.sourceGroups
                        )
                        if (rule.destinationGroups.isNotEmpty()) appendTomlArray(
                            "destination_groups",
                            rule.destinationGroups
                        )
                    }
                }
                if (acl.group.declares.isNotEmpty() || acl.group.members.isNotEmpty()) {
                    append("\n[acl.acl_v1.group]\n")
                    if (acl.group.members.isNotEmpty()) appendTomlArray(
                        "members",
                        acl.group.members
                    )
                    acl.group.declares.forEach { declare ->
                        append("\n[[acl.acl_v1.group.declares]]\n")
                        appendTomlString("group_name", declare.groupName.trim())
                        appendTomlString("group_secret", declare.groupSecret)
                    }
                }
            }

            p.credentialFile?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { appendTomlString("credential_file", it) }
            p.managedCredentials.forEach { credential ->
                val id = credential.credentialId.trim()
                if (id.isEmpty()) return@forEach
                append("\n[[managed_credentials]]\n")
                appendTomlString("credential_id", id)
                appendTomlString("credential_secret", credential.credentialSecret)
                if (credential.groups.isNotEmpty()) appendTomlArray("groups", credential.groups)
                if (credential.allowRelay) append("allow_relay = true\n")
                if (credential.allowedProxyCidrs.isNotEmpty()) appendTomlArray(
                    "allowed_proxy_cidrs",
                    credential.allowedProxyCidrs
                )
                append("expiry_unix = ${credential.expiryUnix}\n")
                append("reusable = ${credential.reusable}\n")
            }

            if (p.secureMode.enabled) {
                append("\n[secure_mode]\n")
                append("enabled = true\n")
                p.secureMode.localPrivateKey?.takeIf { it.isNotEmpty() }?.let { appendTomlString("local_private_key", it) }
                p.secureMode.localPublicKey?.takeIf { it.isNotEmpty() }?.let { appendTomlString("local_public_key", it) }
            }

            append("\n[flags]\n")
            appendTomlString("default_protocol", p.defaultProtocol.trim())
            appendTomlString("dev_name", p.tunDeviceName.trim())
            append("enable_encryption = ${p.enableEncryption}\n")
            append("enable_ipv6 = ${p.enableIpv6}\n")
            append("mtu = ${p.mtu}\n")
            append("latency_first = ${p.latencyFirst}\n")
            append("enable_exit_node = ${p.enableExitNode}\n")
            append("no_tun = ${p.tunMode == TunMode.NO_TUN}\n")
            append("use_smoltcp = false\n")
            // Magic DNS requires a TUN interface to intercept DNS queries. In
            // no_tun mode there is no TUN, so accept_dns must be false to avoid
            // starting a Magic DNS server that cannot function.
            val effectiveAcceptDns = p.enableMagicDns && p.tunMode != TunMode.NO_TUN
            appendTomlString("relay_network_whitelist", p.relayNetworkWhitelist)
            append("disable_p2p = ${p.disableP2p}\n")
            append("p2p_only = ${p.p2pOnly}\n")
            append("lazy_p2p = ${p.lazyP2p}\n")
            append("need_p2p = ${p.needP2p}\n")
            append("relay_all_peer_rpc = ${p.relayAllPeerRpc}\n")
            append("disable_tcp_hole_punching = ${p.disableTcpHolePunching}\n")
            append("disable_udp_hole_punching = ${p.disableUdpHolePunching}\n")
            append("disable_sym_hole_punching = ${p.disableSymHolePunching}\n")
            append("disable_upnp = ${p.disableUpnp}\n")
            append("multi_thread = ${p.multiThread}\n")
            append("multi_thread_count = ${p.multiThreadCount}\n")
            append("data_compress_algo = \"${p.dataCompressAlgo.name}\"\n")
            // bind_device (SO_BINDTODEVICE) requires CAP_NET_RAW, which neither the
            // app process (VPN_SERVICE / no_tun) nor the root process needs to use
            // except for the ROOT_TUN physical-routing path. In no_tun mode there
            // is no TUN device to bind to, so bind_device must be false regardless
            // of tunMode — otherwise EasyTier fails with EPERM on every socket.
            //
            // Root mode: disable bind_device and set a fwmark so Android's
            // policy routing routes traffic through the physical interface,
            // bypassing VpnService/mihomo TUNs. This applies to no_tun as well:
            // the core then runs in the root daemon and must keep using the
            // physical NIC instead of any system VPN/proxy TUN.
            //
            // Android's VpnService installs ip rule:
            //   13000: from all fwmark 0x0/0x20000 uidrange 0-99999 lookup tun0
            // This matches any socket whose fwmark bit 17 is 0, forcing traffic
            // through tun0. By setting socket_mark = 0x20000 (bit 17 = 1), we
            // bypass this rule and fall through to:
            //   31000: from all fwmark 0x0/0xffff lookup wlan0
            // (0x20000 & 0xffff == 0), which routes through the physical interface.
            //
            // socket_mark bypasses Android's VpnService policy routing via fwmark.
            // setsockopt(SO_MARK) requires CAP_NET_ADMIN, which only the root
            // process has. ROOT_TUN sessions always run in the root daemon
            // (including no_tun), so the mark can always be applied.
            val effectiveBindDevice = p.tunMode != TunMode.NO_TUN &&
                p.tunMode != TunMode.ROOT_TUN &&
                p.bindDevice
            append("bind_device = $effectiveBindDevice\n")
            if (p.tunMode == TunMode.ROOT_TUN) {
                // A user-supplied fwmark overrides the built-in ROOT_TUN mark.
                append("socket_mark = ${p.socketMark ?: ROOT_TUN_SOCKET_MARK}\n")
            }
            append("enable_kcp_proxy = ${p.enableKcpProxy}\n")
            append("disable_kcp_input = ${p.disableKcpInput}\n")
            append("disable_relay_kcp = ${p.disableRelayKcp}\n")
            append("enable_relay_foreign_network_kcp = ${p.enableRelayForeignNetworkKcp}\n")
            append("proxy_forward_by_system = ${p.proxyForwardBySystem}\n")
            append("accept_dns = $effectiveAcceptDns\n")
            append("private_mode = ${p.privateMode}\n")
            append("enable_quic_proxy = ${p.enableQuicProxy}\n")
            append("disable_quic_input = ${p.disableQuicInput}\n")
            append("disable_relay_quic = ${p.disableRelayQuic}\n")
            append("enable_relay_foreign_network_quic = ${p.enableRelayForeignNetworkQuic}\n")
            append("foreign_relay_bps_limit = ${p.foreignRelayBpsLimit}\n")
            append("instance_recv_bps_limit = ${p.instanceRecvBpsLimit}\n")
            appendTomlString("encryption_algorithm", p.encryptionAlgorithm.toTomlString())
            appendTomlString("tld_dns_zone", p.tldDnsZone.trim())
            append("disable_relay_data = ${p.disableRelayData}\n")
            append("enable_udp_broadcast_relay = ${p.enableUdpBroadcastRelay}\n")
        }
    }

    fun rootTunSpec(
        profile: EasyTierProfile,
        globalSettings: GlobalSettings = GlobalSettings(),
    ): RootTunSpec {
        val p = globalSettings.mergeInto(profile)
        return RootTunSpec(
            ipv4Cidr = p.virtualIpv4?.trim(),
            mtu = p.mtu,
            manualRoutes = p.manualRoutes.map(String::trim),
            proxyCidrs = p.proxyNetworks.map { it.cidr.trim() },
            devName = p.tunDeviceName.trim().ifBlank { "easytier0" },
            // Magic DNS needs a TUN to intercept DNS; impossible in no_tun mode.
            magicDns = p.enableMagicDns && p.tunMode != TunMode.NO_TUN,
            wireguardPortal = p.vpnPortal != null,
        )
    }

    private fun StringBuilder.appendTomlArray(key: String, values: List<String>) {
        val trimmed = values.map(String::trim).filter(String::isNotEmpty)
        if (trimmed.isNotEmpty()) append("$key = [${trimmed.joinToString { "\"${it.tomlEscape()}\"" }}]\n")
    }

    private fun StringBuilder.appendTomlString(key: String, value: String) {
        append("$key = \"${value.tomlEscape()}\"\n")
    }

    private fun String.tomlEscape() = replace("\\", "\\\\").replace("\"", "\\\"")

    private fun cc.ptoe.easytier.compose.data.EncryptionAlgorithm.toTomlString(): String = when (this) {
        cc.ptoe.easytier.compose.data.EncryptionAlgorithm.Xor -> "xor"
        cc.ptoe.easytier.compose.data.EncryptionAlgorithm.AesGcm -> "aes-gcm"
        cc.ptoe.easytier.compose.data.EncryptionAlgorithm.Aes256Gcm -> "aes-256-gcm"
        cc.ptoe.easytier.compose.data.EncryptionAlgorithm.ChaCha20 -> "chacha20"
    }
}
