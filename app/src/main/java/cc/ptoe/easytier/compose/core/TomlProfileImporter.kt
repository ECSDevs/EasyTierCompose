package cc.ptoe.easytier.compose.core

import cc.ptoe.easytier.compose.data.Acl
import cc.ptoe.easytier.compose.data.AclAction
import cc.ptoe.easytier.compose.data.AclChain
import cc.ptoe.easytier.compose.data.AclChainType
import cc.ptoe.easytier.compose.data.AclGroup
import cc.ptoe.easytier.compose.data.AclGroupDeclare
import cc.ptoe.easytier.compose.data.AclProtocol
import cc.ptoe.easytier.compose.data.AclRule
import cc.ptoe.easytier.compose.data.CompressionAlgo
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.EncryptionAlgorithm
import cc.ptoe.easytier.compose.data.ManagedCredential
import cc.ptoe.easytier.compose.data.Peer
import cc.ptoe.easytier.compose.data.PortForward
import cc.ptoe.easytier.compose.data.ProxyNetwork
import cc.ptoe.easytier.compose.data.SecureMode
import cc.ptoe.easytier.compose.data.TunMode
import cc.ptoe.easytier.compose.data.VpnPortal
import cc.ptoe.easytier.compose.data.VpnPortalClient
import org.tomlj.Toml
import org.tomlj.TomlParseResult
import org.tomlj.TomlTable
import java.util.UUID

sealed interface TomlImportResult {
    data class Success(val profile: EasyTierProfile) : TomlImportResult

    data class Failure(val message: String) : TomlImportResult
}

/**
 * Parses an EasyTier TOML configuration (as produced by `easytier-cli` or by
 * [TomlConfigBuilder]) back into an [EasyTierProfile].
 *
 * The reverse mapping mirrors the forward serialization in `TomlConfigBuilder`
 * and the core's own `Config` structure (`easytier-core/src/config/toml.rs`).
 * Unknown keys are ignored so that configs carrying extra sections (logging,
 * `source`, `use_smoltcp`, ...) still import cleanly.
 *
 * Fields without a TOML representation (profile id / name / TunMode / engine
 * defaults) fall back to sensible values:
 *  - `id` is a fresh UUID; the original `instance_name` is preserved in `name`
 *    so the imported instance stays identifiable.
 *  - [TunMode] is inferred: `no_tun = true` → [TunMode.NO_TUN]; a `socket_mark`
 *    flag → [TunMode.ROOT_TUN] (only Root mode serializes it); otherwise
 *    [TunMode.VPN_SERVICE].
 */
object TomlProfileImporter {
    private const val MAX_DOCUMENT_CHARS = 1_000_000

