package cc.ptoe.easytier.compose.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cc.ptoe.easytier.compose.core.toV2rayShareLink
import cc.ptoe.easytier.compose.R
import cc.ptoe.easytier.compose.data.RuntimeState
import cc.ptoe.easytier.compose.data.RuntimeStatus
import cc.ptoe.easytier.compose.data.WireGuardPortalInfo
import cc.ptoe.easytier.compose.ui.EasyTierUiState
import cc.ptoe.easytier.compose.ui.components.SettingsGroup
import kotlinx.coroutines.delay

@Composable
internal fun DashboardScreen(
    state: EasyTierUiState,
    connect: () -> Unit,
    disconnect: () -> Unit,
    onCreate: () -> Unit,
) {
    val profile = state.profiles.firstOrNull { it.id == state.selectedProfileId }
    val active = state.runtime.state in setOf(RuntimeState.STARTING, RuntimeState.RUNNING)

    val cardClick: () -> Unit = when {
        profile == null -> onCreate
        active -> disconnect
        else -> connect
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val error = state.runtime.error
        if (error != null) {
            item {
                ErrorBanner(
                    message = error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item {
            StatusCard(
                runtime = state.runtime,
                profileName = profile?.name,
                active = active,
                hasProfile = profile != null,
                onClick = cardClick,
            )
        }

        item {
            StatusDetailsGroup(
                networkName = profile?.networkName,
                virtualIpv4 = state.runtime.virtualIpv4,
                hostname = state.runtime.hostname,
                natType = state.runtime.natType,
                statusState = state.runtime.state,
            )
        }

        if (profile?.vpnPortal != null && active) {
            item {
                WireGuardPortalCard(
                    portal = state.runtime.wireguardPortal,
                    remark = profile.name,
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = AlertDialogDefaults.shape,
        color = MaterialTheme.colorScheme.errorContainer,
        tonalElevation = AlertDialogDefaults.TonalElevation,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun StatusCard(
    runtime: RuntimeStatus,
    profileName: String?,
    active: Boolean,
    hasProfile: Boolean,
    onClick: () -> Unit,
) {
    val statusState = runtime.state
    val (iconBg, iconFg, iconVector) = when (statusState) {
        RuntimeState.RUNNING -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            Icons.Default.Check,
        )
        RuntimeState.STARTING, RuntimeState.STOPPING -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            Icons.Default.PowerSettingsNew,
        )
        RuntimeState.ERROR -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            Icons.Default.Info,
        )
        else -> Triple(
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Default.PowerSettingsNew,
        )
    }
    val subtitleRes = when {
        !hasProfile -> R.string.dashboard_click_create_profile
        active -> R.string.dashboard_click_disconnect
        else -> R.string.dashboard_click_connect
    }
    val actionDescriptionRes = when {
        !hasProfile -> R.string.content_create_profile
        active -> R.string.content_disconnect
        else -> R.string.content_connect
    }
    val actionDescription = stringResource(actionDescriptionRes)

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = actionDescription },
        shape = AlertDialogDefaults.shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = AlertDialogDefaults.TonalElevation,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(iconBg),
                contentAlignment = Alignment.Center,
            ) {
                if (statusState == RuntimeState.STARTING || statusState == RuntimeState.STOPPING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.5.dp,
                        color = iconFg,
                    )
                } else {
                    Icon(
                        imageVector = iconVector,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = iconFg,
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = profileName ?: stringResource(R.string.dashboard_no_profile),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(subtitleRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusDetailsGroup(
    networkName: String?,
    virtualIpv4: String?,
    hostname: String?,
    natType: String?,
    statusState: RuntimeState,
) {
    val statusText = when (statusState) {
        RuntimeState.STOPPED -> stringResource(R.string.status_stopped)
        RuntimeState.STARTING -> stringResource(R.string.status_starting)
        RuntimeState.RUNNING -> stringResource(R.string.status_running)
        RuntimeState.STOPPING -> stringResource(R.string.status_stopping)
        RuntimeState.ERROR -> stringResource(R.string.status_error)
    }
    SettingsGroup {
        StatusDetailRow(
            icon = Icons.Default.Layers,
            title = stringResource(R.string.detail_network),
            value = networkName,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        StatusDetailRow(
            icon = Icons.Default.Router,
            title = stringResource(R.string.detail_virtual_ip),
            value = virtualIpv4,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        StatusDetailRow(
            icon = Icons.Default.AdminPanelSettings,
            title = stringResource(R.string.detail_hostname),
            value = hostname,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        StatusDetailRow(
            icon = Icons.Default.VpnKey,
            title = stringResource(R.string.detail_nat_type),
            value = localizedNatType(natType),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        StatusDetailRow(
            icon = Icons.Default.Check,
            title = stringResource(R.string.detail_status),
            value = statusText,
        )
    }
}

@Composable
private fun StatusDetailRow(
    icon: ImageVector,
    title: String,
    value: String?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = value?.ifEmpty { null } ?: stringResource(R.string.value_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Shows the WireGuard portal credentials rendered by EasyTier Core
 * (VpnPortalRpc.GetVpnPortalInfo) for profiles that enabled the VPN portal.
 * Hidden until the session is active; shows a waiting hint while the portal
 * has not started yet.
 */
@Composable
private fun WireGuardPortalCard(portal: WireGuardPortalInfo?, remark: String) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AlertDialogDefaults.shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = AlertDialogDefaults.TonalElevation,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = stringResource(R.string.wireguard_portal),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (portal != null) {
                    var menuExpanded by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { menuExpanded = true }) {
                            Text(stringResource(if (copied) R.string.action_copied else R.string.action_copy))
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.wireguard_share_link)) },
                                onClick = {
                                    menuExpanded = false
                                    val shareLink = portal.toV2rayShareLink(remark) ?: portal.clientConfig
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(
                                        ClipData.newPlainText(context.getString(R.string.wireguard_node_link_clipboard), shareLink),
                                    )
                                    copied = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.wireguard_config_ini)) },
                                onClick = {
                                    menuExpanded = false
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(
                                        ClipData.newPlainText(context.getString(R.string.wireguard_config_clipboard), portal.clientConfig),
                                    )
                                    copied = true
                                },
                            )
                        }
                    }
                }
            }

            if (portal == null) {
                Text(
                    text = stringResource(R.string.wireguard_waiting),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        parseWireGuardConfig(portal.clientConfig).forEach { (key, value) ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = wireGuardFieldLabel(key)?.let { stringResource(it) } ?: key,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = value,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        if (portal.connectedClients.isNotEmpty()) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = stringResource(R.string.wireguard_connected_clients),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = portal.connectedClients.joinToString("\n"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// EasyTier Core renders the portal client config as a standard WireGuard INI
// config ([Interface]/[Peer] sections with `Key = Value # comment` lines).
// Strip sections/comments and humanize the well-known keys for display.
private fun parseWireGuardConfig(config: String): List<Pair<String, String>> =
    config.lineSequence()
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() && !it.startsWith('[') && it.contains('=') }
        .map { line ->
            line.substringBefore('=').trim() to line.substringAfter('=').trim()
        }
        .toList()

private fun wireGuardFieldLabel(key: String): Int? = when (key) {
    "PrivateKey" -> R.string.wireguard_private_key
    "PublicKey" -> R.string.wireguard_public_key
    "AllowedIPs" -> R.string.wireguard_allowed_ips
    "PersistentKeepalive" -> R.string.wireguard_persistent_keepalive
    else -> null
}

@Composable
internal fun localizedNatType(raw: String?): String? {
    val value = raw?.takeIf { it.isNotBlank() } ?: return null
    return when (value) {
        "Unknown" -> stringResource(R.string.nat_unknown)
        "OpenInternet" -> stringResource(R.string.nat_open_internet)
        "NoPAT" -> stringResource(R.string.nat_no_pat)
        "FullCone" -> stringResource(R.string.nat_full_cone)
        "Restricted" -> stringResource(R.string.nat_restricted)
        "PortRestricted" -> stringResource(R.string.nat_port_restricted)
        "Symmetric" -> stringResource(R.string.nat_symmetric)
        "SymUdpFirewall" -> stringResource(R.string.nat_symmetric_udp_firewall)
        "SymmetricEasyInc" -> stringResource(R.string.nat_symmetric_easy_increase)
        "SymmetricEasyDec" -> stringResource(R.string.nat_symmetric_easy_decrease)
        else -> value
    }
}
