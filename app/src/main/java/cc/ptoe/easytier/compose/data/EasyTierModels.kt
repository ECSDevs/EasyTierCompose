package cc.ptoe.easytier.compose.data

import kotlinx.serialization.Serializable

@Serializable
enum class TunMode {
    NO_TUN,
    VPN_SERVICE,
    ROOT_TUN,
}

@Serializable
enum class CompressionAlgo {
    None,
    Zstd,
}

@Serializable
enum class EncryptionAlgorithm {
    Xor,
    AesGcm,
    Aes256Gcm,
    ChaCha20,
}

@Serializable
data class Peer(
    val uri: String,
    val peerPublicKey: String? = null,
)

@Serializable
data class ProxyNetwork(
    val cidr: String,
    val mappedCidr: String? = null,
    val allow: List<String> = emptyList(),
)

@Serializable
data class PortForward(
    val bindAddr: String,
    val dstAddr: String,
    val proto: String,
)

@Serializable
data class VpnPortal(
    val clientCidr: String,
    val wireguardListen: String,
) {
    companion object {
        /**
         * Generates a portal with safe defaults: a random /24 inside the
         * CGNAT range 100.64.0.0/10 as the client CIDR (routable inside the
         * mesh, avoids loopback/link-local and common LAN collisions) and
         * the standard EasyTier WireGuard portal listen address.
         */
        fun generateDefault(random: kotlin.random.Random = kotlin.random.Random.Default): VpnPortal = VpnPortal(
            clientCidr = "100.${random.nextInt(64, 128)}.${random.nextInt(0, 256)}.0/24",
            wireguardListen = "0.0.0.0:11011",
        )
    }
}

@Serializable
data class SecureMode(
    val enabled: Boolean = false,
    val localPrivateKey: String? = null,
    val localPublicKey: String? = null,
)

@Serializable
data class EasyTierProfile(
    val id: String,
    val name: String,
    val hostname: String? = null,
    val networkName: String,
    val networkSecret: String,
    val peers: List<Peer> = emptyList(),
    val listeners: List<String> = emptyList(),
    val mappedListeners: List<String> = emptyList(),
    val virtualIpv4: String?,
    val virtualIpv6: String? = null,
    val dhcp: Boolean,
    val proxyNetworks: List<ProxyNetwork> = emptyList(),
    val manualRoutes: List<String> = emptyList(),
    val exitNodes: List<String> = emptyList(),
    val enableMagicDns: Boolean,
    val tunMode: TunMode,
    // IPv6 public address provider
    val ipv6PublicAddrProvider: Boolean = false,
    val ipv6PublicAddrAuto: Boolean = false,
    val ipv6PublicAddrPrefix: String? = null,
    // Port forwards
    val portForwards: List<PortForward> = emptyList(),
    // SOCKS5 proxy (socks5_proxy): local SOCKS5 server forwarding into the mesh
    val socks5Proxy: String? = null,
    // VPN portal (WireGuard)
    val vpnPortal: VpnPortal? = null,
    // Secure mode (credential key pair)
    val secureMode: SecureMode = SecureMode(),
    // STUN servers
    val stunServers: List<String> = emptyList(),
    val stunServersV6: List<String> = emptyList(),
    // Whitelists
    val tcpWhitelist: List<String> = emptyList(),
    val udpWhitelist: List<String> = emptyList(),
    // Flags
    val defaultProtocol: String = "tcp",
    val enableEncryption: Boolean = true,
    val enableIpv6: Boolean = true,
    val latencyFirst: Boolean = false,
    val enableExitNode: Boolean = false,
    val relayNetworkWhitelist: String = "*",
    val disableP2p: Boolean = false,
    val p2pOnly: Boolean = false,
    val lazyP2p: Boolean = false,
    val needP2p: Boolean = false,
    val relayAllPeerRpc: Boolean = false,
    val disableTcpHolePunching: Boolean = false,
    val disableUdpHolePunching: Boolean = false,
    val disableSymHolePunching: Boolean = false,
    val disableUpnp: Boolean = false,
    val dataCompressAlgo: CompressionAlgo = CompressionAlgo.None,
    val bindDevice: Boolean = true,
    val enableKcpProxy: Boolean = false,
    val disableKcpInput: Boolean = false,
    val disableRelayKcp: Boolean = false,
    val enableRelayForeignNetworkKcp: Boolean = false,
    val proxyForwardBySystem: Boolean = false,
    val privateMode: Boolean = false,
    val enableQuicProxy: Boolean = false,
    val disableQuicInput: Boolean = false,
    val disableRelayQuic: Boolean = false,
    val enableRelayForeignNetworkQuic: Boolean = false,
    val encryptionAlgorithm: EncryptionAlgorithm = EncryptionAlgorithm.AesGcm,
    val tldDnsZone: String = "et.net.",
    val disableRelayData: Boolean = false,
    val enableUdpBroadcastRelay: Boolean = false,
    // Device-local (engine) options. Each profile carries its own values and
    // the global overrides (GlobalSettings) may override them on top. These
    // never affect other devices in the network.
    val tunDeviceName: String = "easytier0",
    val startOnBoot: Boolean = false,
    val mtu: Int = 1380,
    val multiThread: Boolean = true,
    val multiThreadCount: Int = 2,
    val foreignRelayBpsLimit: Long = Long.MAX_VALUE,
    val instanceRecvBpsLimit: Long = Long.MAX_VALUE,
    // Optional SO_MARK (fwmark) for ROOT_TUN sockets. null = built-in default.
    val socketMark: Long? = null,
)

