@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cc.ptoe.easytier.compose.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import cc.ptoe.easytier.compose.BuildConfig
import cc.ptoe.easytier.compose.data.CompressionAlgo
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.EncryptionAlgorithm
import cc.ptoe.easytier.compose.data.GlobalSettings
import cc.ptoe.easytier.compose.data.Peer
import cc.ptoe.easytier.compose.data.PortForward
import cc.ptoe.easytier.compose.data.ProxyNetwork
import cc.ptoe.easytier.compose.data.RuntimePeer
import cc.ptoe.easytier.compose.data.RuntimeState
import cc.ptoe.easytier.compose.data.RuntimeStatus
import cc.ptoe.easytier.compose.data.SecureMode
import cc.ptoe.easytier.compose.data.TunMode
import cc.ptoe.easytier.compose.data.VpnPortal
import cc.ptoe.easytier.compose.transport.RuntimeEffect

private enum class Destination(val label: String, val icon: ImageVector) {
    Dashboard("Dashboard", Icons.Default.Dashboard),
    Profiles("Profiles", Icons.Default.Layers),
    Peers("Peers", Icons.Default.People),
    Settings("Settings", Icons.Default.Settings),
    Editor("Profile", Icons.Default.Edit),
}

@Composable
fun EasyTierApp(
    viewModel: EasyTierViewModel,
    requestVpnPermission: (android.content.Intent) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var destination by remember { mutableStateOf(Destination.Dashboard) }
    val wide = LocalConfiguration.current.screenWidthDp >= 840
    val draftRunning = state.draft?.let { draft ->
        state.runtime.profileId == draft.id && state.runtime.state in setOf(RuntimeState.STARTING, RuntimeState.RUNNING)
    } ?: false

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            if (effect is RuntimeEffect.RequestVpnPermission) requestVpnPermission(effect.intent)
        }
    }
    LaunchedEffect(state.draft) {
        if (state.draft == null && destination == Destination.Editor) destination = Destination.Profiles
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(destination.label) },
                navigationIcon = {
                    if (destination == Destination.Editor) {
                        IconButton(onClick = {
                            viewModel.discardDraft()
                            destination = Destination.Profiles
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (destination == Destination.Editor) {
                        IconButton(
                            onClick = viewModel::saveDraft,
                            enabled = !draftRunning,
                            modifier = Modifier.semantics { contentDescription = "save_profile" },
                        ) {
                            Icon(Icons.Default.Check, contentDescription = "Save")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (!wide && destination != Destination.Editor) {
                AppNavigationBar(destination) { destination = it }
            }
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (wide && destination != Destination.Editor) {
                AppNavigationRail(destination) { destination = it }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            Box(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp).widthIn(max = 900.dp)) {
                when (destination) {
                    Destination.Dashboard -> DashboardScreen(
                        state = state,
                        connect = viewModel::connect,
                        disconnect = viewModel::disconnect,
                        onCreate = { viewModel.beginCreate(); destination = Destination.Editor },
                    )
                    Destination.Profiles -> ProfilesScreen(
                        state = state,
                        add = { viewModel.beginCreate(); destination = Destination.Editor },
                        edit = { viewModel.beginEdit(it); destination = Destination.Editor },
                        delete = viewModel::delete,
                    )
                    Destination.Peers -> PeersScreen(state = state)
                    Destination.Editor -> state.draft?.let { draft ->
                        ProfileEditorScreen(
                            profile = draft,
                            errors = state.fieldErrors,
                            running = draftRunning,
                            update = viewModel::updateDraft,
                            save = viewModel::saveDraft,
                        )
                    }
                    Destination.Settings -> SettingsScreen(
                        state = state,
                        onTunMode = viewModel::updateTunMode,
                        onGlobalSettings = viewModel::updateGlobalSettings,
                        reset = viewModel::resetProfiles,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppNavigationBar(selected: Destination, select: (Destination) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Destination.entries.filter { it != Destination.Editor }.forEach { item ->
            NavigationBarItem(
                selected = selected == item,
                onClick = { select(item) },
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label) },
            )
        }
    }
}

@Composable
private fun AppNavigationRail(selected: Destination, select: (Destination) -> Unit) {
    NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Spacer(Modifier.height(16.dp))
        Destination.entries.filter { it != Destination.Editor }.forEach { item ->
            NavigationRailItem(
                selected = selected == item,
                onClick = { select(item) },
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label) },
            )
        }
    }
}

@Composable
private fun DashboardScreen(
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

@Composable
private fun ProfilesScreen(
    state: EasyTierUiState,
    add: () -> Unit,
    edit: (EasyTierProfile) -> Unit,
    delete: (EasyTierProfile) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<EasyTierProfile?>(null) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(state.profiles, key = { it.id }) { profile ->
                val isSelected = profile.id == state.selectedProfileId
                OutlinedCard(
                    onClick = { edit(profile) },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "profile_${profile.id}" },
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(profile.name, style = MaterialTheme.typography.titleLarge)
                                if (isSelected) {
                                    AssistChip(
                                        onClick = {},
                                        label = { Text("Selected", style = MaterialTheme.typography.labelMedium) },
                                        colors = AssistChipDefaults.assistChipColors(
                                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                                            labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        ),
                                    )
                                }
                            }
                            Text(
                                text = "${profile.networkName} • ${profile.peers.size} peers",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { edit(profile) }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit Profile")
                        }
                        IconButton(
                            enabled = state.runtime.profileId != profile.id || state.runtime.state == RuntimeState.STOPPED,
                            onClick = { pendingDelete = profile },
                            modifier = Modifier.semantics { contentDescription = "delete_profile" },
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Profile")
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = add,
            modifier = Modifier.align(Alignment.BottomEnd).semantics { contentDescription = "create_profile" },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            icon = { Icon(Icons.Default.Add, null) },
            text = { Text("Add Profile") },
        )
    }

    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${profile.name}?") },
            text = { Text(if (state.runtime.profileId == profile.id && state.runtime.state != RuntimeState.STOPPED) "Disconnect before deleting" else "This profile will be removed from this device.") },
            confirmButton = {
                Button(
                    onClick = { delete(profile); pendingDelete = null },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { OutlinedButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PeersScreen(state: EasyTierUiState) {
    var selected by remember { mutableStateOf<RuntimePeer?>(null) }
    val peers = state.runtime.peers
    val running = state.runtime.state in setOf(RuntimeState.STARTING, RuntimeState.RUNNING)

    Box(Modifier.fillMaxSize()) {
        if (peers.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Default.People,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    text = if (running) "No peers connected" else "Connect to a network to view peers",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(peers, key = { it.hostname + it.virtualIpv4.orEmpty() }) { peer ->
                    OutlinedCard(
                        onClick = { selected = peer },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.outlinedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Router,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp),
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = peer.hostname,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = peer.virtualIpv4 ?: "—",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = peer.latencyMs?.let { "%.0f ms".format(it) } ?: "—",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    selected?.let { peer -> PeerDetailsDialog(peer = peer, onDismiss = { selected = null }) }
}

@Composable
private fun PeerDetailsDialog(peer: RuntimePeer, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(peer.hostname) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                PeerDetailRow("Virtual IP", peer.virtualIpv4)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PeerDetailRow("Latency", peer.latencyMs?.let { "%.1f ms".format(it) })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PeerDetailRow("Connection", peer.connectionType)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PeerDetailRow("Tunnel", peer.tunnelProtos.joinToString(", ").ifEmpty { "—" })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PeerDetailRow("Loss rate", peer.lossRate?.let { "%.1f%%".format(it * 100.0) })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PeerDetailRow("NAT type", peer.natType)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun PeerDetailRow(title: String, value: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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

@Composable
private fun ProfileEditorScreen(
    profile: EasyTierProfile,
    errors: Map<String, String>,
    running: Boolean,
    update: ((EasyTierProfile) -> EasyTierProfile) -> Unit,
    save: () -> Unit,
) {
    var revealSecret by remember { mutableStateOf(false) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (running) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        text = "Disconnect before saving changes to this profile.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        item {
            SectionCard(title = "General", icon = Icons.Default.Info) {
                FormField("Profile name", profile.name, errors["name"]) { v -> update { it.copy(name = v) } }
                FormField("Hostname", profile.hostname.orEmpty(), null) { v -> update { it.copy(hostname = v.ifBlank { null }) } }
                FormField("Network name", profile.networkName, errors["networkName"]) { v -> update { it.copy(networkName = v) } }
                FormField(
                    label = "Network secret",
                    value = profile.networkSecret,
                    error = null,
                    transformation = if (revealSecret) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { revealSecret = !revealSecret }) {
                            Icon(
                                imageVector = if (revealSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (revealSecret) "Hide secret" else "Reveal secret",
                            )
                        }
                    },
                ) { v -> update { it.copy(networkSecret = v) } }
                SwitchRow("Use DHCP", profile.dhcp) { checked -> update { it.copy(dhcp = checked, virtualIpv4 = if (checked) null else it.virtualIpv4) } }
                if (!profile.dhcp) {
                    FormField("Static IPv4 CIDR", profile.virtualIpv4.orEmpty(), errors["virtualIpv4"]) { v -> update { it.copy(virtualIpv4 = v.ifBlank { null }) } }
                }
                FormField("Static IPv6 CIDR", profile.virtualIpv6.orEmpty(), errors["virtualIpv6"]) { v -> update { it.copy(virtualIpv6 = v.ifBlank { null }) } }
            }
        }

        item {
            SectionCard(title = "Network & Peers", icon = Icons.Default.Router) {
                PeerListField(profile.peers, errors["peers"]) { v -> update { it.copy(peers = v) } }
                ListField("Listeners", profile.listeners, errors["listeners"]) { v -> update { it.copy(listeners = v) } }
                ListField("Mapped listeners", profile.mappedListeners, errors["mappedListeners"]) { v -> update { it.copy(mappedListeners = v) } }
            }
        }

        item {
            SectionCard(title = "Routing", icon = Icons.Default.Tune) {
                SwitchRow("Magic DNS", profile.enableMagicDns) { checked -> update { it.copy(enableMagicDns = checked) } }
                FormField("MTU", profile.mtu.toString(), errors["mtu"]) { v -> update { it.copy(mtu = v.toIntOrNull() ?: it.mtu) } }
                FormField("TLD DNS zone", profile.tldDnsZone, errors["tldDnsZone"]) { v -> update { it.copy(tldDnsZone = v) } }
                ProxyNetworkListField(profile.proxyNetworks, errors["proxyNetworks"]) { v -> update { it.copy(proxyNetworks = v) } }
                ListField("Manual routes", profile.manualRoutes, errors["manualRoutes"]) { v -> update { it.copy(manualRoutes = v) } }
                SwitchRow("Enable exit node", profile.enableExitNode) { checked -> update { it.copy(enableExitNode = checked) } }
                ListField("Exit nodes", profile.exitNodes, errors["exitNodes"]) { v -> update { it.copy(exitNodes = v) } }
            }
        }

        item {
            SectionCard(title = "IPv6 Public Address", icon = Icons.Default.Router) {
                SwitchRow("Provider", profile.ipv6PublicAddrProvider) { checked -> update { it.copy(ipv6PublicAddrProvider = checked) } }
                SwitchRow("Auto", profile.ipv6PublicAddrAuto) { checked -> update { it.copy(ipv6PublicAddrAuto = checked) } }
                FormField("IPv6 public prefix (CIDR)", profile.ipv6PublicAddrPrefix.orEmpty(), errors["ipv6PublicAddrPrefix"]) { v -> update { it.copy(ipv6PublicAddrPrefix = v.ifBlank { null }) } }
            }
        }

        item {
            SectionCard(title = "Port Forwards", icon = Icons.Default.Tune) {
                PortForwardListField(profile.portForwards, errors["portForwards"]) { v -> update { it.copy(portForwards = v) } }
            }
        }

        item {
            SectionCard(title = "VPN Portal (WireGuard)", icon = Icons.Default.VpnKey) {
                val portal = profile.vpnPortal
                SwitchRow("Enable WireGuard portal", portal != null) { checked ->
                    update { it.copy(vpnPortal = if (checked) VpnPortal("", "") else null) }
                }
                if (portal != null) {
                    FormField("Client CIDR", portal.clientCidr, errors["vpnPortal"]) { v -> update { it.copy(vpnPortal = portal.copy(clientCidr = v)) } }
                    FormField("WireGuard listen", portal.wireguardListen, errors["vpnPortal"]) { v -> update { it.copy(vpnPortal = portal.copy(wireguardListen = v)) } }
                }
            }
        }

        item {
            SectionCard(title = "Secure Mode", icon = Icons.Default.VpnKey) {
                SwitchRow("Enable secure mode", profile.secureMode.enabled) { checked -> update { it.copy(secureMode = it.secureMode.copy(enabled = checked)) } }
                if (profile.secureMode.enabled) {
                    FormField("Local private key", profile.secureMode.localPrivateKey.orEmpty(), null) { v -> update { it.copy(secureMode = it.secureMode.copy(localPrivateKey = v.ifBlank { null })) } }
                    FormField("Local public key", profile.secureMode.localPublicKey.orEmpty(), null) { v -> update { it.copy(secureMode = it.secureMode.copy(localPublicKey = v.ifBlank { null })) } }
                }
            }
        }

        item {
            SectionCard(title = "STUN & Whitelists", icon = Icons.Default.Router) {
                ListField("STUN servers", profile.stunServers, errors["stunServers"]) { v -> update { it.copy(stunServers = v) } }
                ListField("STUN servers (IPv6)", profile.stunServersV6, errors["stunServersV6"]) { v -> update { it.copy(stunServersV6 = v) } }
                ListField("TCP whitelist", profile.tcpWhitelist, errors["tcpWhitelist"]) { v -> update { it.copy(tcpWhitelist = v) } }
                ListField("UDP whitelist", profile.udpWhitelist, errors["udpWhitelist"]) { v -> update { it.copy(udpWhitelist = v) } }
                FormField("Relay network whitelist", profile.relayNetworkWhitelist, null) { v -> update { it.copy(relayNetworkWhitelist = v) } }
            }
        }

        item {
            SectionCard(title = "Flags — General", icon = Icons.Default.Tune) {
                ChoiceRow("Default protocol", profile.defaultProtocol, listOf("tcp", "udp", "wss")) { v -> update { it.copy(defaultProtocol = v) } }
                SwitchRow("Enable encryption", profile.enableEncryption) { c -> update { it.copy(enableEncryption = c) } }
                ChoiceRow("Encryption algorithm", profile.encryptionAlgorithm.name, EncryptionAlgorithm.entries.map { it.name }) { v ->
                    update { it.copy(encryptionAlgorithm = EncryptionAlgorithm.valueOf(v)) }
                }
                ChoiceRow("Data compression", profile.dataCompressAlgo.name, CompressionAlgo.entries.map { it.name }) { v ->
                    update { it.copy(dataCompressAlgo = CompressionAlgo.valueOf(v)) }
                }
                SwitchRow("Enable IPv6", profile.enableIpv6) { c -> update { it.copy(enableIpv6 = c) } }
                SwitchRow("Latency first", profile.latencyFirst) { c -> update { it.copy(latencyFirst = c) } }
                SwitchRow("Multi-thread", profile.multiThread) { c -> update { it.copy(multiThread = c) } }
                FormField("Multi-thread count", profile.multiThreadCount.toString(), errors["multiThreadCount"]) { v -> update { it.copy(multiThreadCount = v.toIntOrNull() ?: it.multiThreadCount) } }
                SwitchRow("Bind device", profile.bindDevice) { c -> update { it.copy(bindDevice = c) } }
                SwitchRow("Private mode", profile.privateMode) { c -> update { it.copy(privateMode = c) } }
                SwitchRow("Proxy forward by system", profile.proxyForwardBySystem) { c -> update { it.copy(proxyForwardBySystem = c) } }
                SwitchRow("Disable relay data", profile.disableRelayData) { c -> update { it.copy(disableRelayData = c) } }
                SwitchRow("Enable UDP broadcast relay", profile.enableUdpBroadcastRelay) { c -> update { it.copy(enableUdpBroadcastRelay = c) } }
                FormField("Foreign relay bps limit", profile.foreignRelayBpsLimit.toString(), errors["foreignRelayBpsLimit"]) { v -> update { it.copy(foreignRelayBpsLimit = v.toLongOrNull() ?: it.foreignRelayBpsLimit) } }
                FormField("Instance recv bps limit", profile.instanceRecvBpsLimit.toString(), errors["instanceRecvBpsLimit"]) { v -> update { it.copy(instanceRecvBpsLimit = v.toLongOrNull() ?: it.instanceRecvBpsLimit) } }
            }
        }

        item {
            SectionCard(title = "Flags — P2P", icon = Icons.Default.Tune) {
                SwitchRow("Disable P2P", profile.disableP2p) { c -> update { it.copy(disableP2p = c) } }
                SwitchRow("P2P only", profile.p2pOnly) { c -> update { it.copy(p2pOnly = c) } }
                SwitchRow("Lazy P2P", profile.lazyP2p) { c -> update { it.copy(lazyP2p = c) } }
                SwitchRow("Relay all peer RPC", profile.relayAllPeerRpc) { c -> update { it.copy(relayAllPeerRpc = c) } }
                SwitchRow("Disable TCP hole punching", profile.disableTcpHolePunching) { c -> update { it.copy(disableTcpHolePunching = c) } }
                SwitchRow("Disable UDP hole punching", profile.disableUdpHolePunching) { c -> update { it.copy(disableUdpHolePunching = c) } }
                SwitchRow("Disable symmetric hole punching", profile.disableSymHolePunching) { c -> update { it.copy(disableSymHolePunching = c) } }
                SwitchRow("Disable UPnP", profile.disableUpnp) { c -> update { it.copy(disableUpnp = c) } }
            }
        }

        item {
            SectionCard(title = "Flags — KCP Proxy", icon = Icons.Default.Tune) {
                SwitchRow("Enable KCP proxy", profile.enableKcpProxy) { c -> update { it.copy(enableKcpProxy = c) } }
                SwitchRow("Disable KCP input", profile.disableKcpInput) { c -> update { it.copy(disableKcpInput = c) } }
                SwitchRow("Disable relay KCP", profile.disableRelayKcp) { c -> update { it.copy(disableRelayKcp = c) } }
                SwitchRow("Enable relay foreign network KCP", profile.enableRelayForeignNetworkKcp) { c -> update { it.copy(enableRelayForeignNetworkKcp = c) } }
            }
        }

        item {
            SectionCard(title = "Flags — QUIC Proxy", icon = Icons.Default.Tune) {
                SwitchRow("Enable QUIC proxy", profile.enableQuicProxy) { c -> update { it.copy(enableQuicProxy = c) } }
                SwitchRow("Disable QUIC input", profile.disableQuicInput) { c -> update { it.copy(disableQuicInput = c) } }
                SwitchRow("Disable relay QUIC", profile.disableRelayQuic) { c -> update { it.copy(disableRelayQuic = c) } }
                SwitchRow("Enable relay foreign network QUIC", profile.enableRelayForeignNetworkQuic) { c -> update { it.copy(enableRelayForeignNetworkQuic = c) } }
            }
        }

        item {
            errors["form"]?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            content()
        }
    }
}

@Composable
private fun FormField(
    label: String,
    value: String,
    error: String?,
    enabled: Boolean = true,
    transformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        label = { Text(label) },
        supportingText = if (error == null) null else { { Text(error) } },
        isError = error != null,
        visualTransformation = transformation,
        trailingIcon = trailingIcon,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onChange, enabled = enabled)
    }
}

@Composable
private fun ChoiceRow(label: String, value: String, options: List<String>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onChange(option); expanded = false })
            }
        }
    }
}

@Composable
private fun ListField(
    label: String,
    items: List<String>,
    error: String?,
    enabled: Boolean = true,
    onChange: (List<String>) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val fieldValue = when {
        items.isEmpty() -> ""
        items.size == 1 -> items.first()
        else -> "${items.size} entries"
    }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = fieldValue,
            onValueChange = {},
            enabled = enabled,
            readOnly = true,
            label = { Text(label) },
            supportingText = if (error == null) null else { { Text(error) } },
            isError = error != null,
            trailingIcon = {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Edit $label")
            },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(enabled = enabled) { open = true },
        )
    }
    if (open) {
        ListEditorDialog(
            title = label,
            items = items,
            onDismiss = { open = false },
            onChange = onChange,
        )
    }
}

@Composable
private fun ListEditorDialog(
    title: String,
    items: List<String>,
    onDismiss: () -> Unit,
    onChange: (List<String>) -> Unit,
) {
    var draft by remember(items) { mutableStateOf(items) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var editingValue by remember { mutableStateOf("") }

    fun commit() {
        val value = editingValue.trim()
        if (value.isEmpty()) { editingIndex = null; return }
        draft = when (editingIndex) {
            null -> draft + value
            else -> draft.mapIndexed { i, item -> if (i == editingIndex) value else item }
        }
        editingIndex = null
        editingValue = ""
    }

    AlertDialog(
        onDismissRequest = {
            if (draft != items) onChange(draft)
            onDismiss()
        },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (editingIndex != null) {
                    Text("Edit entry", style = MaterialTheme.typography.labelLarge)
                } else {
                    Text("Add new entry", style = MaterialTheme.typography.labelLarge)
                }
                OutlinedTextField(
                    value = editingValue,
                    onValueChange = { editingValue = it },
                    label = { Text(if (editingIndex != null) "Entry value" else "New entry") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (editingValue.isNotEmpty()) {
                            IconButton(onClick = { editingValue = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
                            }
                        }
                    },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (editingIndex != null) {
                        TextButton(onClick = { editingIndex = null; editingValue = "" }) { Text("Cancel") }
                    }
                    Button(
                        onClick = { commit() },
                        enabled = editingValue.trim().isNotEmpty(),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Icon(
                            if (editingIndex == null) Icons.Default.Add else Icons.Default.Check,
                            contentDescription = null,
                            Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(if (editingIndex == null) "Add" else "Update")
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                if (draft.isEmpty()) {
                    Text(
                        text = "No entries yet. Add one above.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        draft.forEachIndexed { index, item ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = item,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { editingIndex = index; editingValue = item }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit entry")
                                }
                                IconButton(onClick = { draft = draft.filterIndexed { i, _ -> i != index } }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete entry")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (draft != items) onChange(draft)
                    onDismiss()
                },
                shape = MaterialTheme.shapes.medium,
            ) { Text("Done") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun PeerListField(peers: List<Peer>, error: String?, onChange: (List<Peer>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val fieldValue = when {
        peers.isEmpty() -> ""
        peers.size == 1 -> peers.first().uri
        else -> "${peers.size} peers"
    }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = fieldValue,
            onValueChange = {},
            readOnly = true,
            label = { Text("Peers") },
            supportingText = if (error == null) null else { { Text(error) } },
            isError = error != null,
            trailingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Edit peers") },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(modifier = Modifier.matchParentSize().clickable { open = true })
    }
    if (open) PeerListEditorDialog(peers, { open = false }, onChange)
}

@Composable
private fun PeerListEditorDialog(peers: List<Peer>, onDismiss: () -> Unit, onChange: (List<Peer>) -> Unit) {
    var draft by remember(peers) { mutableStateOf(peers) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var uri by remember { mutableStateOf("") }
    var publicKey by remember { mutableStateOf("") }

    fun reset() { editingIndex = null; uri = ""; publicKey = "" }
    fun commit() {
        val trimmedUri = uri.trim()
        if (trimmedUri.isEmpty()) { reset(); return }
        val peer = Peer(uri = trimmedUri, peerPublicKey = publicKey.trim().ifBlank { null })
        draft = when (editingIndex) {
            null -> draft + peer
            else -> draft.mapIndexed { i, p -> if (i == editingIndex) peer else p }
        }
        reset()
    }

    AlertDialog(
        onDismissRequest = { if (draft != peers) onChange(draft); onDismiss() },
        title = { Text("Peers") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (editingIndex != null) "Edit peer" else "Add peer", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = uri, onValueChange = { uri = it },
                    label = { Text("Peer URI") }, singleLine = true,
                    shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = publicKey, onValueChange = { publicKey = it },
                    label = { Text("Peer public key (optional)") }, singleLine = true,
                    shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (editingIndex != null) TextButton(onClick = { reset() }) { Text("Cancel") }
                    Button(onClick = { commit() }, enabled = uri.trim().isNotEmpty(), shape = MaterialTheme.shapes.medium) {
                        Icon(if (editingIndex == null) Icons.Default.Add else Icons.Default.Check, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(if (editingIndex == null) "Add" else "Update")
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (draft.isEmpty()) {
                    Text("No peers yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        draft.forEachIndexed { index, peer ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(peer.uri, style = MaterialTheme.typography.bodyMedium)
                                    peer.peerPublicKey?.takeIf { it.isNotBlank() }?.let {
                                        Text("key: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                IconButton(onClick = { editingIndex = index; uri = peer.uri; publicKey = peer.peerPublicKey.orEmpty() }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit peer")
                                }
                                IconButton(onClick = { draft = draft.filterIndexed { i, _ -> i != index } }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete peer")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { if (draft != peers) onChange(draft); onDismiss() }, shape = MaterialTheme.shapes.medium) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ProxyNetworkListField(networks: List<ProxyNetwork>, error: String?, onChange: (List<ProxyNetwork>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val fieldValue = when {
        networks.isEmpty() -> ""
        networks.size == 1 -> networks.first().cidr
        else -> "${networks.size} networks"
    }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = fieldValue,
            onValueChange = {},
            readOnly = true,
            label = { Text("Proxy networks") },
            supportingText = if (error == null) null else { { Text(error) } },
            isError = error != null,
            trailingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Edit proxy networks") },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(modifier = Modifier.matchParentSize().clickable { open = true })
    }
    if (open) ProxyNetworkEditorDialog(networks, { open = false }, onChange)
}

@Composable
private fun ProxyNetworkEditorDialog(networks: List<ProxyNetwork>, onDismiss: () -> Unit, onChange: (List<ProxyNetwork>) -> Unit) {
    var draft by remember(networks) { mutableStateOf(networks) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var cidr by remember { mutableStateOf("") }
    var mappedCidr by remember { mutableStateOf("") }
    var allow by remember { mutableStateOf("") }

    fun reset() { editingIndex = null; cidr = ""; mappedCidr = ""; allow = "" }
    fun commit() {
        val trimmedCidr = cidr.trim()
        if (trimmedCidr.isEmpty()) { reset(); return }
        val network = ProxyNetwork(
            cidr = trimmedCidr,
            mappedCidr = mappedCidr.trim().ifBlank { null },
            allow = allow.split(",", " ").map(String::trim).filter(String::isNotEmpty),
        )
        draft = when (editingIndex) {
            null -> draft + network
            else -> draft.mapIndexed { i, n -> if (i == editingIndex) network else n }
        }
        reset()
    }

    AlertDialog(
        onDismissRequest = { if (draft != networks) onChange(draft); onDismiss() },
        title = { Text("Proxy networks") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (editingIndex != null) "Edit network" else "Add network", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(value = cidr, onValueChange = { cidr = it }, label = { Text("CIDR") }, singleLine = true, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = mappedCidr, onValueChange = { mappedCidr = it }, label = { Text("Mapped CIDR (optional)") }, singleLine = true, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = allow, onValueChange = { allow = it }, label = { Text("Allow (tcp,udp,icmp — comma separated)") }, singleLine = true, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (editingIndex != null) TextButton(onClick = { reset() }) { Text("Cancel") }
                    Button(onClick = { commit() }, enabled = cidr.trim().isNotEmpty(), shape = MaterialTheme.shapes.medium) {
                        Icon(if (editingIndex == null) Icons.Default.Add else Icons.Default.Check, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(if (editingIndex == null) "Add" else "Update")
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (draft.isEmpty()) {
                    Text("No proxy networks yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        draft.forEachIndexed { index, network ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(network.cidr, style = MaterialTheme.typography.bodyMedium)
                                    val extra = listOfNotNull(
                                        network.mappedCidr?.takeIf { it.isNotBlank() }?.let { "mapped: $it" },
                                        network.allow.takeIf { it.isNotEmpty() }?.joinToString(",")?.let { "allow: $it" },
                                    ).joinToString(" • ")
                                    if (extra.isNotEmpty()) Text(extra, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { editingIndex = index; cidr = network.cidr; mappedCidr = network.mappedCidr.orEmpty(); allow = network.allow.joinToString(",") }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit network")
                                }
                                IconButton(onClick = { draft = draft.filterIndexed { i, _ -> i != index } }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete network")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { if (draft != networks) onChange(draft); onDismiss() }, shape = MaterialTheme.shapes.medium) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PortForwardListField(forwards: List<PortForward>, error: String?, onChange: (List<PortForward>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val fieldValue = when {
        forwards.isEmpty() -> ""
        forwards.size == 1 -> forwards.first().bindAddr
        else -> "${forwards.size} forwards"
    }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = fieldValue,
            onValueChange = {},
            readOnly = true,
            label = { Text("Port forwards") },
            supportingText = if (error == null) null else { { Text(error) } },
            isError = error != null,
            trailingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Edit port forwards") },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(modifier = Modifier.matchParentSize().clickable { open = true })
    }
    if (open) PortForwardEditorDialog(forwards, { open = false }, onChange)
}

@Composable
private fun PortForwardEditorDialog(forwards: List<PortForward>, onDismiss: () -> Unit, onChange: (List<PortForward>) -> Unit) {
    var draft by remember(forwards) { mutableStateOf(forwards) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var bindAddr by remember { mutableStateOf("") }
    var dstAddr by remember { mutableStateOf("") }
    var proto by remember { mutableStateOf("tcp") }

    fun reset() { editingIndex = null; bindAddr = ""; dstAddr = ""; proto = "tcp" }
    fun commit() {
        if (bindAddr.isBlank() || dstAddr.isBlank()) { reset(); return }
        val forward = PortForward(bindAddr = bindAddr.trim(), dstAddr = dstAddr.trim(), proto = proto.trim().lowercase())
        draft = when (editingIndex) {
            null -> draft + forward
            else -> draft.mapIndexed { i, f -> if (i == editingIndex) forward else f }
        }
        reset()
    }

    AlertDialog(
        onDismissRequest = { if (draft != forwards) onChange(draft); onDismiss() },
        title = { Text("Port forwards") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (editingIndex != null) "Edit forward" else "Add forward", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(value = bindAddr, onValueChange = { bindAddr = it }, label = { Text("Bind address (host:port)") }, singleLine = true, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = dstAddr, onValueChange = { dstAddr = it }, label = { Text("Destination address (host:port)") }, singleLine = true, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
                ChoiceRow("Protocol", proto, listOf("tcp", "udp")) { proto = it }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (editingIndex != null) TextButton(onClick = { reset() }) { Text("Cancel") }
                    Button(onClick = { commit() }, enabled = bindAddr.isNotBlank() && dstAddr.isNotBlank(), shape = MaterialTheme.shapes.medium) {
                        Icon(if (editingIndex == null) Icons.Default.Add else Icons.Default.Check, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(if (editingIndex == null) "Add" else "Update")
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (draft.isEmpty()) {
                    Text("No port forwards yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        draft.forEachIndexed { index, forward ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${forward.bindAddr} → ${forward.dstAddr} (${forward.proto})", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                IconButton(onClick = { editingIndex = index; bindAddr = forward.bindAddr; dstAddr = forward.dstAddr; proto = forward.proto }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit forward")
                                }
                                IconButton(onClick = { draft = draft.filterIndexed { i, _ -> i != index } }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete forward")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { if (draft != forwards) onChange(draft); onDismiss() }, shape = MaterialTheme.shapes.medium) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SettingsScreen(
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

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
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
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(if (subtitle != null) 2.dp else 0.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

private fun notificationsPermissionGranted(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
