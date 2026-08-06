@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cc.ptoe.easytier.compose.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.ptoe.easytier.compose.data.RuntimeState
import cc.ptoe.easytier.compose.transport.RuntimeEffect
import cc.ptoe.easytier.compose.ui.screens.DashboardScreen
import cc.ptoe.easytier.compose.ui.screens.PeersScreen
import cc.ptoe.easytier.compose.ui.screens.ProfileEditorScreen
import cc.ptoe.easytier.compose.ui.screens.ProfilesScreen
import cc.ptoe.easytier.compose.ui.screens.SettingsScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

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

    val backProgress = remember { Animatable(0f) }
    val editorAnim = remember { Animatable(0f) }
    var editorShown by remember { mutableStateOf(false) }
    var skipExitAnimation by remember { mutableStateOf(false) }
    var lastDraft by remember { mutableStateOf(state.draft) }
    val currentDraft = state.draft
    if (currentDraft != null) lastDraft = currentDraft

    // Drive editor enter/exit slide animation and keep gesture progress in sync.
    LaunchedEffect(destination) {
        if (destination == Destination.Editor) {
            backProgress.snapTo(0f)
            skipExitAnimation = false
            editorShown = true
            editorAnim.animateTo(1f, tween(durationMillis = 300))
        } else {
            backProgress.snapTo(0f)
            if (editorShown) {
                if (skipExitAnimation) {
                    editorAnim.snapTo(0f)
                } else {
                    editorAnim.animateTo(0f, tween(durationMillis = 300))
                }
                editorShown = false
                skipExitAnimation = false
            }
        }
    }

    PredictiveBackHandler(enabled = destination == Destination.Editor) { progress: Flow<BackEventCompat> ->
        try {
            progress.collect { event -> backProgress.snapTo(event.progress) }
            // Gesture committed: discard draft and leave editor.
            skipExitAnimation = true
            viewModel.discardDraft()
            destination = Destination.Profiles
        } catch (e: CancellationException) {
            // Gesture cancelled: animate back to rest.
            backProgress.animateTo(0f, animationSpec = tween(durationMillis = 250))
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Box {
                val tabDestination = if (destination == Destination.Editor) Destination.Profiles else destination
                TopAppBar(title = { Text(tabDestination.label) })
                if (editorShown) {
                    TopAppBar(
                        modifier = Modifier.graphicsLayer {
                            val p = editorAnim.value
                            val bp = backProgress.value
                            val scale = 1f - bp * 0.1f
                            scaleX = scale
                            scaleY = scale
                            alpha = 1f - bp * 0.3f
                            if (wide) {
                                translationY = (1f - p) * -size.height
                                translationX = bp * size.width * 0.35f
                            } else {
                                translationX = (1f - p) * size.width + bp * size.width * 0.35f
                            }
                        },
                        title = { Text(Destination.Editor.label) },
                        navigationIcon = {
                            IconButton(onClick = {
                                viewModel.discardDraft()
                                destination = Destination.Profiles
                            }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = viewModel::saveDraft,
                                enabled = !draftRunning,
                                modifier = Modifier.semantics { contentDescription = "save_profile" },
                            ) {
                                Icon(Icons.Default.Check, contentDescription = "Save")
                            }
                        },
                    )
                }
            }
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
                val tabDestination = if (destination == Destination.Editor) Destination.Profiles else destination
                val tabOrder = remember {
                    listOf(Destination.Dashboard, Destination.Profiles, Destination.Peers, Destination.Settings)
                }
                AnimatedContent(
                    targetState = tabDestination,
                    transitionSpec = {
                        val fromIndex = tabOrder.indexOf(initialState)
                        val toIndex = tabOrder.indexOf(targetState)
                        val forward = toIndex >= fromIndex
                        val dir = if (forward) 1 else -1
                        val duration = 280
                        if (wide) {
                            slideInVertically(tween(duration)) { it * dir } togetherWith
                                slideOutVertically(tween(duration)) { -it * dir }
                        } else {
                            slideInHorizontally(tween(duration)) { it * dir } togetherWith
                                slideOutHorizontally(tween(duration)) { -it * dir }
                        }
                    },
                    label = "tab",
                ) { tab ->
                    when (tab) {
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
                        Destination.Settings -> SettingsScreen(
                            state = state,
                            onTunMode = viewModel::updateTunMode,
                            onGlobalSettings = viewModel::updateGlobalSettings,
                            reset = viewModel::resetProfiles,
                        )
                        Destination.Editor -> {}
                    }
                }

                // Editor overlay: slides in from the right, participates in predictive back.
                if (editorShown) {
                    val draft = currentDraft ?: lastDraft
                    if (draft != null) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val p = editorAnim.value
                                    val bp = backProgress.value
                                    val scale = 1f - bp * 0.1f
                                    scaleX = scale
                                    scaleY = scale
                                    alpha = p * (1f - bp * 0.3f)
                                    if (wide) {
                                        // Large screen: enter from top, predictive back slides horizontally.
                                        translationY = (1f - p) * size.height
                                        translationX = bp * size.width * 0.35f
                                    } else {
                                        // Small screen: enter from right, predictive back slides horizontally.
                                        translationX = (1f - p) * size.width + bp * size.width * 0.35f
                                    }
                                }
                        ) {
                            ProfileEditorScreen(
                                profile = draft,
                                errors = state.fieldErrors,
                                running = draftRunning,
                                update = viewModel::updateDraft,
                                save = viewModel::saveDraft,
                            )
                        }
                    }
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
