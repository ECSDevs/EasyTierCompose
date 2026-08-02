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
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.RuntimeState
import cc.ptoe.easytier.compose.data.RuntimeStatus
import cc.ptoe.easytier.compose.data.TunMode
import cc.ptoe.easytier.compose.transport.RuntimeEffect

private enum class Destination(val label: String, val icon: ImageVector) {
    Dashboard("Dashboard", Icons.Default.Dashboard),
    Profiles("Profiles", Icons.Default.Layers),
    Settings("Settings", Icons.Default.Settings),
    Editor("Profile", Icons.Default.Edit),
}

@Composable
fun EasyTierApp(
    viewModel: EasyTierViewModel,
    requestVpnPermission: (android.content.Intent) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
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
    LaunchedEffect(state.runtime.error) { state.runtime.error?.let { snackbar.showSnackbar(it) } }
    LaunchedEffect(state.draft) {
        if (state.draft == null && destination == Destination.Editor) destination = Destination.Profiles
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbar) },
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
                tunDevice = state.runtime.tunDevice,
                tunMode = profile?.tunMode,
                statusState = state.runtime.state,
                error = state.runtime.error,
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
            // 左：圆形背景图标
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

            // 右：双行文本（主标题 + 副标题）
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
    tunDevice: String?,
    tunMode: TunMode?,
    statusState: RuntimeState,
    error: String?,
) {
    val statusText = statusState.name.lowercase().replaceFirstChar { it.uppercase() } +
        (error?.let { " — $it" } ?: "")
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
            title = "TUN Device",
            value = tunDevice,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        StatusDetailRow(
            icon = Icons.Default.VpnKey,
            title = "TUN Mode",
            value = tunMode?.let { if (it == TunMode.VPN_SERVICE) "VPN Service" else "Root TUN" },
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
                                text = "${profile.networkName} • ${profile.peerUrls.size} peers • ${if (profile.tunMode == TunMode.VPN_SERVICE) "VPN Service" else "Root TUN"}",
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
private fun ProfileEditorScreen(
    profile: EasyTierProfile,
    errors: Map<String, String>,
    running: Boolean,
    update: ((EasyTierProfile) -> EasyTierProfile) -> Unit,
    save: () -> Unit,
) {
    var revealSecret by remember { mutableStateOf(false) }
    val advanced = !profile.advancedToml.isNullOrBlank()

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

        // Section 1: Basic Information
        item {
            SectionCard(title = "General Profile", icon = Icons.Default.Info) {
                FormField("Profile name", profile.name, errors["name"]) { value -> update { it.copy(name = value) } }
                FormField("Network name", profile.networkName, errors["networkName"], !advanced) { value -> update { it.copy(networkName = value) } }
                FormField(
                    label = "Network secret",
                    value = profile.networkSecret,
                    error = null,
                    enabled = !advanced,
                    transformation = if (revealSecret) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { revealSecret = !revealSecret }) {
                            Icon(
                                imageVector = if (revealSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (revealSecret) "Hide secret" else "Reveal secret",
                            )
                        }
                    },
                ) { value -> update { it.copy(networkSecret = value) } }
            }
        }

        // Section 2: Peers & Network
        item {
            SectionCard(title = "Network & Peers", icon = Icons.Default.Router) {
                ListField("Peer URLs", profile.peerUrls, errors["peerUrls"], !advanced) { value -> update { it.copy(peerUrls = value) } }
                ListField("Listeners", profile.listeners, errors["listeners"], !advanced) { value -> update { it.copy(listeners = value) } }
                if (!profile.dhcp) {
                    FormField("Static IPv4 CIDR", profile.virtualIpv4.orEmpty(), errors["virtualIpv4"], !advanced) { value -> update { it.copy(virtualIpv4 = value) } }
                }
                SwitchRow("Use DHCP", profile.dhcp, !advanced) { checked -> update { it.copy(dhcp = checked, virtualIpv4 = if (checked) null else it.virtualIpv4) } }
            }
        }

        // Section 3: Routing & Advanced
        item {
            SectionCard(title = "Routing & Advanced", icon = Icons.Default.Tune) {
                ListField("Proxy CIDRs", profile.proxyCidrs, errors["proxyCidrs"], !advanced) { value -> update { it.copy(proxyCidrs = value) } }
                ListField("Manual routes", profile.manualRoutes, errors["manualRoutes"], !advanced) { value -> update { it.copy(manualRoutes = value) } }
                SwitchRow("Magic DNS", profile.enableMagicDns, !advanced && profile.tunMode == TunMode.VPN_SERVICE) { checked -> update { it.copy(enableMagicDns = checked) } }
                if (profile.tunMode == TunMode.ROOT_TUN) {
                    Text("Magic DNS requires VPN Service mode", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FormField("MTU", profile.mtu.toString(), errors["mtu"], !advanced) { value -> update { it.copy(mtu = value.toIntOrNull() ?: it.mtu) } }
                SwitchRow("Advanced TOML", advanced, profile.tunMode == TunMode.VPN_SERVICE) { checked -> update { it.copy(advancedToml = if (checked) "" else null) } }
                if (profile.tunMode == TunMode.ROOT_TUN) {
                    Text("Advanced TOML is supported only in VPN Service mode", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (advanced) {
                    FormField("Advanced TOML", profile.advancedToml.orEmpty(), errors["advancedToml"]) { value -> update { it.copy(advancedToml = value) } }
                    Text("Advanced TOML replaces the structured configuration form.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
private fun SettingsScreen(
    state: EasyTierUiState,
    onTunMode: (TunMode) -> Unit,
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

    var confirmReset by remember { mutableStateOf(false) }

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
