package cc.ptoe.easytier.compose.data

import kotlinx.serialization.Serializable

@Serializable
enum class TunMode {
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
)

@Serializable
data class GlobalSettings(
    val tunDeviceName: String = "easytier0",
    val noTun: Boolean = false,
    val startOnBoot: Boolean = false,
    // Engine settings: device-local options that are not specific to any
    // EasyTier network and do not affect other devices in the network.
    val mtu: Int = 1380,
    val multiThread: Boolean = true,
    val multiThreadCount: Int = 2,
    val foreignRelayBpsLimit: Long = Long.MAX_VALUE,
    val instanceRecvBpsLimit: Long = Long.MAX_VALUE,
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
