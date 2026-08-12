package cc.ptoe.easytier.compose.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.ptoe.easytier.compose.R
import cc.ptoe.easytier.compose.data.RuntimePeer
import cc.ptoe.easytier.compose.data.RuntimeState
import cc.ptoe.easytier.compose.ui.EasyTierUiState

@Composable
internal fun PeersScreen(state: EasyTierUiState) {
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
                    text = stringResource(if (running) R.string.peers_none_connected else R.string.peers_connect_to_view),
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
                                    text = if (peer.hostname.equals("unknown", ignoreCase = true)) stringResource(R.string.value_unknown) else peer.hostname,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = peer.virtualIpv4 ?: stringResource(R.string.value_unavailable),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = peer.latencyMs?.let { stringResource(R.string.latency_ms, it) }
                                    ?: stringResource(R.string.value_unavailable),
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
                PeerDetailRow(stringResource(R.string.detail_virtual_ip), peer.virtualIpv4)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PeerDetailRow(
                    stringResource(R.string.peer_detail_latency),
                    peer.latencyMs?.let { stringResource(R.string.latency_ms_precise, it) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PeerDetailRow(stringResource(R.string.peer_detail_connection), peerConnectionLabel(peer))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PeerDetailRow(
                    stringResource(R.string.peer_detail_tunnel),
                    peer.tunnelProtos.joinToString(", ").ifEmpty { null },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PeerDetailRow(
                    stringResource(R.string.peer_detail_loss_rate),
                    peer.lossRate?.let { stringResource(R.string.loss_rate, it * 100.0) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PeerDetailRow(stringResource(R.string.detail_nat_type), localizedNatType(peer.natType))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun peerConnectionLabel(peer: RuntimePeer): String = when {
    peer.connectionType.equals("P2P", ignoreCase = true) -> stringResource(R.string.peer_connection_p2p)
    peer.connectionType.startsWith("Relay", ignoreCase = true) -> stringResource(R.string.peer_connection_relay, peer.cost)
    else -> peer.connectionType
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
            text = value?.ifEmpty { null } ?: stringResource(R.string.value_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