/**
 * Global overrides applied on top of every profile.
 *
 * `GlobalSettings` mirrors [EasyTierProfile]'s configuration fields: a non-null
 * value overrides the profile's value, `null` means "inherit from the profile".
 * An empty [GlobalSettings] is a no-op, so the profile is used as-is.
 *
 * Build/validate flow: `globalSettings.mergeInto(profile)` produces the
 * effective profile that is written to TOML. App-only fields that are not part
 * of the EasyTier config (id/name/tunMode) are intentionally not overridable.
 */
@Serializable
data class GlobalSettings(
    // General
    val hostname: String? = null,
    val networkName: String? = null,
    val networkSecret: String? = null,
    val virtualIpv4: String? = null,
    val virtualIpv6: String? = null,
    val dhcp: Boolean? = null,
    // Network & peers
    val peers: List<Peer>? = null,
    val listeners: List<String>? = null,
    val mappedListeners: List<String>? = null,
    // Routing
    val enableMagicDns: Boolean? = null,
    val tldDnsZone: String? = null,
    val proxyNetworks: List<ProxyNetwork>? = null,
    val manualRoutes: List<String>? = null,
    val enableExitNode: Boolean? = null,
    val exitNodes: List<String>? = null,
    // IPv6 public address provider
    val ipv6PublicAddrProvider: Boolean? = null,
    val ipv6PublicAddrAuto: Boolean? = null,
    val ipv6PublicAddrPrefix: String? = null,
    // Gateway services
    val portForwards: List<PortForward>? = null,
    val socks5Proxy: String? = null,
    val vpnPortal: VpnPortal? = null,
    val secureMode: SecureMode? = null,
    // STUN & whitelists
    val stunServers: List<String>? = null,
    val stunServersV6: List<String>? = null,
    val tcpWhitelist: List<String>? = null,
    val udpWhitelist: List<String>? = null,
    val relayNetworkWhitelist: String? = null,
    // Flags — General
    val defaultProtocol: String? = null,
    val enableEncryption: Boolean? = null,
    val encryptionAlgorithm: EncryptionAlgorithm? = null,
    val dataCompressAlgo: CompressionAlgo? = null,
    val enableIpv6: Boolean? = null,
    val latencyFirst: Boolean? = null,
    val bindDevice: Boolean? = null,
    val privateMode: Boolean? = null,
    val relayAllPeerRpc: Boolean? = null,
    val proxyForwardBySystem: Boolean? = null,
    val disableRelayData: Boolean? = null,
    val enableUdpBroadcastRelay: Boolean? = null,
    // Flags — P2P
    val disableP2p: Boolean? = null,
    val p2pOnly: Boolean? = null,
    val lazyP2p: Boolean? = null,
    val needP2p: Boolean? = null,
    val disableTcpHolePunching: Boolean? = null,
    val disableUdpHolePunching: Boolean? = null,
    val disableSymHolePunching: Boolean? = null,
    val disableUpnp: Boolean? = null,
    // Flags — KCP proxy
    val enableKcpProxy: Boolean? = null,
    val disableKcpInput: Boolean? = null,
    val disableRelayKcp: Boolean? = null,
    val enableRelayForeignNetworkKcp: Boolean? = null,
    // Flags — QUIC proxy
    val enableQuicProxy: Boolean? = null,
    val disableQuicInput: Boolean? = null,
    val disableRelayQuic: Boolean? = null,
    val enableRelayForeignNetworkQuic: Boolean? = null,
    // Device-local (engine)
    val tunDeviceName: String? = null,
    val mtu: Int? = null,
    val multiThread: Boolean? = null,
    val multiThreadCount: Int? = null,
    val foreignRelayBpsLimit: Long? = null,
    val instanceRecvBpsLimit: Long? = null,
    val socketMark: Long? = null,
)