    fun import(toml: String): TomlImportResult {
        return try {
            // Config files often carry a UTF-8 BOM, and UTF-16 files decoded as
            // UTF-8 interleave a NUL per character. Both would trip the parser.
            val normalized = sanitize(toml)
            if (normalized.length > MAX_DOCUMENT_CHARS) {
                return TomlImportResult.Failure("Configuration is too large")
            }
            val result = Toml.parse(normalized)
            result.errors().firstOrNull()?.let { error ->
                return TomlImportResult.Failure(error.message ?: "Invalid TOML syntax")
            }
            val identity = result.getTable("network_identity")
                ?: return TomlImportResult.Failure("Missing [network_identity] section")
            val networkName = identity.stringOr("network_name")?.trim().orEmpty()
            if (networkName.isEmpty()) {
                return TomlImportResult.Failure("network_name is missing in [network_identity]")
            }

            val flags = result.getTable("flags")
            val instanceName = result.stringOr("instance_name")?.trim()?.takeIf { it.isNotEmpty() }
                ?: result.stringOr("hostname")?.trim()?.takeIf { it.isNotEmpty() }
            val hasSocketMark = flags?.contains("socket_mark") == true
            val tunMode = when {
                flags.boolOr("no_tun", false) -> TunMode.NO_TUN
                hasSocketMark -> TunMode.ROOT_TUN
                else -> TunMode.VPN_SERVICE
            }

            TomlImportResult.Success(
                EasyTierProfile(
                    id = UUID.randomUUID().toString(),
                    name = instanceName ?: networkName,
                    hostname = result.stringOr("hostname")?.trim()?.takeIf { it.isNotEmpty() },
                    networkName = networkName,
                    networkSecret = identity.stringOr("network_secret").orEmpty(),
                    peers = result.arrayOfTables("peer").mapNotNull { peerTable ->
                        val uri = peerTable.stringOr("uri")?.trim()?.takeIf { it.isNotEmpty() }
                            ?: return@mapNotNull null
                        Peer(uri = uri, peerPublicKey = peerTable.stringOr("peer_public_key"))
                    },
                    listeners = result.stringsOrEmpty("listeners"),
                    mappedListeners = result.stringsOrEmpty("mapped_listeners"),
                    virtualIpv4 = result.stringOr("ipv4")?.trim()?.takeIf { it.isNotEmpty() },
                    virtualIpv6 = result.stringOr("ipv6")?.trim()?.takeIf { it.isNotEmpty() },
                    dhcp = result.boolOr("dhcp", false),
                    proxyNetworks = result.arrayOfTables("proxy_network").mapNotNull { network ->
                        val cidr = network.stringOr("cidr")?.trim()?.takeIf { it.isNotEmpty() }
                            ?: return@mapNotNull null
                        ProxyNetwork(
                            cidr = cidr,
                            mappedCidr = network.stringOr("mapped_cidr"),
                            allow = network.stringsOrEmpty("allow"),
                        )
                    },
                    manualRoutes = result.stringsOrEmpty("routes"),
                    exitNodes = result.stringsOrEmpty("exit_nodes"),
                    enableMagicDns = flags.boolOr("accept_dns", false),
                    tunMode = tunMode,
                    ipv6PublicAddrProvider = result.boolOr("ipv6_public_addr_provider", false),
                    ipv6PublicAddrAuto = result.boolOr("ipv6_public_addr_auto", false),
                    ipv6PublicAddrPrefix = result.stringOr("ipv6_public_addr_prefix")
                        ?.trim()?.takeIf { it.isNotEmpty() },
                    portForwards = result.arrayOfTables("port_forward").mapNotNull { forward ->
                        val bind = forward.stringOr("bind_addr")?.trim()?.takeIf { it.isNotEmpty() }
                            ?: return@mapNotNull null
                        val dest = forward.stringOr("dst_addr")?.trim()?.takeIf { it.isNotEmpty() }
                            ?: return@mapNotNull null
                        PortForward(
                            bindAddr = bind,
                            dstAddr = dest,
                            proto = forward.stringOr("proto")?.trim()?.lowercase().orEmpty(),
                        )
                    },
                    socks5Proxy = result.stringOr("socks5_proxy")?.trim()?.takeIf { it.isNotEmpty() },
                    vpnPortal = result.getTable("vpn_portal_config")?.let { portal ->
                        VpnPortal(
                            wireguardListen = portal.stringOr("wireguard_listen") ?: "0.0.0.0:11011",
                            wireguardPrivateKey = portal.stringOr("wireguard_private_key"),
                            clients = portal.arrayOfTables("clients").mapNotNull { client ->
                                val name = client.stringOr("name")?.trim()?.takeIf { it.isNotEmpty() }
                                    ?: return@mapNotNull null
                                VpnPortalClient(
                                    name = name,
                                    virtualIp = client.stringOr("virtual_ip").orEmpty(),
                                    groups = client.stringsOrEmpty("groups"),
                                )
                            },
                        )
                    },
                    secureMode = run {
                        val secure = result.getTable("secure_mode")
                        SecureMode(
                            enabled = secure.boolOr("enabled", false),
                            localPrivateKey = secure.stringOr("local_private_key"),
                            localPublicKey = secure.stringOr("local_public_key"),
                        )
                    },
                    stunServers = result.stringsOrEmpty("stun_servers"),
                    tcpStunServers = result.stringsOrEmpty("tcp_stun_servers"),
                    stunServersV6 = result.stringsOrEmpty("stun_servers_v6"),
                    tcpWhitelist = result.stringsOrEmpty("tcp_whitelist"),
                    udpWhitelist = result.stringsOrEmpty("udp_whitelist"),
                    defaultProtocol = flags.stringOr("default_protocol") ?: "tcp",
                    enableEncryption = flags.boolOr("enable_encryption", true),
                    enableIpv6 = flags.boolOr("enable_ipv6", true),
                    latencyFirst = flags.boolOr("latency_first", false),
                    enableExitNode = flags.boolOr("enable_exit_node", false),
                    relayNetworkWhitelist = flags.stringOr("relay_network_whitelist") ?: "*",
                    disableP2p = flags.boolOr("disable_p2p", false),
                    p2pOnly = flags.boolOr("p2p_only", false),
                    lazyP2p = flags.boolOr("lazy_p2p", false),
                    needP2p = flags.boolOr("need_p2p", false),
                    relayAllPeerRpc = flags.boolOr("relay_all_peer_rpc", false),
                    disableTcpHolePunching = flags.boolOr("disable_tcp_hole_punching", false),
                    disableUdpHolePunching = flags.boolOr("disable_udp_hole_punching", false),
                    disableSymHolePunching = flags.boolOr("disable_sym_hole_punching", false),
                    disableUpnp = flags.boolOr("disable_upnp", false),
                    dataCompressAlgo = flags.stringOr("data_compress_algo")?.let(::parseCompression)
                        ?: CompressionAlgo.None,
                    bindDevice = flags.boolOr("bind_device", true),
                    enableKcpProxy = flags.boolOr("enable_kcp_proxy", false),
                    disableKcpInput = flags.boolOr("disable_kcp_input", false),
                    disableRelayKcp = flags.boolOr("disable_relay_kcp", false),
                    enableRelayForeignNetworkKcp = flags.boolOr("enable_relay_foreign_network_kcp", false),
                    proxyForwardBySystem = flags.boolOr("proxy_forward_by_system", false),
                    privateMode = flags.boolOr("private_mode", false),
                    enableQuicProxy = flags.boolOr("enable_quic_proxy", false),
                    disableQuicInput = flags.boolOr("disable_quic_input", false),
                    disableRelayQuic = flags.boolOr("disable_relay_quic", false),
                    enableRelayForeignNetworkQuic = flags.boolOr("enable_relay_foreign_network_quic", false),
                    encryptionAlgorithm = flags.stringOr("encryption_algorithm")?.let(::parseEncryption)
                        ?: EncryptionAlgorithm.AesGcm,
                    tldDnsZone = flags.stringOr("tld_dns_zone") ?: "et.net.",
                    disableRelayData = flags.boolOr("disable_relay_data", false),
                    enableUdpBroadcastRelay = flags.boolOr("enable_udp_broadcast_relay", false),
                    // Device-local engine options default like a freshly created profile.
                    tunDeviceName = flags.stringOr("dev_name")?.trim()?.ifBlank { "easytier0" } ?: "easytier0",
                    startOnBoot = false,
                    mtu = flags.longOr("mtu", 1380).toInt(),
                    multiThread = flags.boolOr("multi_thread", true),
                    multiThreadCount = flags.longOr("multi_thread_count", 2).toInt(),
                    foreignRelayBpsLimit = flags.longOr("foreign_relay_bps_limit", Long.MAX_VALUE),
                    instanceRecvBpsLimit = flags.longOr("instance_recv_bps_limit", Long.MAX_VALUE),
                    socketMark = if (hasSocketMark) flags.longOr("socket_mark", 0) else null,
                    acl = parseAcl(result),
                    managedCredentials = result.arrayOfTables("managed_credentials").mapNotNull { credential ->
                        val id = credential.stringOr("credential_id")?.trim()?.takeIf { it.isNotEmpty() }
                            ?: return@mapNotNull null
                        ManagedCredential(
                            credentialId = id,
                            credentialSecret = credential.stringOr("credential_secret").orEmpty(),
                            groups = credential.stringsOrEmpty("groups"),
                            allowRelay = credential.boolOr("allow_relay", false),
                            allowedProxyCidrs = credential.stringsOrEmpty("allowed_proxy_cidrs"),
                            expiryUnix = credential.longOr("expiry_unix", 0),
                            reusable = credential.boolOr("reusable", true),
                        )
                    },
                    credentialFile = result.stringOr("credential_file")?.trim()?.takeIf { it.isNotEmpty() },
                ),
            )
        } catch (error: RuntimeException) {
            TomlImportResult.Failure(error.message ?: "Failed to parse TOML configuration")
        }
    }

