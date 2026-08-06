package cc.ptoe.easytier.compose.core

import android.app.Activity
import android.content.Context
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.GlobalSettings
import cc.ptoe.easytier.compose.data.RuntimeState
import cc.ptoe.easytier.compose.data.RuntimeStatus
import cc.ptoe.easytier.compose.data.TunMode
import cc.ptoe.easytier.compose.transport.RuntimeEffect
import cc.ptoe.easytier.compose.transport.root.RootTunTransport
import cc.ptoe.easytier.compose.transport.vpn.VpnTunTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class EasyTierRuntimeCoordinator(private val context: Context, activity: Activity) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val vpn = VpnTunTransport(context, activity)
    private val root = RootTunTransport(context)
    private val mutableStatus = MutableStateFlow(RuntimeStatus.Stopped)
    val status: StateFlow<RuntimeStatus> = mutableStatus.asStateFlow()
    private val mutableEffects = MutableSharedFlow<RuntimeEffect>()
    val effects: SharedFlow<RuntimeEffect> = mutableEffects.asSharedFlow()
    private var activeProfile: EasyTierProfile? = null
    private var pollJob: Job? = null
    // Tracks whether the active session took the no-TUN path (globalSettings.noTun = true),
    // so stopLocked() can release the EasyTier instance without touching VpnService/RootTun.
    private var noTunActive: Boolean = false

    init {
        scope.launch { vpn.effects.collect { mutableEffects.emit(it) } }
    }

    suspend fun start(profile: EasyTierProfile, globalSettings: GlobalSettings = GlobalSettings()): RuntimeStatus = mutex.withLock {
        // Auto-fill hostname from the Android device name when the profile leaves it blank,
        // so peers see a recognizable identity without forcing the user to configure it.
        val effectiveProfile = profile.withDeviceHostnameIfBlank(context)
        val errors = ProfileValidator().validate(effectiveProfile, globalSettings)
        if (errors.isNotEmpty()) return@withLock failure(effectiveProfile, errors.values.first())
        val toml = TomlConfigBuilder.build(effectiveProfile, globalSettings)
        stopLocked()
        activeProfile = effectiveProfile
        noTunActive = globalSettings.noTun
        mutableStatus.value = RuntimeStatus(RuntimeState.STARTING, effectiveProfile.id, effectiveProfile.tunMode, null, null, null)
        return@withLock when {
            // no_tun: EasyTier core skips TUN creation; the app must match by not
            // establishing VpnService/RootTun either, otherwise we'd still bring up
            // a system VPN interface or easytier0 despite no_tun = true in TOML.
            noTunActive -> startNoTun(effectiveProfile, toml)
            else -> when (effectiveProfile.tunMode) {
                TunMode.VPN_SERVICE -> startVpn(effectiveProfile, toml, globalSettings)
                TunMode.ROOT_TUN -> startRoot(effectiveProfile, toml, globalSettings)
            }
        }
    }

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

    suspend fun onVpnPermissionResult(granted: Boolean): RuntimeStatus = mutex.withLock {
        val result = vpn.onPermissionResult(granted)
        mutableStatus.value = result
        if (result.state == RuntimeState.RUNNING || result.state == RuntimeState.STARTING) pollVpn()
        result
    }

    private suspend fun startVpn(profile: EasyTierProfile, toml: String, globalSettings: GlobalSettings): RuntimeStatus = try {
        EasyTierJni.retainNetworkInstance(null)
        require(EasyTierJni.runNetworkInstance(toml) == 0) { nativeError("EasyTier failed to start") }
        vpn.start(profile, toml, globalSettings).also {
            mutableStatus.value = it
            if (it.state == RuntimeState.STARTING || it.state == RuntimeState.RUNNING) pollVpn()
        }
    } catch (error: Throwable) {
        vpn.stop()
        runCatching { EasyTierJni.retainNetworkInstance(null) }
        failure(profile, error.message ?: nativeError("EasyTier failed to start"))
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
        require(EasyTierJni.runNetworkInstance(toml) == 0) { nativeError("EasyTier failed to start") }
        mutableStatus.value = RuntimeStatus(RuntimeState.RUNNING, profile.id, profile.tunMode, null, null, null)
        pollNoTun()
        mutableStatus.value
    } catch (error: Throwable) {
        runCatching { EasyTierJni.retainNetworkInstance(null) }
        failure(profile, error.message ?: nativeError("EasyTier failed to start"))
    }

    private suspend fun stopLocked(): RuntimeStatus {
        // cancelAndJoin (instead of cancel) ensures the poll coroutine has fully
        // terminated before we call stopService(). Otherwise a late establishWhenResolved()
        // call could post a startForegroundService intent AFTER stopService(), causing
        // EasyTierVpnService to be re-activated and leaving the system VPN up.
        pollJob?.cancelAndJoin()
        pollJob = null
        val profile = activeProfile
        if (noTunActive) {
            mutableStatus.value = mutableStatus.value.copy(state = RuntimeState.STOPPING)
            runCatching { EasyTierJni.retainNetworkInstance(null) }
            noTunActive = false
            activeProfile = null
            return RuntimeStatus.Stopped.also { mutableStatus.value = it }
        }
        when (profile?.tunMode) {
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
                    mutableStatus.value = mutableStatus.value.copy(
                        peers = info.peers,
                        hostname = info.hostname,
                        natType = info.natType,
                    )
                }
                delay(5_000)
            }
        }
    }

    private fun failure(profile: EasyTierProfile, error: String): RuntimeStatus =
        RuntimeStatus(RuntimeState.ERROR, profile.id, profile.tunMode, null, null, error).also { mutableStatus.value = it }

    private fun nativeError(fallback: String) = EasyTierJni.getLastError() ?: fallback
}
