package cc.ptoe.easytier.compose.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import cc.ptoe.easytier.compose.R
import cc.ptoe.easytier.compose.core.TomlConfigBuilder
import cc.ptoe.easytier.compose.core.TomlImportResult
import cc.ptoe.easytier.compose.core.TomlProfileImporter
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.RuntimeState
import cc.ptoe.easytier.compose.ui.EasyTierUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.Charset

@Composable
internal fun ProfilesScreen(
    state: EasyTierUiState,
    add: () -> Unit,
    importProfile: (EasyTierProfile) -> Unit,
    edit: (EasyTierProfile) -> Unit,
    select: (EasyTierProfile) -> Unit,
    delete: (EasyTierProfile) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<EasyTierProfile?>(null) }
    var fabExpanded by remember { mutableStateOf(false) }
    var showLinkDialog by remember { mutableStateOf(false) }
    var linkUrl by remember { mutableStateOf("") }
    var linkLoading by remember { mutableStateOf(false) }
    var linkError by remember { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var pendingExport by remember { mutableStateOf<Pair<EasyTierProfile, String>?>(null) }

    fun handleImportText(text: String) {
        when (val result = TomlProfileImporter.import(text)) {
            is TomlImportResult.Success -> {
                fabExpanded = false
                showLinkDialog = false
                importProfile(result.profile)
            }

            is TomlImportResult.Failure -> importError = result.message
        }
    }

    /**
     * Dismisses the speed-dial menu first and only then runs [action], so the
     * collapse animation is visible before the editor (or document picker /
     * link dialog) appears — otherwise the overlay would cover the menu the
     * instant it is tapped.
     */
    fun selectAddAction(action: () -> Unit) {
        fabExpanded = false
        scope.launch {
            delay(200)
            action()
        }
    }

    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use(::readLimited) }.getOrNull()
            }
            when {
                read == null -> importError = context.getString(R.string.profiles_import_read_failed)
                read.tooLarge -> importError = context.getString(R.string.profiles_import_too_large)
                else -> handleImportText(read.text)
            }
        }
    }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val export = pendingExport ?: return@rememberLauncherForActivityResult
        pendingExport = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(export.second.toByteArray()) } }
            }
        }
    }

    fun fetchFromLink() {
        val input = linkUrl.trim()
        val uri = runCatching { URI.create(input) }.getOrNull()
        if (uri == null || (uri.scheme != "http" && uri.scheme != "https") || uri.host.isNullOrEmpty()) {
            linkError = context.getString(R.string.profiles_import_invalid_url)
            return
        }
        linkLoading = true
        linkError = null
        scope.launch {
            val fetched: Result<ReadResult> = withContext(Dispatchers.IO) {
                runCatching {
                    val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10_000
                        readTimeout = 10_000
                    }
                    try {
                        if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
                        readLimited(connection.inputStream)
                    } finally {
                        connection.disconnect()
                    }
                }
            }
            linkLoading = false
            fetched.fold(
                onSuccess = { read ->
                    if (read.tooLarge) {
                        linkError = context.getString(R.string.profiles_import_too_large)
                    } else {
                        handleImportText(read.text)
                    }
                },
                onFailure = { linkError = it.message ?: context.getString(R.string.profiles_import_read_failed) },
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (state.profiles.isNotEmpty()) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.profiles, key = { it.id }) { profile ->
                    val isSelected = profile.id == state.selectedProfileId
                    val cardDescription = stringResource(R.string.profiles_card_description, profile.name)
                    val editDescription = stringResource(R.string.content_edit_profile)
                    val deleteDescription = stringResource(R.string.content_delete_profile)
                    val exportDescription = stringResource(R.string.content_export_profile)
                    OutlinedCard(
                        onClick = { select(profile) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = cardDescription },
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
                                            label = { Text(stringResource(R.string.profiles_selected), style = MaterialTheme.typography.labelMedium) },
                                            colors = AssistChipDefaults.assistChipColors(
                                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                                labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                            ),
                                        )
                                    }
                                }
                                Text(
                                    text = stringResource(
                                        R.string.profiles_network_summary,
                                        profile.networkName,
                                        pluralStringResource(R.plurals.peer_count, profile.peers.size, profile.peers.size),
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                onClick = {
                                    pendingExport = profile to TomlConfigBuilder.build(profile, state.globalSettings)
                                    createDocument.launch(exportFileName(profile.name))
                                },
                                modifier = Modifier.semantics { contentDescription = exportDescription },
                            ) {
                                Icon(Icons.Default.Share, contentDescription = exportDescription)
                            }
                            IconButton(onClick = { edit(profile) }) {
                                Icon(Icons.Default.Edit, contentDescription = editDescription)
                            }
                            IconButton(
                                enabled = state.runtime.profileId != profile.id || state.runtime.state == RuntimeState.STOPPED,
                                onClick = { pendingDelete = profile },
                                modifier = Modifier.semantics { contentDescription = deleteDescription },
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = deleteDescription)
                            }
                        }
                    }
                }
            }
        }
        val createDescription = stringResource(R.string.content_create_profile)
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(top = 12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AnimatedVisibility(
                visible = fabExpanded,
                enter = fadeIn(tween(150)) + slideInVertically(tween(220)) { it / 2 },
                exit = fadeOut(tween(120)) + slideOutVertically(tween(160)) { it / 2 },
            ) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Farthest action first; the column stacks upward from the FAB.
                    FabMenuAction(stringResource(R.string.profiles_add_import_link), Icons.Default.Language) {
                        selectAddAction { showLinkDialog = true }
                    }
                    FabMenuAction(stringResource(R.string.profiles_add_import_file), Icons.Default.FolderOpen) {
                        selectAddAction { openDocument.launch(arrayOf("*/*")) }
                    }
                    FabMenuAction(stringResource(R.string.profiles_add_manual), Icons.Default.Add) {
                        selectAddAction { add() }
                    }
                }
            }
            FloatingActionButton(
                onClick = { fabExpanded = !fabExpanded },
                modifier = Modifier.semantics { contentDescription = createDescription },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                val rotation by animateFloatAsState(
                    targetValue = if (fabExpanded) 45f else 0f,
                    animationSpec = tween(200),
                    label = "fabMenuRotation",
                )
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.graphicsLayer { rotationZ = rotation },
                )
            }
        }
    }

    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.profiles_delete_title, profile.name)) },
            text = {
                Text(
                    stringResource(
                        if (state.runtime.profileId == profile.id && state.runtime.state != RuntimeState.STOPPED) {
                            R.string.profiles_disconnect_before_delete
                        } else {
                            R.string.profiles_removed_from_device
                        },
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = { delete(profile); pendingDelete = null },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { OutlinedButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (showLinkDialog) {
        LinkImportDialog(
            url = linkUrl,
            onUrlChange = {
                linkUrl = it
                linkError = null
            },
            loading = linkLoading,
            error = linkError,
            onImport = { if (!linkLoading && linkUrl.isNotBlank()) fetchFromLink() },
            onDismiss = { if (!linkLoading) { showLinkDialog = false; linkError = null } },
        )
    }

    importError?.let { message ->
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text(stringResource(R.string.profiles_import_error_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { importError = null }) { Text(stringResource(R.string.action_close)) }
            },
        )
    }
}

@Composable
private fun FabMenuAction(label: String, icon: ImageVector, onSelect: () -> Unit) {
    ExtendedFloatingActionButton(
        onClick = onSelect,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        icon = { Icon(icon, contentDescription = null) },
        text = { Text(label) },
    )
}

@Composable
private fun LinkImportDialog(
    url: String,
    onUrlChange: (String) -> Unit,
    loading: Boolean,
    error: String?,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profiles_add_import_link)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    label = { Text(stringResource(R.string.profiles_import_url_label)) },
                    placeholder = { Text(stringResource(R.string.profiles_import_url_hint)) },
                    singleLine = true,
                    enabled = !loading,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onImport() }),
                )
                if (loading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(R.string.profiles_import_loading),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onImport, enabled = !loading && url.isNotBlank()) {
                Text(stringResource(R.string.profiles_import_fetch))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, enabled = !loading) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

