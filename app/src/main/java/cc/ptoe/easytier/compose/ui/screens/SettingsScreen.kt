package cc.ptoe.easytier.compose.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import cc.ptoe.easytier.compose.BuildConfig
import cc.ptoe.easytier.compose.data.GlobalSettings
import cc.ptoe.easytier.compose.data.RuntimeState
import cc.ptoe.easytier.compose.data.TunMode
import cc.ptoe.easytier.compose.ui.EasyTierUiState
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
    var socks5Port by remember(settings.socks5Port) { mutableStateOf(settings.socks5Port.toString()) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            SettingsGroup {
                SettingsItem(
                    icon = Icons.Default.AdminPanelSettings,
                    title = "TUN Mode",
                    subtitle = when {
                        profile == null -> "No profile selected"
                        tunMode == TunMode.VPN_SERVICE -> "VPN Service"
                        else -> "Root TUN"
                    },
                    trailing = {
                        Switch(
                            checked = tunMode == TunMode.ROOT_TUN,
                            onCheckedChange = { checked ->
                                onTunMode(if (checked) TunMode.ROOT_TUN else TunMode.VPN_SERVICE)
                            },
                            enabled = profile != null && !active,
                        )
                    },
                )
            }
        }

        item {
            SettingsGroup {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Global overrides", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    OutlinedTextField(
                        value = tunDeviceName,
                        onValueChange = { tunDeviceName = it },
                        label = { Text("TUN device name (root only)") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SwitchRow("No TUN", settings.noTun) { checked ->
                        onGlobalSettings(settings.copy(noTun = checked))
                    }
                    SwitchRow("Start on boot", settings.startOnBoot) { checked ->
                        onGlobalSettings(settings.copy(startOnBoot = checked))
                    }
                    SwitchRow("SOCKS5 allow LAN", settings.socks5AllowLan) { checked ->
                        onGlobalSettings(settings.copy(socks5AllowLan = checked))
                    }
                    OutlinedTextField(
                        value = socks5Port,
                        onValueChange = { socks5Port = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text("SOCKS5 port") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val portValid = socks5Port.toIntOrNull() in 1..65535
                    if (!portValid) {
                        Text("Port must be between 1 and 65535", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(
                            onClick = {
                                onGlobalSettings(settings.copy(
                                    tunDeviceName = tunDeviceName.trim().ifBlank { "easytier0" },
                                    socks5Port = socks5Port.toIntOrNull()?.takeIf { it in 1..65535 } ?: 1080,
                                ))
                            },
                            enabled = portValid,
                            shape = MaterialTheme.shapes.medium,
                        ) { Text("Save") }
                    }
                }
            }
        }

        if (!notificationsGranted && Build.VERSION.SDK_INT >= 33) {
            item {
                SettingsGroup {
                    SettingsItem(
                        icon = Icons.Default.NotificationsActive,
                        title = "Notifications",
                        subtitle = "Allow for VPN status",
                        onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    )
                }
            }
        }

        item {
            SettingsGroup {
                SettingsItem(
                    icon = Icons.Default.Router,
                    title = "EasyTier",
                    subtitle = "Version ${BuildConfig.VERSION_NAME}",
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsItem(
                    icon = Icons.Default.RestartAlt,
                    title = "Reset all profiles",
                    subtitle = "Restore default configuration",
                    onClick = { confirmReset = true },
                )
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset all profiles?") },
            text = { Text("The current active connection will stop first.") },
            confirmButton = {
                Button(
                    onClick = { reset(); confirmReset = false },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Reset") }
            },
            dismissButton = { OutlinedButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}