/**
 * Applies non-null overrides on top of [profile], producing the effective
 * configuration used for TOML generation and validation.
 */
fun GlobalSettings.mergeInto(profile: EasyTierProfile): EasyTierProfile = profile.copy(
    hostname = hostname ?: profile.hostname,
    networkName = networkName ?: profile.networkName,
    networkSecret = networkSecret ?: profile.networkSecret,
    virtualIpv4 = virtualIpv4 ?: profile.virtualIpv4,
    virtualIpv6 = virtualIpv6 ?: profile.virtualIpv6,
    dhcp = dhcp ?: profile.dhcp,
    peers = peers ?: profile.peers,
    listeners = listeners ?: profile.listeners,
    mappedListeners = mappedListeners ?: profile.mappedListeners,
    enableMagicDns = enableMagicDns ?: profile.enableMagicDns,
    tldDnsZone = tldDnsZone ?: profile.tldDnsZone,
    proxyNetworks = proxyNetworks ?: profile.proxyNetworks,
    manualRoutes = manualRoutes ?: profile.manualRoutes,
    enableExitNode = enableExitNode ?: profile.enableExitNode,
    exitNodes = exitNodes ?: profile.exitNodes,
    ipv6PublicAddrProvider = ipv6PublicAddrProvider ?: profile.ipv6PublicAddrProvider,
    ipv6PublicAddrAuto = ipv6PublicAddrAuto ?: profile.ipv6PublicAddrAuto,
    ipv6PublicAddrPrefix = ipv6PublicAddrPrefix ?: profile.ipv6PublicAddrPrefix,
    portForwards = portForwards ?: profile.portForwards,
    socks5Proxy = socks5Proxy ?: profile.socks5Proxy,
    vpnPortal = vpnPortal ?: profile.vpnPortal,
    secureMode = secureMode ?: profile.secureMode,
    stunServers = stunServers ?: profile.stunServers,
    stunServersV6 = stunServersV6 ?: profile.stunServersV6,
    tcpWhitelist = tcpWhitelist ?: profile.tcpWhitelist,
    udpWhitelist = udpWhitelist ?: profile.udpWhitelist,
    relayNetworkWhitelist = relayNetworkWhitelist ?: profile.relayNetworkWhitelist,
    defaultProtocol = defaultProtocol ?: profile.defaultProtocol,
    enableEncryption = enableEncryption ?: profile.enableEncryption,
    encryptionAlgorithm = encryptionAlgorithm ?: profile.encryptionAlgorithm,
    dataCompressAlgo = dataCompressAlgo ?: profile.dataCompressAlgo,
    enableIpv6 = enableIpv6 ?: profile.enableIpv6,
    latencyFirst = latencyFirst ?: profile.latencyFirst,
    bindDevice = bindDevice ?: profile.bindDevice,
    privateMode = privateMode ?: profile.privateMode,
    relayAllPeerRpc = relayAllPeerRpc ?: profile.relayAllPeerRpc,
    proxyForwardBySystem = proxyForwardBySystem ?: profile.proxyForwardBySystem,
    disableRelayData = disableRelayData ?: profile.disableRelayData,
    enableUdpBroadcastRelay = enableUdpBroadcastRelay ?: profile.enableUdpBroadcastRelay,
    disableP2p = disableP2p ?: profile.disableP2p,
    p2pOnly = p2pOnly ?: profile.p2pOnly,
    lazyP2p = lazyP2p ?: profile.lazyP2p,
    needP2p = needP2p ?: profile.needP2p,
    disableTcpHolePunching = disableTcpHolePunching ?: profile.disableTcpHolePunching,
    disableUdpHolePunching = disableUdpHolePunching ?: profile.disableUdpHolePunching,
    disableSymHolePunching = disableSymHolePunching ?: profile.disableSymHolePunching,
    disableUpnp = disableUpnp ?: profile.disableUpnp,
    enableKcpProxy = enableKcpProxy ?: profile.enableKcpProxy,
    disableKcpInput = disableKcpInput ?: profile.disableKcpInput,
    disableRelayKcp = disableRelayKcp ?: profile.disableRelayKcp,
    enableRelayForeignNetworkKcp = enableRelayForeignNetworkKcp ?: profile.enableRelayForeignNetworkKcp,
    enableQuicProxy = enableQuicProxy ?: profile.enableQuicProxy,
    disableQuicInput = disableQuicInput ?: profile.disableQuicInput,
    disableRelayQuic = disableRelayQuic ?: profile.disableRelayQuic,
    enableRelayForeignNetworkQuic = enableRelayForeignNetworkQuic ?: profile.enableRelayForeignNetworkQuic,
    tunDeviceName = tunDeviceName ?: profile.tunDeviceName,
    mtu = mtu ?: profile.mtu,
    multiThread = multiThread ?: profile.multiThread,
    multiThreadCount = multiThreadCount ?: profile.multiThreadCount,
    foreignRelayBpsLimit = foreignRelayBpsLimit ?: profile.foreignRelayBpsLimit,
    instanceRecvBpsLimit = instanceRecvBpsLimit ?: profile.instanceRecvBpsLimit,
    socketMark = socketMark ?: profile.socketMark,
)

