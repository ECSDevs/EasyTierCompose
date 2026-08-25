package cc.ptoe.easytier.compose.core
import androidx.annotation.StringRes

import android.content.Context
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.R
import cc.ptoe.easytier.compose.data.GlobalSettings
import cc.ptoe.easytier.compose.data.RuntimeState
import cc.ptoe.easytier.compose.data.RuntimeStatus
import cc.ptoe.easytier.compose.data.TunMode
import cc.ptoe.easytier.compose.data.mergeInto
import cc.ptoe.easytier.compose.transport.root.RootTunTransport
import cc.ptoe.easytier.compose.transport.vpn.VpnPermissionRequester
import cc.ptoe.easytier.compose.transport.vpn.VpnTunTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class EasyTierRuntimeCoordinator private constructor(
    private val context: Context,
    permissionRequester: VpnPermissionRequester,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val vpn = VpnTunTransport(context, permissionRequester)
    private val root = RootTunTransport(context)
    private val mutableStatus = MutableStateFlow(RuntimeStatus.Stopped)
    val status: StateFlow<RuntimeStatus> = mutableStatus.asStateFlow()
    private var activeProfile: EasyTierProfile? = null
    private var pollJob: Job? = null

    /**
     * Swaps the VPN permission requester. BootCompletedReceiver binds a no-op requester
     * (background context can't launch the consent dialog); when MainActivity comes up it
     * swaps in the real activity-backed requester so a foreground connect() can prompt.
     */
    fun updatePermissionRequester(requester: VpnPermissionRequester) {
        vpn.updatePermissionRequester(requester)
    }

    companion object {
        // Process-wide singleton: BootCompletedReceiver and MainActivity must share one
        // coordinator. Two instances would each run their own poll loops and transports
        // against the same global native instance / root daemon, so a stop() from the
        // activity would be overridden by the boot receiver's still-polling loop, leaving
        // the VPN service running with no way to disconnect it.
        @Volatile private var instance: EasyTierRuntimeCoordinator? = null

        @Synchronized
        fun getInstance(context: Context, permissionRequester: VpnPermissionRequester): EasyTierRuntimeCoordinator {
            val current = instance
            if (current != null) {
                // Adopt the caller's requester so a boot-created singleton can prompt
                // once the activity is foregrounded.
                current.updatePermissionRequester(permissionRequester)
                return current
            }
            return EasyTierRuntimeCoordinator(context.applicationContext, permissionRequester).also { instance = it }
        }
    }

    suspend fun start(profile: EasyTierProfile, globalSettings: GlobalSettings = GlobalSettings()): RuntimeStatus = mutex.withLock {
        // Auto-fill hostname from the Android device name when the profile leaves it blank,
        // so peers see a recognizable identity without forcing the user to configure it.
        // Global overrides are merged on top afterwards; build/validate merge again
        // internally, which is idempotent.
        val effectiveProfile = globalSettings.mergeInto(profile.withDeviceHostnameIfBlank(context))
        val errors = ProfileValidator().validate(effectiveProfile, globalSettings)
        if (errors.isNotEmpty()) return@withLock failure(effectiveProfile, errors.values.first().resolve(context))
        val toml = TomlConfigBuilder.build(effectiveProfile, globalSettings)
        stopLocked()
        activeProfile = effectiveProfile
        mutableStatus.value = RuntimeStatus(RuntimeState.STARTING, effectiveProfile.id, effectiveProfile.tunMode, null, null, null)
        return@withLock when (effectiveProfile.tunMode) {
            // No TUN mode: run the EasyTier core with no TUN device at all, in
            // the app process. VpnService and RootTun are both skipped.
            TunMode.NO_TUN -> startNoTun(effectiveProfile, toml)
            TunMode.VPN_SERVICE -> startVpn(effectiveProfile, toml, globalSettings)
            TunMode.ROOT_TUN -> startRoot(effectiveProfile, toml, globalSettings)
        }
    }

    /**
     * Launches [start] in the coordinator's own [scope] (SupervisorJob + Dispatchers.IO),
     * decoupled from the caller's coroutine context. The returned [Job] can be awaited
     * for completion but cancelling it does NOT abort the start operation — the
     * SupervisorJob keeps [start] running until it settles on its own.
     *
     * Used by [BootCompletedReceiver] so its 8s `withTimeoutOrNull` wait only abandons
     * *waiting* for the result, not the start operation itself. This ensures `bind()`
     * and `remote.start(...)` complete even when boot-time root process startup is slow.
     */
    fun startDetached(profile: EasyTierProfile, globalSettings: GlobalSettings = GlobalSettings()): Job =
        scope.launch { start(profile, globalSettings) }

    suspend fun stop(): RuntimeStatus = mutex.withLock { stopLocked() }

    /**
     * Attempts to reconnect to a root daemon left running by a previous app process
     * (orphan process). If the daemon reports a running EasyTier instance, matches
     * its [profileId] against [profileLookup] and, on a match, takes over: sets the
     * active profile, publishes the daemon's status, and resumes polling. If the
     * profile no longer exists, stops the orphan so it doesn't linger.
     *
     * Safe to call on every app launch: it is a no-op when no daemon is running
     * (state STOPPED) and when the active transport is VPN_SERVICE.
     */
    suspend fun attachOrphanRoot(profileLookup: suspend (String) -> EasyTierProfile?) = mutex.withLock {
        // Skip if a session is already active in this process.
        if (activeProfile != null) return@withLock
        val rootStatus = root.attach() ?: return@withLock
        val profileId = rootStatus.profileId
        val profile = if (profileId != null) profileLookup(profileId) else null
        if (profile == null) {
            // Profile was deleted while the app was gone — stop the orphan.
            root.stop()
            return@withLock
        }
        activeProfile = profile
        val status = root.adopt(rootStatus, profile)
        mutableStatus.value = status
        if (status.state == RuntimeState.RUNNING || status.state == RuntimeState.STARTING) pollRoot()
    }

    /**
     * Attempts to adopt an EasyTier instance already running in-process (started by
     * BootCompletedReceiver in VPN_SERVICE or no_tun mode). Detects a running instance
     * via collectNetworkInfos, matches its profileId via [profileLookup], and resumes
     * polling without re-establishing the TUN/VPN interface.
     *
     * Safe to call on every app launch: it is a no-op when no in-process instance is
     * running (collectNetworkInfos returns null/empty) and when the active transport
     * is ROOT_TUN (handled by [attachOrphanRoot] instead, since the EasyTier core in
     * ROOT_TUN mode lives in the root daemon process, not in the app process).
     */
    suspend fun attachRunningInstance(
        globalSettings: GlobalSettings,
        profileLookup: suspend (String) -> EasyTierProfile?,
    ) = mutex.withLock {
        if (activeProfile != null) return@withLock
        val raw = runCatching { EasyTierJni.collectNetworkInfos(1) }.getOrNull() ?: return@withLock
        val map = runCatching {
            Json.parseToJsonElement(raw).jsonObject["map"]?.jsonObject
        }.getOrNull() ?: return@withLock
        if (map.isEmpty()) return@withLock
        // Find the first running instance without an error.
        val entry = map.entries.firstOrNull { (_, value) ->
            val obj = value.jsonObject
            obj["error_msg"]?.jsonPrimitive?.content.isNullOrBlank()
        } ?: return@withLock
        val profileId = entry.key
        val profile = profileLookup(profileId) ?: run {
            // Instance running but profile deleted — release it so it doesn't linger.
            EasyTierJni.retainNetworkInstance(null)
            return@withLock
        }
        activeProfile = profile
        val info = raw.networkInfo(profileId)
        when (profile.tunMode) {
            // In-process no_tun adoption only applies to NO_TUN sessions.
            TunMode.NO_TUN -> {
                mutableStatus.value = RuntimeStatus(
                    state = RuntimeState.RUNNING,
                    profileId = profile.id,
                    tunMode = profile.tunMode,
                    virtualIpv4 = info?.virtualIpv4,
                    tunDevice = null,
                    error = null,
                    peers = info?.peers.orEmpty(),
                    hostname = info?.hostname,
                    natType = info?.natType,
                )
                pollNoTun()
            }
            TunMode.VPN_SERVICE -> {
                val status = vpn.adopt(profile, info?.virtualIpv4, info?.routes.orEmpty())
                mutableStatus.value = status
                if (status.state == RuntimeState.RUNNING || status.state == RuntimeState.STARTING) pollVpn()
            }
            else -> {
                // ROOT_TUN should not reach here (EasyTier runs in the root daemon
                // process, not in-process). Clear state so attachOrphanRoot can handle it.
                activeProfile = null
            }
        }
    }

    private suspend fun startVpn(profile: EasyTierProfile, toml: String, globalSettings: GlobalSettings): RuntimeStatus = try {
        EasyTierJni.retainNetworkInstance(null)
        require(EasyTierJni.runNetworkInstance(toml) == 0) { nativeError(R.string.error_easytier_start_failed) }
        vpn.start(profile, toml, globalSettings).also {
            mutableStatus.value = it
            if (it.state == RuntimeState.STARTING || it.state == RuntimeState.RUNNING) pollVpn()
        }
    } catch (cancellation: CancellationException) {
        // External cancellation (e.g. BootCompletedReceiver's 8s budget): the EasyTier
        // core is already running in-process — do NOT release it or stop the VPN. The
        // caller intentionally bailed without waiting; a later app launch re-attaches
        // via attachRunningInstance().
        throw cancellation
    } catch (error: Throwable) {
        vpn.stop()
        runCatching { EasyTierJni.retainNetworkInstance(null) }
        failure(profile, error.message ?: nativeError(R.string.error_easytier_start_failed))
    }

    private suspend fun startRoot(profile: EasyTierProfile, toml: String, globalSettings: GlobalSettings): RuntimeStatus {
        val status = root.start(profile, toml, globalSettings)
        mutableStatus.value = status
        if (status.state == RuntimeState.RUNNING || status.state == RuntimeState.STARTING) pollRoot()
        return status
    }

    /**
     * no_tun path: start the EasyTier core only, with no VpnService/RootTun attached.
     * The core still publishes virtual_ipv4 / peers / hostname / natType via
     * collectNetworkInfos, so we poll those for UI updates — but never call
     * establishWhenResolved() or root.setTunFd(), leaving the system without a TUN.
     */
    private suspend fun startNoTun(profile: EasyTierProfile, toml: String): RuntimeStatus = try {
        EasyTierJni.retainNetworkInstance(null)
        require(EasyTierJni.runNetworkInstance(toml) == 0) { nativeError(R.string.error_easytier_start_failed) }
        mutableStatus.value = RuntimeStatus(RuntimeState.RUNNING, profile.id, profile.tunMode, null, null, null)
        pollNoTun()
        mutableStatus.value
    } catch (cancellation: CancellationException) {
        // External cancellation (e.g. BootCompletedReceiver's 8s budget): the EasyTier
        // core is already running in-process — do NOT release it. The caller
        // intentionally bailed without waiting; a later app launch re-attaches via
        // attachRunningInstance().
        throw cancellation
    } catch (error: Throwable) {
        runCatching { EasyTierJni.retainNetworkInstance(null) }
        failure(profile, error.message ?: nativeError(R.string.error_easytier_start_failed))
    }

    private suspend fun stopLocked(): RuntimeStatus {
        // cancelAndJoin (instead of cancel) ensures the poll coroutine has fully
        // terminated before we call stopService(). Otherwise a late establishWhenResolved()
        // call could post a startForegroundService intent AFTER stopService(), causing
        // EasyTierVpnService to be re-activated and leaving the system VPN up.
        pollJob?.cancelAndJoin()
        pollJob = null
        val profile = activeProfile
        when (profile?.tunMode) {
            // no_tun sessions started in-process (NO_TUN mode) release the
            // EasyTier instance here; ROOT_TUN sessions always live in the root
            // daemon and are stopped via root.stop() below.
            TunMode.NO_TUN -> {
                mutableStatus.value = mutableStatus.value.copy(state = RuntimeState.STOPPING)
                runCatching { EasyTierJni.retainNetworkInstance(null) }
            }
            TunMode.VPN_SERVICE -> {
                mutableStatus.value = mutableStatus.value.copy(state = RuntimeState.STOPPING)
                vpn.stop()
                runCatching { EasyTierJni.retainNetworkInstance(null) }
            }
            TunMode.ROOT_TUN -> root.stop()
            null -> return mutableStatus.value.takeIf { it.state == RuntimeState.STOPPED } ?: RuntimeStatus.Stopped
        }
        activeProfile = null
        return RuntimeStatus.Stopped.also { mutableStatus.value = it }
    }

    private fun pollVpn() {
        pollJob?.cancel()
        val profile = activeProfile ?: return
        pollJob = scope.launch {
            while (mutableStatus.value.state in setOf(RuntimeState.STARTING, RuntimeState.RUNNING)) {
                runCatching { EasyTierJni.collectNetworkInfos(1)?.networkInfo(profile.id) }.getOrNull()?.let { info ->
                    if (!info.error.isNullOrBlank()) {
                        failure(profile, info.error)
                        vpn.stop()
                        EasyTierJni.retainNetworkInstance(null)
                        return@launch
                    }
                    if (!info.virtualIpv4.isNullOrBlank()) {
                        val status = vpn.establishWhenResolved(profile, info.virtualIpv4, info.routes)
                        mutableStatus.value = status.copy(peers = info.peers, hostname = info.hostname, natType = info.natType)
                    } else {
                        mutableStatus.value = mutableStatus.value.copy(hostname = info.hostname, natType = info.natType)
                    }
                    refreshWireGuardPortal(profile)
                }
                // STARTING polls faster to resolve the virtual IPv4 quickly; steady-state
                // peers/latency change slowly, so 5s is enough and cuts JNI + JSON overhead.
                delay(if (mutableStatus.value.state == RuntimeState.RUNNING) 5_000 else 2_000)
            }
        }
    }

    private fun pollRoot() {
        pollJob?.cancel()
        pollJob = scope.launch { root.status.collect { mutableStatus.value = it } }
    }

    /**
     * Refreshes the WireGuard VPN portal info for profiles that configured one.
     * collectNetworkInfos does not include the portal client config, so it is
     * queried via VpnPortalRpc and attached to the current status. No-op when
     * the portal is not configured or not started yet (fetch returns null).
     */
    private fun refreshWireGuardPortal(profile: EasyTierProfile) {
        if (profile.vpnPortal == null) return
        if (mutableStatus.value.state != RuntimeState.RUNNING) return
        WireGuardPortalClient.fetch(profile.id)?.let { portal ->
            mutableStatus.value = mutableStatus.value.copy(wireguardPortal = portal)
        }
    }

    private fun pollNoTun() {
        pollJob?.cancel()
        val profile = activeProfile ?: return
        pollJob = scope.launch {
            while (mutableStatus.value.state in setOf(RuntimeState.STARTING, RuntimeState.RUNNING)) {
                runCatching { EasyTierJni.collectNetworkInfos(1)?.networkInfo(profile.id) }.getOrNull()?.let { info ->
                    if (!info.error.isNullOrBlank()) {
                        failure(profile, info.error)
                        EasyTierJni.retainNetworkInstance(null)
                        return@launch
                    }
                    // No TUN to establish: just surface runtime metadata for the UI.
                    // EasyTier still assigns a virtual IPv4 even without a TUN device,
                    // so propagate it (the poll only sets it when resolved, preserving
                    // the previous value when collectNetworkInfos returns null).
                    info.virtualIpv4?.let { ipv4 ->
                        mutableStatus.value = mutableStatus.value.copy(virtualIpv4 = ipv4)
                    }
                    mutableStatus.value = mutableStatus.value.copy(
                        peers = info.peers,
                        hostname = info.hostname,
                        natType = info.natType,
                    )
                    refreshWireGuardPortal(profile)
                }
                delay(5_000)
            }
        }
    }

    private fun failure(profile: EasyTierProfile, error: String): RuntimeStatus =
        RuntimeStatus(RuntimeState.ERROR, profile.id, profile.tunMode, null, null, error).also { mutableStatus.value = it }

    private fun nativeError(@StringRes fallbackRes: Int) = EasyTierJni.getLastError() ?: context.getString(fallbackRes)
}
