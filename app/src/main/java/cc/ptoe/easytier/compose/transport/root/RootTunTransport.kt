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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume

class RootTunTransport(private val context: Context) : RuntimeTransport {
    private val mutableStatus = MutableStateFlow(RuntimeStatus.Stopped)
    override val status: StateFlow<RuntimeStatus> = mutableStatus.asStateFlow()
    private var service: IEasyTierRootService? = null
    private var bound = false
    // @Volatile: the status callback fires on a Binder thread, while start/adopt/stop
    // mutate this on the caller's thread. Volatile gives the callback a consistent view.
    @Volatile private var activeProfile: EasyTierProfile? = null
    private var callback: IRootStatusCallback? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) { service = IEasyTierRootService.Stub.asInterface(binder) }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            callback = null
            if (bound) mutableStatus.value = RuntimeStatus(RuntimeState.ERROR, activeProfile?.id, activeProfile?.tunMode, null, null, "Root helper disconnected")
        }
    }

    override suspend fun start(profile: EasyTierProfile, toml: String, globalSettings: GlobalSettings): RuntimeStatus {
        activeProfile = profile
        mutableStatus.value = RuntimeStatus(RuntimeState.STARTING, profile.id, profile.tunMode, null, null, null)
        val remote = bind() ?: return error(profile, "Root helper disconnected")
        return runCatching {
            registerCallback(remote)
            remote.start(profile.id, toml, TomlConfigBuilder.rootTunSpec(profile, globalSettings))
            // Wait for the daemon to reach RUNNING or ERROR. registerStatusCallback
            // immediately pushes the daemon's *current* state, which is STOPPED before
            // `remote.start(...)` takes effect — filtering for RUNNING/ERROR skips that
            // initial STOPPED (and the subsequent STARTING) so we don't bail out early
            // while the daemon is still doing DHCP to resolve the virtual IPv4.
            withTimeoutOrNull(15_000) {
                mutableStatus.first { it.state == RuntimeState.RUNNING || it.state == RuntimeState.ERROR }
            } ?: run {
                // Timed out — stop the daemon so the root process doesn't linger in
                // STARTING, leaving no way for the user to disconnect from ERROR.
                stop()
                error(profile, "Root helper start timed out")
            }
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
     *  [adopt] with the matched profile to receive pushed status updates.
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
     * active profile, publishes the current status, and registers a callback so
     * all subsequent daemon status changes are pushed to [mutableStatus] without
     * polling. The transport must already be bound (via [attach]).
     */
    fun adopt(root: RootRuntimeStatus, profile: EasyTierProfile): RuntimeStatus {
        activeProfile = profile
        val status = root.toRuntimeStatus(profile)
        mutableStatus.value = status
        service?.let { registerCallback(it) }
        return status
    }

    override suspend fun stop() {
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
        unregisterCallback()
        if (bound) RootService.unbind(connection)
        bound = false
        service = null
    }

    /**
     * Registers a push-based status callback with the daemon. Every status change
     * (STARTING -> RUNNING, peer list refresh, ERROR, STOPPED) is delivered via
     * [IRootStatusCallback.onStatusUpdated] instead of the app polling getStatus().
     * The daemon also immediately pushes the current status on registration.
     */
    private fun registerCallback(remote: IEasyTierRootService) {
        if (callback != null) return
        callback = object : IRootStatusCallback.Stub() {
            override fun onStatusUpdated(root: RootRuntimeStatus) {
                // Fires on a Binder thread. MutableStateFlow is thread-safe, and
                // activeProfile is @Volatile, so this needs no extra synchronization.
                val profile = activeProfile ?: return
                mutableStatus.value = root.toRuntimeStatus(profile)
            }
        }
        runCatching { remote.registerStatusCallback(callback!!) }
    }

    private fun unregisterCallback() {
        val cb = callback ?: return
        callback = null
        runCatching { service?.unregisterStatusCallback(cb) }
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
