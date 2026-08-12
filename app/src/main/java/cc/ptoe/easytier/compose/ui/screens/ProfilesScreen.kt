package cc.ptoe.easytier.compose.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.ptoe.easytier.compose.R
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.RuntimeState
import cc.ptoe.easytier.compose.ui.EasyTierUiState

@Composable
internal fun ProfilesScreen(
    state: EasyTierUiState,
    add: () -> Unit,
    edit: (EasyTierProfile) -> Unit,
    select: (EasyTierProfile) -> Unit,
    delete: (EasyTierProfile) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<EasyTierProfile?>(null) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(state.profiles, key = { it.id }) { profile ->
                val isSelected = profile.id == state.selectedProfileId
                val cardDescription = stringResource(R.string.profiles_card_description, profile.name)
                val editDescription = stringResource(R.string.content_edit_profile)
                val deleteDescription = stringResource(R.string.content_delete_profile)
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
        val createDescription = stringResource(R.string.content_create_profile)
        ExtendedFloatingActionButton(
            onClick = add,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .semantics { contentDescription = createDescription },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text(stringResource(R.string.profiles_add)) },
        )
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
}
