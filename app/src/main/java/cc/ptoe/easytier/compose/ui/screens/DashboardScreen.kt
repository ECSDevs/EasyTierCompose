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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cc.ptoe.easytier.compose.core.toV2rayShareLink
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
    val subtitle = when {
        !hasProfile -> "Click to create a profile"
        active -> "Click to disconnect"
        else -> "Click to connect"
    }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = if (active) "disconnect_button" else "connect_button" },
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
                    text = profileName ?: "No profile selected",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
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
    val statusText = statusState.name.lowercase().replaceFirstChar { it.uppercase() }
    SettingsGroup {
        StatusDetailRow(
            icon = Icons.Default.Layers,
            title = "Network",
            value = networkName,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        StatusDetailRow(
            icon = Icons.Default.Router,
            title = "Virtual IP",
            value = virtualIpv4,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        StatusDetailRow(
            icon = Icons.Default.AdminPanelSettings,
            title = "Hostname",
            value = hostname,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        StatusDetailRow(
            icon = Icons.Default.VpnKey,
            title = "NAT type",
            value = natType,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        StatusDetailRow(
            icon = Icons.Default.Check,
            title = "Status",
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
            text = value?.ifEmpty { null } ?: "—",
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
                    text = "WireGuard Portal",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (portal != null) {
                    // Offer both formats: the v2rayN-style wireguard:// share link can be
                    // imported directly into v2rayN/v2rayNG, while the raw WireGuard INI
                    // config can be imported by v2rayN or used to fill in NekoBox manually
                    // (NekoBox has no wireguard:// share link parser at all).
                    var menuExpanded by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { menuExpanded = true }) {
                            Text(if (copied) "Copied" else "Copy")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Share link (v2rayN style)") },
                                onClick = {
                                    menuExpanded = false
                                    val shareLink = portal.toV2rayShareLink(remark) ?: portal.clientConfig
                                    val clipboard =
                                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("WireGuard node link", shareLink))
                                    copied = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("WireGuard config (INI)") },
                                onClick = {
                                    menuExpanded = false
                                    val clipboard =
                                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(
                                        ClipData.newPlainText("WireGuard config", portal.clientConfig),
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
                    text = "Waiting for the WireGuard portal to start…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // SelectionContainer makes the credential values long-press selectable/copyable.
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        parseWireGuardConfig(portal.clientConfig).forEach { (label, value) ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = label,
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
                                    text = "Connected clients",
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
            val key = line.substringBefore('=').trim()
            val value = line.substringAfter('=').trim()
            wireGuardFieldLabel(key) to value
        }
        .toList()

private fun wireGuardFieldLabel(key: String): String = when (key) {
    "PrivateKey" -> "Private Key"
    "PublicKey" -> "Public Key"
    "AllowedIPs" -> "Allowed IPs"
    "PersistentKeepalive" -> "Persistent Keepalive"
    else -> key
}
