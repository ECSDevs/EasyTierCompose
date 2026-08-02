package cc.ptoe.easytier.compose.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.ptoe.easytier.compose.core.EasyTierRuntimeCoordinator
import cc.ptoe.easytier.compose.core.ProfileValidator
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.GlobalSettings
import cc.ptoe.easytier.compose.data.GlobalSettingsRepository
import cc.ptoe.easytier.compose.data.ProfileRepository
import cc.ptoe.easytier.compose.data.RuntimeState
import cc.ptoe.easytier.compose.data.RuntimeStatus
import cc.ptoe.easytier.compose.data.TunMode
import cc.ptoe.easytier.compose.transport.RuntimeEffect
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class EasyTierUiState(
    val profiles: List<EasyTierProfile> = emptyList(),
    val selectedProfileId: String? = null,
    val draft: EasyTierProfile? = null,
    val runtime: RuntimeStatus = RuntimeStatus.Stopped,
    val fieldErrors: Map<String, String> = emptyMap(),
    val globalSettings: GlobalSettings = GlobalSettings(),
)

class EasyTierViewModel(
    private val repository: ProfileRepository,
    private val coordinator: EasyTierRuntimeCoordinator,
    private val globalSettingsRepository: GlobalSettingsRepository,
) : ViewModel() {
    private val selectedId = MutableStateFlow<String?>(null)
    private val draft = MutableStateFlow<EasyTierProfile?>(null)
    private val errors = MutableStateFlow<Map<String, String>>(emptyMap())
    private val globalSettings = MutableStateFlow(GlobalSettings())
    val effects = MutableSharedFlow<RuntimeEffect>()

    val state: StateFlow<EasyTierUiState> = combine(
        repository.profiles,
        repository.selectedProfileId,
        selectedId,
        draft,
        coordinator.status,
        errors,
        globalSettings,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val profiles = values[0] as List<EasyTierProfile>
        val persistedSelection = values[1] as String?
        val localSelection = values[2] as String?
        val editing = values[3] as EasyTierProfile?
        val runtime = values[4] as RuntimeStatus
        val fieldErrors = values[5] as Map<String, String>
        val settings = values[6] as GlobalSettings
        EasyTierUiState(profiles, localSelection ?: persistedSelection ?: profiles.firstOrNull()?.id, editing, runtime, fieldErrors, settings)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EasyTierUiState())

    init {
        viewModelScope.launch { coordinator.effects.collect { effects.emit(it) } }
        viewModelScope.launch {
            globalSettingsRepository.settings.collect { globalSettings.value = it }
        }
    }

    fun selectProfile(id: String?) = viewModelScope.launch { selectedId.value = id; repository.select(id) }
    fun beginCreate() { draft.value = repository.newProfile(); errors.value = emptyMap() }
    fun beginEdit(profile: EasyTierProfile) { draft.value = profile; errors.value = emptyMap() }
    fun discardDraft() { draft.value = null; errors.value = emptyMap() }
    fun updateDraft(transform: (EasyTierProfile) -> EasyTierProfile) { draft.value = draft.value?.let(transform) }

    fun saveDraft() = viewModelScope.launch {
        val value = draft.value ?: return@launch
        if (state.value.runtime.profileId == value.id && state.value.runtime.state in setOf(RuntimeState.STARTING, RuntimeState.RUNNING)) {
            errors.value = mapOf("form" to "Disconnect before saving this profile")
            return@launch
        }
        val validation = ProfileValidator().validate(value, globalSettings.value)
        if (validation.isNotEmpty()) { errors.value = validation; return@launch }
        repository.save(value)
        selectedId.value = value.id
        repository.select(value.id)
        draft.value = null
        errors.value = emptyMap()
    }

    fun delete(profile: EasyTierProfile) = viewModelScope.launch {
        if (state.value.runtime.profileId == profile.id && state.value.runtime.state != RuntimeState.STOPPED) return@launch
        repository.delete(profile.id)
    }

    fun updateTunMode(mode: TunMode) = viewModelScope.launch {
        val profile = selectedProfile() ?: return@launch
        if (state.value.runtime.state in setOf(RuntimeState.STARTING, RuntimeState.RUNNING)) return@launch
        val updated = profile.copy(tunMode = mode, enableMagicDns = profile.enableMagicDns && mode == TunMode.VPN_SERVICE)
        repository.save(updated)
        draft.value = draft.value?.takeIf { it.id == updated.id }?.copy(tunMode = mode, enableMagicDns = updated.enableMagicDns)
    }

    fun updateGlobalSettings(settings: GlobalSettings) = viewModelScope.launch {
        globalSettingsRepository.update(settings)
    }

    fun connect() = viewModelScope.launch { selectedProfile()?.let { coordinator.start(it, globalSettings.value) } }
    fun disconnect() = viewModelScope.launch { coordinator.stop() }
    fun onVpnPermissionResult(granted: Boolean) = viewModelScope.launch { coordinator.onVpnPermissionResult(granted) }
    fun resetProfiles() = viewModelScope.launch { coordinator.stop(); repository.reset(); selectedId.value = null }
    private fun selectedProfile() = state.value.profiles.firstOrNull { it.id == state.value.selectedProfileId }
}
