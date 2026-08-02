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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class EasyTierRuntimeCoordinator(context: Context, activity: Activity) {
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

    init {
        scope.launch { vpn.effects.collect { mutableEffects.emit(it) } }
    }

    suspend fun start(profile: EasyTierProfile, globalSettings: GlobalSettings = GlobalSettings()): RuntimeStatus = mutex.withLock {
        val errors = ProfileValidator().validate(profile, globalSettings)
        if (errors.isNotEmpty()) return@withLock failure(profile, errors.values.first())
        val toml = TomlConfigBuilder.build(profile, globalSettings)
        stopLocked()
        activeProfile = profile
        mutableStatus.value = RuntimeStatus(RuntimeState.STARTING, profile.id, profile.tunMode, null, null, null)
        return@withLock when (profile.tunMode) {
            TunMode.VPN_SERVICE -> startVpn(profile, toml, globalSettings)
            TunMode.ROOT_TUN -> root.start(profile, toml, globalSettings).also { mutableStatus.value = it }
        }
    }

    suspend fun stop(): RuntimeStatus = mutex.withLock { stopLocked() }

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

    private suspend fun stopLocked(): RuntimeStatus {
        pollJob?.cancel()
        pollJob = null
        val profile = activeProfile
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
                        mutableStatus.value = status
                    }
                }
                delay(2_000)
            }
        }
    }

    private fun failure(profile: EasyTierProfile, error: String): RuntimeStatus =
        RuntimeStatus(RuntimeState.ERROR, profile.id, profile.tunMode, null, null, error).also { mutableStatus.value = it }

    private fun nativeError(fallback: String) = EasyTierJni.getLastError() ?: fallback
}

private data class NetworkInfo(val virtualIpv4: String?, val routes: List<String>, val error: String?)

private fun String.networkInfo(profileId: String): NetworkInfo? = runCatching {
    val map = Json.parseToJsonElement(this).jsonObject["map"]?.jsonObject ?: return null
    val info = map[profileId]?.jsonObject ?: return null
    val error = info["error_msg"]?.jsonPrimitive?.content
    val virtual = info["my_node_info"]?.jsonObject?.get("virtual_ipv4")?.jsonObject?.let { value ->
        val addr = value["address"]?.jsonObject?.get("addr")?.jsonPrimitive?.content
        val prefix = value["network_length"]?.jsonPrimitive?.content
        if (!addr.isNullOrBlank() && !prefix.isNullOrBlank()) "$addr/$prefix" else null
    }
    val routes = info["routes"]?.toString().orEmpty().split('"').filter { it.contains('/') }.distinct().sorted()
    NetworkInfo(virtual, routes, error)
}.getOrNull()
