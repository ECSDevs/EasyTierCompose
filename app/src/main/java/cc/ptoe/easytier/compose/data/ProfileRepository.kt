package cc.ptoe.easytier.compose.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.easyTierProfilesDataStore by preferencesDataStore(name = "easytier_profiles")
private val Context.easyTierGlobalSettingsDataStore by preferencesDataStore(name = "easytier_global_settings")

class ProfileRepository(private val context: Context) {
    private val profilesKey = stringPreferencesKey("profiles_v1")
    private val corruptProfilesKey = stringPreferencesKey("profiles_v1_corrupt")
    private val selectedProfileKey = stringPreferencesKey("selected_profile_id")
    private val json = Json { ignoreUnknownKeys = true }

    val profiles: Flow<List<EasyTierProfile>> = context.easyTierProfilesDataStore.data.map { preferences ->
        val raw = preferences[profilesKey] ?: return@map emptyList()
        runCatching { json.decodeFromString(ListSerializer(EasyTierProfile.serializer()), raw) }
            .getOrElse {
                context.easyTierProfilesDataStore.edit { mutable ->
                    mutable[corruptProfilesKey] = raw
                    mutable.remove(profilesKey)
                }
                emptyList()
            }
    }

    val selectedProfileId: Flow<String?> = context.easyTierProfilesDataStore.data.map { it[selectedProfileKey] }

    fun newProfile(): EasyTierProfile = EasyTierProfile(
        id = UUID.randomUUID().toString(),
        name = "",
        networkName = "",
        networkSecret = "",
        virtualIpv4 = null,
        dhcp = false,
        enableMagicDns = false,
        tunMode = TunMode.VPN_SERVICE,
    )

    suspend fun save(profile: EasyTierProfile) {
        context.easyTierProfilesDataStore.edit { preferences ->
            val existing = decodeProfiles(preferences[profilesKey])
            val index = existing.indexOfFirst { it.id == profile.id }
            val updated = if (index < 0) existing + profile else existing.toMutableList().also { it[index] = profile }
            preferences[profilesKey] = json.encodeToString(ListSerializer(EasyTierProfile.serializer()), updated)
        }
    }

    suspend fun delete(profileId: String) {
        context.easyTierProfilesDataStore.edit { preferences ->
            val updated = decodeProfiles(preferences[profilesKey]).filterNot { it.id == profileId }
            preferences[profilesKey] = json.encodeToString(ListSerializer(EasyTierProfile.serializer()), updated)
            if (preferences[selectedProfileKey] == profileId) preferences.remove(selectedProfileKey)
        }
    }

    suspend fun select(profileId: String?) {
        context.easyTierProfilesDataStore.edit { preferences ->
            if (profileId == null) preferences.remove(selectedProfileKey) else preferences[selectedProfileKey] = profileId
        }
    }

    suspend fun reset() {
        context.easyTierProfilesDataStore.edit { preferences ->
            preferences.remove(profilesKey)
            preferences.remove(selectedProfileKey)
        }
    }

    private fun decodeProfiles(raw: String?): List<EasyTierProfile> = raw?.let {
        runCatching { json.decodeFromString(ListSerializer(EasyTierProfile.serializer()), it) }.getOrDefault(emptyList())
    } ?: emptyList()
}

class GlobalSettingsRepository(private val context: Context) {
    private val settingsKey = stringPreferencesKey("settings_v1")
    private val json = Json { ignoreUnknownKeys = true }

    // Legacy per-field keys, kept read-only to migrate settings written by
    // older app versions. Once `settings_v1` exists they are ignored.
    private val legacyTunDeviceNameKey = stringPreferencesKey("tun_device_name")
    private val legacyNoTunKey = booleanPreferencesKey("no_tun")
    private val legacyStartOnBootKey = booleanPreferencesKey("start_on_boot")

    val settings: Flow<GlobalSettings> = context.easyTierGlobalSettingsDataStore.data.map { preferences ->
        preferences[settingsKey]?.let { raw ->
            runCatching { json.decodeFromString(GlobalSettings.serializer(), raw) }.getOrNull()
        } ?: GlobalSettings(
            tunDeviceName = preferences[legacyTunDeviceNameKey] ?: "easytier0",
            noTun = preferences[legacyNoTunKey] ?: false,
            startOnBoot = preferences[legacyStartOnBootKey] ?: false,
        )
    }

    suspend fun update(settings: GlobalSettings) {
        context.easyTierGlobalSettingsDataStore.edit { preferences ->
            preferences[settingsKey] = json.encodeToString(GlobalSettings.serializer(), settings)
        }
    }
}
