package cc.ptoe.easytier.compose.transport.root

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import cc.ptoe.easytier.compose.core.TomlConfigBuilder
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.GlobalSettings
import cc.ptoe.easytier.compose.data.RuntimeState
import cc.ptoe.easytier.compose.data.RuntimeStatus
import cc.ptoe.easytier.compose.transport.RuntimeTransport
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class RootTunTransport(private val context: Context) : RuntimeTransport {
    private val mutableStatus = MutableStateFlow(RuntimeStatus.Stopped)
    override val status: StateFlow<RuntimeStatus> = mutableStatus.asStateFlow()
    private var service: IEasyTierRootService? = null
    private var bound = false
    private var activeProfile: EasyTierProfile? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) { service = IEasyTierRootService.Stub.asInterface(binder) }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            if (bound) mutableStatus.value = RuntimeStatus(RuntimeState.ERROR, activeProfile?.id, activeProfile?.tunMode, null, null, "Root helper disconnected")
        }
    }

    override suspend fun start(profile: EasyTierProfile, toml: String, globalSettings: GlobalSettings): RuntimeStatus {
        activeProfile = profile
        mutableStatus.value = RuntimeStatus(RuntimeState.STARTING, profile.id, profile.tunMode, null, null, null)
        val remote = bind() ?: return error(profile, "Root helper disconnected")
        return runCatching {
            remote.start(profile.id, toml, TomlConfigBuilder.rootTunSpec(profile, globalSettings))
            repeat(30) {
                delay(500)
                val root = remote.status
                val state = runCatching { RuntimeState.valueOf(root.state) }.getOrDefault(RuntimeState.ERROR)
                val status = RuntimeStatus(state, profile.id, profile.tunMode, root.virtualIpv4, root.tunDevice, root.error)
                mutableStatus.value = status
                if (state != RuntimeState.STARTING) return status
            }
            error(profile, "Root helper start timed out")
        }.getOrElse { error(profile, it.message ?: "Root helper disconnected") }
    }

    override suspend fun stop() {
        runCatching { service?.stop() }
        if (bound) RootService.unbind(connection)
        bound = false
        service = null
        activeProfile = null
        mutableStatus.value = RuntimeStatus.Stopped
    }

    private suspend fun bind(): IEasyTierRootService? {
        service?.let { return it }
        return withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { continuation ->
                RootService.bind(Intent(context, EasyTierRootService::class.java), connection)
                bound = true
                Thread {
                    while (service == null && continuation.isActive) Thread.sleep(50)
                    service?.let { continuation.resume(it) }
                }.start()
                continuation.invokeOnCancellation { if (bound) RootService.unbind(connection) }
            }
        }
    }

    private fun error(profile: EasyTierProfile, message: String): RuntimeStatus =
        RuntimeStatus(RuntimeState.ERROR, profile.id, profile.tunMode, null, null, message).also { mutableStatus.value = it }
}
