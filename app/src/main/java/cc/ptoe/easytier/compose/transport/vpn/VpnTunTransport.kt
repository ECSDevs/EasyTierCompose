package cc.ptoe.easytier.compose.transport.vpn

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.GlobalSettings
import cc.ptoe.easytier.compose.data.RuntimeState
import cc.ptoe.easytier.compose.data.RuntimeStatus
import cc.ptoe.easytier.compose.transport.RuntimeEffect
import cc.ptoe.easytier.compose.transport.RuntimeTransport
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class VpnTunTransport(private val context: Context, private val activity: Activity) : RuntimeTransport {
    private val mutableStatus = MutableStateFlow(RuntimeStatus.Stopped)
    override val status: StateFlow<RuntimeStatus> = mutableStatus.asStateFlow()
    private val mutableEffects = MutableSharedFlow<RuntimeEffect>()
    val effects: SharedFlow<RuntimeEffect> = mutableEffects.asSharedFlow()
    private var pending: PendingStart? = null

    override suspend fun start(profile: EasyTierProfile, toml: String, globalSettings: GlobalSettings): RuntimeStatus {
        val prepared = VpnService.prepare(context)
        val ipv4Cidr = profile.virtualIpv4?.takeIf { !profile.dhcp }
        if (prepared != null) {
            pending = PendingStart(profile, ipv4Cidr, emptyList())
            mutableEffects.emit(RuntimeEffect.RequestVpnPermission(prepared))
            return RuntimeStatus(RuntimeState.STARTING, profile.id, profile.tunMode, null, null, null).also { mutableStatus.value = it }
        }
        return startPrepared(profile, ipv4Cidr, emptyList())
    }

    suspend fun onPermissionResult(granted: Boolean): RuntimeStatus {
        val start = pending ?: return mutableStatus.value
        pending = null
        if (!granted) return RuntimeStatus(RuntimeState.ERROR, start.profile.id, start.profile.tunMode, null, null, "VPN permission denied")
            .also { mutableStatus.value = it }
        return startPrepared(start.profile, start.ipv4Cidr, start.routes)
    }

    fun establishWhenResolved(profile: EasyTierProfile, ipv4Cidr: String, routes: List<String>): RuntimeStatus {
        ContextCompat.startForegroundService(
            context,
            EasyTierVpnService.intent(context, profile.id, ipv4Cidr, routes, profile.enableMagicDns, profile.mtu),
        )
        return RuntimeStatus(RuntimeState.RUNNING, profile.id, profile.tunMode, ipv4Cidr, "Android VPN", null)
            .also { mutableStatus.value = it }
    }

    override suspend fun stop() {
        pending = null
        context.stopService(Intent(context, EasyTierVpnService::class.java))
        mutableStatus.value = RuntimeStatus.Stopped
    }

    private fun startPrepared(profile: EasyTierProfile, ipv4Cidr: String?, routes: List<String>): RuntimeStatus {
        if (ipv4Cidr == null) return RuntimeStatus(RuntimeState.STARTING, profile.id, profile.tunMode, null, null, null)
            .also { mutableStatus.value = it }
        return establishWhenResolved(profile, ipv4Cidr, routes)
    }

    private data class PendingStart(val profile: EasyTierProfile, val ipv4Cidr: String?, val routes: List<String>)
}
