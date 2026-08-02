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
)

@Serializable
data class SecureMode(
    val enabled: Boolean = false,
    val