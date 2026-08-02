package cc.ptoe.easytier.compose.data

import kotlinx.serialization.Serializable

@Serializable
enum class TunMode {
    VPN_SERVICE,
    ROOT_TUN,
}

@Serializable
data class EasyTierProfile(
    val id: String,
    val name: String,
    val networkName: String,
    val networkSecret: String,
    val peerUrls: List<String>,
    val listeners: List<String>,
    val virtualIpv4: String?,
    val dhcp: Boolean,
    val proxyCidrs: List<String>,
    val manualRoutes: List<String>,
    val enableMagicDns: Boolean,
    val mtu: Int,
    val tunMode: TunMode,
    val advancedToml: String?,
)

enum class RuntimeState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR,
}

data class RuntimeStatus(
    val state: RuntimeState,
    val profileId: String?,
    val tunMode: TunMode?,
    val virtualIpv4: String?,
    val tunDevice: String?,
    val error: String?,
) {
    companion object {
        val Stopped = RuntimeStatus(RuntimeState.STOPPED, null, null, null, null, null)
    }
}