enum class RuntimeState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR,
}

/**
 * Snapshot of a remote peer observed via collectNetworkInfos.
 *
 * List display shows only [hostname], [virtualIpv4] and [latencyMs];
 * the remaining fields are surfaced in a details dialog.
 */
@Serializable
data class RuntimePeer(
    val hostname: String,
    val virtualIpv4: String?,
    val latencyMs: Double?,
    val connectionType: String,
    val tunnelProtos: List<String>,
    val lossRate: Double?,
    val natType: String,
    val cost: Int,
)

/**
 * Snapshot of the WireGuard VPN portal exposed by a running EasyTier instance
 * (VpnPortalRpc.GetVpnPortalInfo). [clientConfig] is the WireGuard client
 * config rendered by EasyTier Core; [connectedClients] lists portal client
 * endpoints currently registered.
 */
@Serializable
data class WireGuardPortalInfo(
    val vpnType: String,
    val clientConfig: String,
    val connectedClients: List<String> = emptyList(),
)

data class RuntimeStatus(
    val state: RuntimeState,
    val profileId: String?,
    val tunMode: TunMode?,
    val virtualIpv4: String?,
    val tunDevice: String?,
    val error: String?,
    val peers: List<RuntimePeer> = emptyList(),
    val hostname: String? = null,
    val natType: String? = null,
    val wireguardPortal: WireGuardPortalInfo? = null,
) {
    companion object {
        val Stopped = RuntimeStatus(RuntimeState.STOPPED, null, null, null, null, null)
    }
}