private fun exportFileName(name: String): String =
    "${name.trim().ifBlank { "profile" }.replace(Regex("""[/\\:*?"<>|]"""), "_")}.toml"

private const val MAX_IMPORT_BYTES = 1_048_576

private data class ReadResult(val text: String, val tooLarge: Boolean)

/**
 * Reads at most [MAX_IMPORT_BYTES] bytes, decoding the content by its byte
 * order mark. Config files saved as UTF-16 (e.g. from Windows Notepad) would
 * otherwise decode as UTF-8 with a NUL interleaved per character, which TOML
 * rejects ("Unexpected \u0000").
 */
private fun readLimited(stream: java.io.InputStream): ReadResult {
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (bytes.size() < MAX_IMPORT_BYTES) {
        val read = stream.read(buffer)
        if (read <= 0) break
        val room = MAX_IMPORT_BYTES - bytes.size()
        bytes.write(buffer, 0, minOf(read, room))
    }
    val tooLarge = stream.read() != -1
    return ReadResult(decode(bytes.toByteArray()), tooLarge)
}

private fun decode(content: ByteArray): String {
    val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    val utf16LeBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    val utf16BeBom = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
    val body: ByteArray
    val charset: Charset
    when {
        content.startsWith(utf16LeBom) -> {
            body = content.copyOfRange(2, content.size)
            charset = Charsets.UTF_16LE
        }

        content.startsWith(utf16BeBom) -> {
            body = content.copyOfRange(2, content.size)
            charset = Charsets.UTF_16BE
        }

        content.startsWith(utf8Bom) -> {
            body = content.copyOfRange(3, content.size)
            charset = Charsets.UTF_8
        }

        else -> {
            body = content
            charset = Charsets.UTF_8
        }
    }
    // NUL defense: stray padding, or UTF-16 misread as UTF-8 by a BOM-less path.
    return String(body, charset).replace("\u0000", "")
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    for (i in prefix.indices) {
        if (this[i] != prefix[i]) return false
    }
    return true
}