@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cc.ptoe.easytier.compose.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import cc.ptoe.easytier.compose.R
import cc.ptoe.easytier.compose.data.RuntimeState
import cc.ptoe.easytier.compose.ui.screens.DashboardScreen
import cc.ptoe.easytier.compose.ui.screens.GlobalSettingsScreen
import cc.ptoe.easytier.compose.ui.screens.PeersScreen
import cc.ptoe.easytier.compose.ui.screens.ProfileEditorScreen
import cc.ptoe.easytier.compose.ui.screens.ProfilesScreen
import cc.ptoe.easytier.compose.ui.screens.SettingsScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

private enum class Destination(@param:StringRes val labelRes: Int, val icon: ImageVector) {
    Dashboard(R.string.nav_dashboard, Icons.Default.Dashboard),
    Profiles(R.string.nav_profiles, Icons.Default.Layers),
    Peers(R.string.nav_peers, Icons.Default.People),
    Settings(R.string.nav_settings, Icons.Default.Settings),
    Editor(R.string.nav_profile, Icons.Default.Edit),
    GlobalSettings(R.string.settings_global, Icons.Default.Tune),
}

@Composable
fun EasyTierApp(
    viewModel: EasyTierViewModel,
) {
    val state by viewModel.state.collectAsState()
    var destination by remember { mutableStateOf(Destination.Dashboard) }
    val wide = LocalConfiguration.current.screenWidthDp >= 840
    val draftRunning = state.draft?.let { draft ->
        state.runtime.profileId == draft.id && state.runtime.state in setOf(RuntimeState.STARTING, RuntimeState.RUNNING)
    } ?: false

    LaunchedEffect(state.draft) {
        if (state.draft == null && destination == Destination.Editor) destination = Destination.Profiles
    }

    val backProgress = remember { Animatable(0f) }
    val editorAnim = remember { Animatable(0f) }
    var editorShown by remember { mutableStateOf(false) }
    var gestureCommitted by remember { mutableStateOf(false) }
    var lastDraft by remember { mutableStateOf(state.draft) }
    val currentDraft = state.draft
    if (currentDraft != null) lastDraft = currentDraft

    // Global settings cover: shares the editor's enter/exit slide + predictive
    // back gesture pattern, so returning to Settings feels like the editor.
    val globalsBackProgress = remember { Animatable(0f) }
    val globalsAnim = remember { Animatable(0f) }
    var globalsShown by remember { mutableStateOf(false) }
    var globalsGestureCommitted by remember { mutableStateOf(false) }

    // Drive editor enter/exit slide animation and keep gesture progress in sync.
    LaunchedEffect(destination) {
        if (destination == Destination.Editor) {
            backProgress.snapTo(0f)
            gestureCommitted = false
            // Reset to the resting position before sliding in, so the full
            // enter animation always plays even if a previous exit was cut short.
            editorAnim.snapTo(0f)
            editorShown = true
            editorAnim.animateTo(1f, tween(durationMillis = 300))
        } else {
            if (editorShown) {
                if (gestureCommitted) {
                    // Predictive back committed: keep backProgress at the gesture
                    // position so the editor continues sliding out from where the
                    // gesture left it, then reset after the animation completes.
                    editorAnim.animateTo(0f, tween(durationMillis = 250))
                    backProgress.snapTo(0f)
                } else {
                    // Normal exit (back button / save): slide out from rest.
                    backProgress.snapTo(0f)
                    editorAnim.animateTo(0f, tween(durationMillis = 300))
                }
                editorShown = false
                gestureCommitted = false
            } else {
                backProgress.snapTo(0f)
            }
        }
    }

    // Mirror the editor animation lifecycle for the global settings cover.
    LaunchedEffect(destination) {
        if (destination == Destination.GlobalSettings) {
            globalsBackProgress.snapTo(0f)
            globalsGestureCommitted = false
            globalsShown = true
            globalsAnim.animateTo(1f, tween(durationMillis = 300))
        } else {
            if (globalsShown) {
                if (globalsGestureCommitted) {
                    globalsAnim.animateTo(0f, tween(durationMillis = 250))
                    globalsBackProgress.snapTo(0f)
                } else {
                    globalsBackProgress.snapTo(0f)
                    globalsAnim.animateTo(0f, tween(durationMillis = 300))
                }
                globalsShown = false
                globalsGestureCommitted = false
            } else {
                globalsBackProgress.snapTo(0f)
            }
        }
    }

    PredictiveBackHandler(enabled = destination == Destination.Editor) { progress: Flow<BackEventCompat> ->
        try {
            progress.collect { event -> backProgress.snapTo(event.progress) }
            // Gesture committed: discard draft and leave editor.
            gestureCommitted = true
            viewModel.discardDraft()
            destination = Destination.Profiles
        } catch (e: CancellationException) {
            // Gesture cancelled: animate back to rest.
            backProgress.animateTo(0f, animationSpec = tween(durationMillis = 250))
        }
    }

    PredictiveBackHandler(enabled = destination == Destination.GlobalSettings) { progress: Flow<BackEventCompat> ->
        try {
            progress.collect { event -> globalsBackProgress.snapTo(event.progress) }
            // Gesture committed: leave global settings back to Settings.
            globalsGestureCommitted = true
            destination = Destination.Settings
        } catch (e: CancellationException) {
            // Gesture cancelled: animate back to rest.
            globalsBackProgress.animateTo(0f, animationSpec = tween(durationMillis = 250))
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Box {
                val tabDestination = when (destination) {
                    Destination.Editor -> Destination.Profiles
                    Destination.GlobalSettings -> Destination.Settings
                    else -> destination
                }
                TopAppBar(title = { Text(stringResource(tabDestination.labelRes)) })
                if (editorShown) {
                    TopAppBar(
                        modifier = Modifier.graphicsLayer {
                            val p = editorAnim.value
                            val bp = backProgress.value
                            val scale = 1f - bp * 0.1f
                            scaleX = scale
                            scaleY = scale
                            alpha = p * (1f - bp * 0.3f)
                            if (wide) {
                                translationY = (1f - p) * -size.height
                                translationX = bp * size.width * 0.35f
                            } else {
                                translationX = (1f - p) * size.width + bp * size.width * 0.35f
                            }
                        },
                        title = { Text(stringResource(Destination.Editor.labelRes)) },
                        navigationIcon = {
                            IconButton(onClick = {
                                viewModel.discardDraft()
                                destination = Destination.Profiles
                            }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                            }
                        },
                        actions = {
                            val saveDescription = stringResource(R.string.content_save_profile)
                            IconButton(
                                onClick = viewModel::saveDraft,
                                enabled = !draftRunning,
                                modifier = Modifier.semantics { contentDescription = saveDescription },
                            ) {
                                Icon(Icons.Default.Check, contentDescription = stringResource(R.string.action_save))
                            }
                        },
                    )
                }
                if (globalsShown) {
                    TopAppBar(
                        modifier = Modifier.graphicsLayer {
                            val p = globalsAnim.value
                            val bp = globalsBackProgress.value
                            val scale = 1f - bp * 0.1f
                            scaleX = scale
                            scaleY = scale
                            alpha = p * (1f - bp * 0.3f)
                            if (wide) {
                                translationY = (1f - p) * -size.height
                                translationX = bp * size.width * 0.35f
                            } else {
                                translationX = (1f - p) * size.width + bp * size.width * 0.35f
                            }
                        },
                        title = { Text(stringResource(Destination.GlobalSettings.labelRes)) },
                        navigationIcon = {
                            IconButton(onClick = { destination = Destination.Settings }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                            }
                        },
                    )
                }
            }
        },
        bottomBar = {
            // Fade/slide the navigation bar in and out instead of toggling it
            // instantly, so entering the editor does not make it "flash away".
            AnimatedVisibility(
                visible = !wide && destination != Destination.Editor && destination != Destination.GlobalSettings,
                enter = fadeIn(tween(150)) + slideInVertically(tween(200)) { it },
                exit = fadeOut(tween(150)) + slideOutVertically(tween(200)) { it },
            ) {
                AppNavigationBar(destination) { destination = it }
            }
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (wide && destination != Destination.Editor && destination != Destination.GlobalSettings) {
                AppNavigationRail(destination) { destination = it }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            Box(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp).widthIn(max = 900.dp)) {
                val tabDestination = when (destination) {
                    Destination.Editor -> Destination.Profiles
                    Destination.GlobalSettings -> Destination.Settings
                    else -> destination
                }
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
                    label = stringResource(R.string.transition_tab),
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
                            importProfile = { imported -> viewModel.beginEdit(imported); destination = Destination.Editor },
                            edit = { viewModel.beginEdit(it); destination = Destination.Editor },
                            select = { viewModel.selectProfile(it.id) },
                            delete = viewModel::delete,
                        )
                        Destination.Peers -> PeersScreen(state = state)
                        Destination.Settings -> SettingsScreen(
                            state = state,
                            onTunMode = viewModel::updateTunMode,
                            onStartOnBoot = viewModel::updateStartOnBoot,
                            onOpenGlobalSettings = { destination = Destination.GlobalSettings },
                            reset = viewModel::resetProfiles,
                        )
                        Destination.Editor -> Unit
                        Destination.GlobalSettings -> Unit
                    }
                }

                // Editor overlay: slides in from the right, participates in predictive back.
                if (editorShown) {
                    val draft = currentDraft ?: lastDraft
                    if (draft != null) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface)
                                .graphicsLayer {
                                    val p = editorAnim.value
                                    val bp = backProgress.value
                                    val scale = 1f - bp * 0.1f
                                    scaleX = scale
                                    scaleY = scale
                                    alpha = p * (1f - bp * 0.3f)
                                    if (wide) {
                                        // Large screen: enter from top, predictive back slides horizontally.
                                        translationY = (1f - p) * -size.height
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

                // Global settings cover: same entry/exit + predictive-back slide as
                // the editor, rendered on top of the Settings tab.
                if (globalsShown) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface)
                            .graphicsLayer {
                                val p = globalsAnim.value
                                val bp = globalsBackProgress.value
                                val scale = 1f - bp * 0.1f
                                scaleX = scale
                                scaleY = scale
                                alpha = p * (1f - bp * 0.3f)
                                if (wide) {
                                    translationY = (1f - p) * -size.height
                                    translationX = bp * size.width * 0.35f
                                } else {
                                    translationX = (1f - p) * size.width + bp * size.width * 0.35f
                                }
                            }
                    ) {
                        GlobalSettingsScreen(
                            state = state,
                            onGlobalSettings = viewModel::updateGlobalSettings,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppNavigationBar(selected: Destination, select: (Destination) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Destination.entries
            .filter { it != Destination.Editor && it != Destination.GlobalSettings }
            .forEach { item ->
                val label = stringResource(item.labelRes)
                NavigationBarItem(
                    selected = selected == item,
                    onClick = { select(item) },
                    icon = { Icon(item.icon, contentDescription = label) },
                    label = { Text(label) },
                )
            }
    }
}

@Composable
private fun AppNavigationRail(selected: Destination, select: (Destination) -> Unit) {
    NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Spacer(Modifier.height(16.dp))
        Destination.entries
            .filter { it != Destination.Editor && it != Destination.GlobalSettings }
            .forEach { item ->
                val label = stringResource(item.labelRes)
                NavigationRailItem(
                    selected = selected == item,
                    onClick = { select(item) },
                    icon = { Icon(item.icon, contentDescription = label) },
                    label = { Text(label) },
                )
            }
    }
}
