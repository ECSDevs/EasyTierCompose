package cc.ptoe.easytier.compose.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import cc.ptoe.easytier.compose.BuildConfig
import cc.ptoe.easytier.compose.R
import cc.ptoe.easytier.compose.data.GlobalSettings
import cc.ptoe.easytier.compose.data.RuntimeState
import cc.ptoe.easytier.compose.data.TunMode
import cc.ptoe.easytier.compose.ui.EasyTierUiState
import cc.ptoe.easytier.compose.ui.components.FormField
import cc.ptoe.easytier.compose.ui.components.SettingsGroup
import cc.ptoe.easytier.compose.ui.components.SettingsItem
import cc.ptoe.easytier.compose.ui.components.SwitchRow
import cc.ptoe.easytier.compose.ui.components.notificationsPermissionGranted

@Composable
internal fun SettingsScreen(
    state: EasyTierUiState,
    onTunMode: (TunMode) -> Unit,
    onGlobalSettings: (GlobalSettings) -> Unit,
    reset: () -> Unit,
) {
    val context = LocalContext.current
    var notificationsGranted by remember { mutableStateOf(notificationsPermissionGranted(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationsGranted = granted || notificationsPermissionGranted(context) }

    LifecycleResumeEffect(Unit) {
        notificationsGranted = notificationsPermissionGranted(context)
        onPauseOrDispose { }
    }

    val profile = state.profiles.firstOrNull { it.id == state.selectedProfileId }
    val active = state.runtime.state in setOf(RuntimeState.STARTING, RuntimeState.RUNNING)
    val tunMode = profile?.tunMode ?: TunMode.VPN_SERVICE
    val settings = state.globalSettings

    var confirmReset by remember { mutableStateOf(false) }
    var tunDeviceName by remember(settings.tunDeviceName) { mutableStateOf(settings.tunDeviceName) }
    var mtuInput by remember(settings.mtu) { mutableStateOf(settings.mtu.toString()) }
    var threadCountInput by remember(settings.multiThreadCount) { mutableStateOf(settings.multiThreadCount.toString()) }
    var foreignRelayBpsInput by remember(settings.foreignRelayBpsLimit) {
        mutableStateOf(if (settings.foreignRelayBpsLimit >= Long.MAX_VALUE) "" else settings.foreignRelayBpsLimit.toString())
    }
    var instanceRecvBpsInput by remember(settings.instanceRecvBpsLimit) {
        mutableStateOf(if (settings.instanceRecvBpsLimit >= Long.MAX_VALUE) "" else settings.instanceRecvBpsLimit.toString())
    }
    fun parseBpsLimit(raw: String): Long? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Long.MAX_VALUE
        return trimmed.toLongOrNull()?.takeIf { it >= 0 }
    }
    val mtuValid = mtuInput.toIntOrNull() in 576..9000
    val threadCountValid = (threadCountInput.toIntOrNull() ?: 0) > 0
    val foreignRelayBps = parseBpsLimit(foreignRelayBpsInput)
    val instanceRecvBps = parseBpsLimit(instanceRecvBpsInput)

    LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            SettingsGroup {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.settings_global_overrides), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_tun_mode), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(
                                    when {
                                        tunMode == TunMode.ROOT_TUN && settings.noTun -> R.string.settings_tun_root_no_tun
                                        tunMode == TunMode.ROOT_TUN -> R.string.settings_tun_root
                                        else -> R.string.settings_tun_vpn_service
                                    },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = tunMode == TunMode.ROOT_TUN,
                            onCheckedChange = { checked ->
                                onTunMode(if (checked) TunMode.ROOT_TUN else TunMode.VPN_SERVICE)
                            },
                            enabled = profile != null && !active,
                        )
                    }
                    AnimatedVisibility(visible = !settings.noTun && tunMode == TunMode.ROOT_TUN) {
                        OutlinedTextField(
                            value = tunDeviceName,
                            onValueChange = {
                                tunDeviceName = it
                                onGlobalSettings(settings.copy(tunDeviceName = it))
                            },
                            label = { Text(stringResource(R.string.settings_tun_device_name)) },
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    SwitchRow(stringResource(R.string.settings_no_tun), settings.noTun) { checked ->
                        onGlobalSettings(settings.copy(noTun = checked))
                    }
                    SwitchRow(stringResource(R.string.settings_start_on_boot), settings.startOnBoot) { checked ->
                        onGlobalSettings(settings.copy(startOnBoot = checked))
                    }
                }
            }
        }

        item {
            SettingsGroup {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.settings_engine), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        stringResource(R.string.settings_engine_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FormField(
                        stringResource(R.string.settings_mtu),
                        mtuInput,
                        if (mtuValid) null else stringResource(R.string.error_mtu_range),
                    ) { v ->
                        mtuInput = v.filter { c -> c.isDigit() }.take(5)
                        mtuInput.toIntOrNull()?.takeIf { it in 576..9000 }?.let { mtu ->
                            onGlobalSettings(settings.copy(mtu = mtu))
                        }
                    }
                    SwitchRow(stringResource(R.string.settings_multi_thread), settings.multiThread) { checked ->
                        onGlobalSettings(settings.copy(multiThread = checked))
                    }
                    FormField(
                        stringResource(R.string.settings_multi_thread_count),
                        threadCountInput,
                        if (threadCountValid) null else stringResource(R.string.error_thread_count_positive),
                    ) { v ->
                        threadCountInput = v.filter { c -> c.isDigit() }.take(3)
                        threadCountInput.toIntOrNull()?.takeIf { it > 0 }?.let { count ->
                            onGlobalSettings(settings.copy(multiThreadCount = count))
                        }
                    }
                    FormField(
                        stringResource(R.string.settings_foreign_relay_bps_limit),
                        foreignRelayBpsInput,
                        if (foreignRelayBps != null) null else stringResource(R.string.settings_bps_invalid),
                    ) { v ->
                        foreignRelayBpsInput = v.filter { c -> c.isDigit() }.take(19)
                        parseBpsLimit(foreignRelayBpsInput)?.let { limit ->
                            onGlobalSettings(settings.copy(foreignRelayBpsLimit = limit))
                        }
                    }
                    FormField(
                        stringResource(R.string.settings_instance_recv_bps_limit),
                        instanceRecvBpsInput,
                        if (instanceRecvBps != null) null else stringResource(R.string.settings_bps_invalid),
                    ) { v ->
                        instanceRecvBpsInput = v.filter { c -> c.isDigit() }.take(19)
                        parseBpsLimit(instanceRecvBpsInput)?.let { limit ->
                            onGlobalSettings(settings.copy(instanceRecvBpsLimit = limit))
                        }
                    }
                }
            }
        }

        if (!notificationsGranted && Build.VERSION.SDK_INT >= 33) {
            item {
                SettingsGroup {
                    SettingsItem(
                        icon = Icons.Default.NotificationsActive,
                        title = stringResource(R.string.settings_notifications),
                        subtitle = stringResource(R.string.settings_notifications_subtitle),
                        onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    )
                }
            }
        }

        item {
            SettingsGroup {
                SettingsItem(
                    icon = Icons.Default.Router,
                    title = stringResource(R.string.app_name),
                    subtitle = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsItem(
                    icon = Icons.Default.RestartAlt,
                    title = stringResource(R.string.settings_reset_all),
                    subtitle = stringResource(R.string.settings_restore_default),
                    onClick = { confirmReset = true },
                )
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.settings_reset_title)) },
            text = { Text(stringResource(R.string.settings_reset_message)) },
            confirmButton = {
                Button(
                    onClick = { reset(); confirmReset = false },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.action_reset)) }
            },
            dismissButton = { OutlinedButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
