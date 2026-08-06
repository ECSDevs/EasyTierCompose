package cc.ptoe.easytier.compose.transport.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.GlobalSettings
import cc.ptoe.easytier.compose.data.RuntimeState
import cc.ptoe.easytier.compose.data.RuntimeStatus
import cc.ptoe.easytier.compose.transport.RuntimeTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class VpnTunTransport(
    private val context: Context,
    private val permissionRequester: VpnPermissionRequester,
) : RuntimeTransport {
    private val mutableStatus = MutableStateFlow(RuntimeStatus.Stopped)
    override val status: StateFlow<RuntimeStatus> = mutableStatus.asStateFlow()

    // Guards against establishWhenResolved racing with stop(): once running flips to
    // false, no further startForegroundService intents are posted, so stopService()
    // cannot be re-activated by a late poll iteration.
    @Volatile private var running: Boolean = false
    // Tracks the last established VPN state so we only re-establish when the address
    // or routes actually change, instead of re-calling startForegroundService every
    // 2 seconds from the poll loop.
    private var establishedIpv4Cidr: String? = null
    private var establishedRoutes: List<String> = emptyList()

    override suspend fun start(profile: EasyTierProfile, toml: String, globalSettings: GlobalSettings): RuntimeStatus {
        running = true
        establishedIpv4Cidr = null
        establishedRoutes = emptyList()
        val ipv4Cidr = profile.virtualIpv4?.takeIf { !profile.dhcp }
        val prepared = VpnService.prepare(context)
        if (prepared != null) {
            // Synchronously request VPN permission. In the foreground this launches
            // the system consent dialog via the injected requester; from a background
            // context (e.g. boot) the no-op requester returns false and we surface
            // an ERROR — the user must open the app once to grant permission.
            mutableStatus.value = RuntimeStatus(RuntimeState.STARTING, profile.id, profile.tunMode, null, null, null)
            val granted = permissionRequester.request(prepared)
            if (!granted) {
                return RuntimeStatus(RuntimeState.ERROR, profile.id, profile.tunMode, null, null, "VPN permission denied")
                    .also { running = false; mutableStatus.value = it }
            }
        }
        return startPrepared(profile, ipv4Cidr, emptyList())
    }

    /**
     * Adopts an already-running VPN session started by another coordinator instance
     * (e.g. BootCompletedReceiver). EasyTierVpnService FGS is already running with the
     * VPN interface established, so we restore [running] and the established address/
     * routes to prevent establishWhenResolved() from redundantly re-launching the FGS
     * with the same parameters.
     */
    fun adopt(profile: EasyTierProfile, ipv4Cidr: String?, routes: List<String>): RuntimeStatus {
        running = true
        establishedIpv4Cidr = ipv4Cidr
        establishedRoutes = routes
        val state = if (ipv4Cidr.isNullOrBlank()) RuntimeState.STARTING else RuntimeState.RUNNING
        val tunDevice = if (ipv4Cidr.isNullOrBlank()) null else "Android VPN"
        return RuntimeStatus(state, profile.id, profile.tunMode, ipv4Cidr, tunDevice, null)
            .also { mutableStatus.value = it }
    }

    fun establishWhenResolved(profile: EasyTierProfile, ipv4Cidr: String, routes: List<String>): RuntimeStatus {
        if (!running) return mutableStatus.value
        // Only re-establish the VPN interface when the address or routes actually change,
        // avoiding redundant startForegroundService calls that race with stop().
        if (ipv4Cidr == establishedIpv4Cidr && routes == establishedRoutes) {
            return mutableStatus.value
        }
        establishedIpv4Cidr = ipv4Cidr
        establishedRoutes = routes
        ContextCompat.startForegroundService(
            context,
            EasyTierVpnService.intent(context, profile.id, ipv4Cidr, routes, profile.enableMagicDns, profile.mtu),
        )
        return RuntimeStatus(RuntimeState.RUNNING, profile.id, profile.tunMode, ipv4Cidr, "Android VPN", null)
            .also { mutableStatus.value = it }
    }

    override suspend fun stop() {
        running = false
        establishedIpv4Cidr = null
        establishedRoutes = emptyList()
        context.stopService(Intent(context, EasyTierVpnService::class.java))
        mutableStatus.value = RuntimeStatus.Stopped
    }

    private fun startPrepared(profile: EasyTierProfile, ipv4Cidr: String?, routes: List<String>): RuntimeStatus {
        if (ipv4Cidr == null) return RuntimeStatus(RuntimeState.STARTING, profile.id, profile.tunMode, null, null, null)
            .also { mutableStatus.value = it }
        return establishWhenResolved(profile, ipv4Cidr, routes)
    }
}
