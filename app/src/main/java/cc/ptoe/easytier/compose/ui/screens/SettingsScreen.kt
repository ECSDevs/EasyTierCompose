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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import cc.ptoe.easytier.compose.BuildConfig
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
    // Engine text fields keep local drafts; blank bps limit means "no limit".
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
                    Text("Global overrides", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    // TUN Mode switch — always visible. Under No TUN, Root mode
                    // still matters: it runs the EasyTier core in the root daemon
                    // so its traffic uses the physical NIC instead of going
                    // through VpnService / other proxies' TUNs.
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("TUN Mode", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                when {
                                    tunMode == TunMode.ROOT_TUN && settings.noTun ->
                                        "Root — core runs in root daemon via physical NIC"
                                    tunMode == TunMode.ROOT_TUN -> "Root"
                                    else -> "VPN Service"
                                },
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
                    // TUN device name only applies to Root mode (and only when TUN is not disabled).
                    // Saved instantly on change; downstream falls back to "easytier0" when blank.
                    AnimatedVisibility(visible = !settings.noTun && tunMode == TunMode.ROOT_TUN) {
                        OutlinedTextField(
                            value = tunDeviceName,
                            onValueChange = {
                                tunDeviceName = it
                                onGlobalSettings(settings.copy(tunDeviceName = it))
                            },
                            label = { Text("TUN device name") },
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    SwitchRow("No TUN", settings.noTun) { checked ->
                        onGlobalSettings(settings.copy(noTun = checked))
                    }
                    SwitchRow("Start on boot", settings.startOnBoot) { checked ->
                        onGlobalSettings(settings.copy(startOnBoot = checked))
                    }
                }
            }
        }

        item {
            SettingsGroup {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Engine", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        "Device-local options shared by all profiles. They apply to every EasyTier network and do not affect other devices.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Text fields save instantly while the input is valid; invalid input keeps
                    // the last persisted value and shows an inline error.
                    FormField("MTU", mtuInput, if (mtuValid) null else "MTU must be between 576 and 9000") { v ->
                        mtuInput = v.filter { c -> c.isDigit() }.take(5)
                        mtuInput.toIntOrNull()?.takeIf { it in 576..9000 }?.let { mtu ->
                            onGlobalSettings(settings.copy(mtu = mtu))
                        }
                    }
                    SwitchRow("Multi-thread", settings.multiThread) { checked -> onGlobalSettings(settings.copy(multiThread = checked)) }
                    FormField("Multi-thread count", threadCountInput, if (threadCountValid) null else "Thread count must be greater than 0") { v ->
                        threadCountInput = v.filter { c -> c.isDigit() }.take(3)
                        threadCountInput.toIntOrNull()?.takeIf { it > 0 }?.let { count ->
                            onGlobalSettings(settings.copy(multiThreadCount = count))
                        }
                    }
                    FormField("Foreign relay bps limit", foreignRelayBpsInput, if (foreignRelayBps != null) null else "Must be a non-negative number (blank = no limit)") { v ->
                        foreignRelayBpsInput = v.filter { c -> c.isDigit() }.take(19)
                        parseBpsLimit(foreignRelayBpsInput)?.let { limit ->
                            onGlobalSettings(settings.copy(foreignRelayBpsLimit = limit))
                        }
                    }
                    FormField("Instance recv bps limit", instanceRecvBpsInput, if (instanceRecvBps != null) null else "Must be a non-negative number (blank = no limit)") { v ->
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
