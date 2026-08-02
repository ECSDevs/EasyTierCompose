package cc.ptoe.easytier.compose.transport

import android.content.Intent
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.RuntimeStatus
import kotlinx.coroutines.flow.StateFlow

sealed interface RuntimeEffect {
    data class RequestVpnPermission(val intent: Intent) : RuntimeEffect
}

interface RuntimeTransport {
    val status: StateFlow<RuntimeStatus>
    suspend fun start(profile: EasyTierProfile, toml: String): RuntimeStatus
    suspend fun stop()
}
