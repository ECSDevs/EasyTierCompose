package cc.ptoe.easytier.compose.transport.root

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import cc.ptoe.easytier.compose.core.TomlConfigBuilder
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.GlobalSettings
import cc.ptoe.easytier.compose.data.RuntimePeer
import cc.ptoe.easytier.compose.data.RuntimeState
import cc.ptoe.easytier.compose.data.RuntimeStatus
import cc.ptoe.easytier.compose.transport.RuntimeTransport
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume

class RootTunTransport(private val context: Context) : RuntimeTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableStatus = MutableStateFlow(RuntimeStatus.Stopped)
    override val status: StateFlow<RuntimeStatus> = mutableStatus.asStateFlow()
    private var service: IEasyTierRootService? = null
    private var bound = false
    private var activeProfile: EasyTierProfile? = null
    private var pollJob: Job? = null
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
                val status = root.toRuntimeStatus(profile)
                mutableStatus.value = status
                if (status.state != RuntimeState.STARTING) {
                    if (status.state == RuntimeState.RUNNING) pollRoot(profile)
                    return status
                }
            }
            // Timed out — stop the daemon so the root process doesn't linger in the
            // background. Without this the daemon stays bound and running (still in
            // STARTING), leaving no way for the user to disconnect from the ERROR state.
            stop()
            error(profile, "Root helper start timed out")
        }.getOrElse {
            stop()
            error(profile, it.message ?: "Root helper disconnected")
        }
    }

    /**
     * Attempts to attach to an existing daemon root service left running by a previous
     * app process (orphan process). Binds to the daemon and queries its status.
     *
     * @return the daemon's [RootRuntimeStatus] if an EasyTier instance is running or
     *  starting; `null` if the daemon is fresh/stopped (no orphan to adopt). When
     *  `null` is returned the transport unbinds so the daemon can exit on its own.
     *  On a non-null return the transport stays bound and the caller should invoke
     *  [adopt] with the matched profile to begin polling.
     */
    suspend fun attach(): RootRuntimeStatus? {
        val remote = bind() ?: return null
        val root = remote.status
        val state = runCatching { RuntimeState.valueOf(root.state) }.getOrDefault(RuntimeState.STOPPED)
        if (state == RuntimeState.STOPPED || state == RuntimeState.ERROR) {
            // No orphan — unbind so the daemon process can exit.
            unbindInternal()
            return null
        }
        return root
    }

    /**
     * Adopts an orphaned daemon root service with the matched profile. Sets the
     * active profile, publishes the current status, and begins polling for
     * status updates. The transport must already be bound (via [attach]).
     */
    fun adopt(root: RootRuntimeStatus, profile: EasyTierProfile): RuntimeStatus {
        activeProfile = profile
        val status = root.toRuntimeStatus(profile)
        mutableStatus.value = status
        if (status.state == RuntimeState.RUNNING || status.state == RuntimeState.STARTING) {
            pollRoot(profile)
        }
        return status
    }

    override suspend fun stop() {
        pollJob?.cancel()
        pollJob = null
        // Ask the daemon to stop EasyTier + clean up + stopSelf (exits the daemon process).
        runCatching { service?.stop() }
        // Give the root process a brief moment to run stopRoot() before unbinding,
        // so stopSelf() can take effect and the daemon exits cleanly.
        delay(300)
        unbindInternal()
        activeProfile = null
        mutableStatus.value = RuntimeStatus.Stopped
    }

    private fun unbindInternal() {
        if (bound) RootService.unbind(connection)
        bound = false
        service = null
    }

    private fun pollRoot(profile: EasyTierProfile) {
        pollJob?.cancel()
        val remote = service ?: return
        pollJob = scope.launch {
            while (mutableStatus.value.state in setOf(RuntimeState.STARTING, RuntimeState.RUNNING)) {
                runCatching {
                    val root = remote.status
                    mutableStatus.value = root.toRuntimeStatus(profile)
                }
                delay(2_000)
            }
        }
    }

    /**
     * Intent used to bind to the root service. Always uses [RootService.CATEGORY_DAEMON_MODE]
     * so the root process runs independently from the app lifecycle: when the app is
     * killed the daemon keeps EasyTier running, and on the next app launch [attach]
     * can reconnect to the same daemon process.
     */
    private fun daemonIntent(): Intent =
        Intent(context, EasyTierRootService::class.java)
            .addCategory(RootService.CATEGORY_DAEMON_MODE)

    private suspend fun bind(): IEasyTierRootService? {
        service?.let { return it }
        return withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { continuation ->
                RootService.bind(daemonIntent(), connection)
                bound = true
                Thread {
                    while (service == null && continuation.isActive) Thread.sleep(50)
                    service?.let { continuation.resume(it) }
                }.start()
                continuation.invokeOnCancellation { if (bound) RootService.unbind(connection) }
            }
        }
    }

    private fun RootRuntimeStatus.toRuntimeStatus(profile: EasyTierProfile): RuntimeStatus {
        val state = runCatching { RuntimeState.valueOf(state) }.getOrDefault(RuntimeState.ERROR)
        val peers = peersJson?.takeIf(String::isNotBlank)?.let { json ->
            runCatching { Json.decodeFromString(ListSerializer(RuntimePeer.serializer()), json) }.getOrDefault(emptyList())
        }.orEmpty()
        // Prefer the profileId reported by the daemon (matches the running instance);
        // fall back to the caller-supplied profile for the start flow where the daemon
        // hasn't reported yet.
        val effectiveProfileId = profileId ?: profile.id
        return RuntimeStatus(state, effectiveProfileId, profile.tunMode, virtualIpv4, tunDevice, error, peers, hostname, natType)
    }

    private fun error(profile: EasyTierProfile, message: String): RuntimeStatus =
        RuntimeStatus(RuntimeState.ERROR, profile.id, profile.tunMode, null, null, message).also { mutableStatus.value = it }
}