    private fun parseAcl(result: TomlParseResult): Acl? {
        val aclV1 = result.getTable("acl.acl_v1") ?: result.getTable("acl")?.getTable("acl_v1")
            ?: return null
        val chains = aclV1.arrayOfTables("chains").mapNotNull { chain ->
            val name = chain.stringOr("name")?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            AclChain(
                name = name,
                chainType = AclChainType.fromValue(chain.longOr("chain_type", AclChainType.Forward.value.toLong()).toInt())
                    ?: AclChainType.Forward,
                description = chain.stringOr("description").orEmpty(),
                enabled = chain.boolOr("enabled", true),
                rules = chain.arrayOfTables("rules").mapNotNull { rule ->
                    val ruleName = rule.stringOr("name")?.trim()?.takeIf { it.isNotEmpty() }
                        ?: return@mapNotNull null
                    AclRule(
                        name = ruleName,
                        description = rule.stringOr("description").orEmpty(),
                        priority = rule.longOr("priority", 0).toInt(),
                        enabled = rule.boolOr("enabled", true),
                        protocol = AclProtocol.fromValue(rule.longOr("protocol", AclProtocol.Any.value.toLong()).toInt())
                            ?: AclProtocol.Any,
                        ports = rule.stringsOrEmpty("ports"),
                        sourceIps = rule.stringsOrEmpty("source_ips"),
                        destinationIps = rule.stringsOrEmpty("destination_ips"),
                        sourcePorts = rule.stringsOrEmpty("source_ports"),
                        action = AclAction.fromValue(rule.longOr("action", AclAction.Allow.value.toLong()).toInt())
                            ?: AclAction.Allow,
                        rateLimit = rule.longOr("rate_limit", 0).toInt(),
                        burstLimit = rule.longOr("burst_limit", 0).toInt(),
                        stateful = rule.boolOr("stateful", false),
                        sourceGroups = rule.stringsOrEmpty("source_groups"),
                        destinationGroups = rule.stringsOrEmpty("destination_groups"),
                    )
                },
                defaultAction = AclAction.fromValue(chain.longOr("default_action", AclAction.Allow.value.toLong()).toInt())
                    ?: AclAction.Allow,
            )
        }
        val group = aclV1.getTable("group")
        val declares = group?.arrayOfTables("declares").orEmpty().mapNotNull { declare ->
            val groupName = declare.stringOr("group_name")?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            AclGroupDeclare(
                groupName = groupName,
                groupSecret = declare.stringOr("group_secret").orEmpty(),
            )
        }
        val members = group?.stringsOrEmpty("members").orEmpty()
        return if (chains.isEmpty() && declares.isEmpty() && members.isEmpty()) {
            null
        } else {
            Acl(chains = chains, group = AclGroup(declares = declares, members = members))
        }
    }

    private fun sanitize(input: String): String =
        input.removePrefix("\uFEFF").replace("\u0000", "")

    private fun parseCompression(value: String): CompressionAlgo = when (value.trim().lowercase()) {
        "zstd" -> CompressionAlgo.Zstd
        else -> CompressionAlgo.None
    }

    private fun parseEncryption(value: String): EncryptionAlgorithm = when (value.trim().lowercase()) {
        "xor" -> EncryptionAlgorithm.Xor
        "aes-256-gcm" -> EncryptionAlgorithm.Aes256Gcm
        "chacha20" -> EncryptionAlgorithm.ChaCha20
        else -> EncryptionAlgorithm.AesGcm
    }

    // tomlj throws TomlInvalidTypeException when a typed getter meets the wrong
    // value kind; treat that as "absent" so a malformed field never aborts the
    // whole import. isXxx guards keep the accessors exception-free.
    private inline fun <T> tolerant(getter: () -> T?): T? = try {
        getter()
    } catch (_: RuntimeException) {
        null
    }

    private fun TomlTable?.stringOr(key: String): String? = if (this != null && isString(key)) {
        tolerant { getString(key) }
    } else null

    private fun TomlTable?.boolOr(key: String, default: Boolean): Boolean =
        if (this != null && isBoolean(key)) tolerant { getBoolean(key) } ?: default else default

    private fun TomlTable?.longOr(key: String, default: Long): Long =
        if (this != null && isLong(key)) tolerant { getLong(key) } ?: default else default

    private fun TomlTable?.stringsOrEmpty(key: String): List<String> {
        if (this == null || !isArray(key)) return emptyList()
        return tolerant { getArray(key)?.toList() }.orEmpty().mapNotNull { it as? String }
    }

    private fun TomlTable?.arrayOfTables(key: String): List<TomlTable> {
        if (this == null || !isArray(key)) return emptyList()
        return tolerant { getArray(key)?.toList() }.orEmpty().mapNotNull { it as? TomlTable }
    }
}