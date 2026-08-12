package cc.ptoe.easytier.compose.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.ptoe.easytier.compose.R
import cc.ptoe.easytier.compose.data.Peer
import cc.ptoe.easytier.compose.data.PortForward
import cc.ptoe.easytier.compose.data.ProxyNetwork

@Composable
internal fun PeerListField(peers: List<Peer>, error: String?, onChange: (List<Peer>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.list_peers)
    val fieldValue = when {
        peers.isEmpty() -> ""
        peers.size == 1 -> peers.first().uri
        else -> pluralStringResource(R.plurals.peer_list_count, peers.size, peers.size)
    }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = fieldValue,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            supportingText = if (error == null) null else { { Text(error) } },
            isError = error != null,
            trailingIcon = {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.content_edit_field, label),
                )
            },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(modifier = Modifier.matchParentSize().clickable { open = true })
    }
    if (open) PeerListEditorDialog(peers, { open = false }, onChange)
}

@Composable
internal fun PeerListEditorDialog(peers: List<Peer>, onDismiss: () -> Unit, onChange: (List<Peer>) -> Unit) {
    var draft by remember(peers) { mutableStateOf(peers) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var uri by remember { mutableStateOf("") }
    var publicKey by remember { mutableStateOf("") }

    fun reset() {
        editingIndex = null
        uri = ""
        publicKey = ""
    }

    fun commit() {
        val trimmedUri = uri.trim()
        if (trimmedUri.isEmpty()) {
            reset()
            return
        }
        val peer = Peer(uri = trimmedUri, peerPublicKey = publicKey.trim().ifBlank { null })
        draft = when (editingIndex) {
            null -> draft + peer
            else -> draft.mapIndexed { i, existing -> if (i == editingIndex) peer else existing }
        }
        reset()
    }

    AlertDialog(
        onDismissRequest = { if (draft != peers) onChange(draft); onDismiss() },
        title = { Text(stringResource(R.string.list_peers)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(if (editingIndex != null) R.string.list_edit_peer else R.string.list_add_peer),
                    style = MaterialTheme.typography.labelLarge,
                )
                OutlinedTextField(
                    value = uri,
                    onValueChange = { uri = it },
                    label = { Text(stringResource(R.string.list_peer_uri)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = publicKey,
                    onValueChange = { publicKey = it },
                    label = { Text(stringResource(R.string.list_peer_public_key_optional)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (editingIndex != null) {
                        TextButton(onClick = { reset() }) { Text(stringResource(R.string.action_cancel)) }
                    }
                    Button(
                        onClick = { commit() },
                        enabled = uri.trim().isNotEmpty(),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Icon(if (editingIndex == null) Icons.Default.Add else Icons.Default.Check, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(if (editingIndex == null) R.string.action_add else R.string.action_update))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (draft.isEmpty()) {
                    Text(
                        stringResource(R.string.list_no_peers),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        draft.forEachIndexed { index, peer ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(peer.uri, style = MaterialTheme.typography.bodyMedium)
                                    peer.peerPublicKey?.takeIf { it.isNotBlank() }?.let {
                                        Text(
                                            stringResource(R.string.list_peer_key, it),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                IconButton(onClick = {
                                    editingIndex = index
                                    uri = peer.uri
                                    publicKey = peer.peerPublicKey.orEmpty()
                                }) {
                                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.content_edit_peer))
                                }
                                IconButton(onClick = { draft = draft.filterIndexed { i, _ -> i != index } }) {
                                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.content_delete_peer))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (draft != peers) onChange(draft); onDismiss() },
                shape = MaterialTheme.shapes.medium,
            ) { Text(stringResource(R.string.action_done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
internal fun ProxyNetworkListField(networks: List<ProxyNetwork>, error: String?, onChange: (List<ProxyNetwork>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.list_proxy_networks)
    val fieldValue = when {
        networks.isEmpty() -> ""
        networks.size == 1 -> networks.first().cidr
        else -> pluralStringResource(R.plurals.proxy_network_count, networks.size, networks.size)
    }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = fieldValue,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            supportingText = if (error == null) null else { { Text(error) } },
            isError = error != null,
            trailingIcon = {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.content_edit_field, label),
                )
            },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(modifier = Modifier.matchParentSize().clickable { open = true })
    }
    if (open) ProxyNetworkEditorDialog(networks, { open = false }, onChange)
}

@Composable
internal fun ProxyNetworkEditorDialog(networks: List<ProxyNetwork>, onDismiss: () -> Unit, onChange: (List<ProxyNetwork>) -> Unit) {
    var draft by remember(networks) { mutableStateOf(networks) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var cidr by remember { mutableStateOf("") }
    var mappedCidr by remember { mutableStateOf("") }
    var allow by remember { mutableStateOf("") }

    fun reset() {
        editingIndex = null
        cidr = ""
        mappedCidr = ""
        allow = ""
    }

    fun commit() {
        val trimmedCidr = cidr.trim()
        if (trimmedCidr.isEmpty()) {
            reset()
            return
        }
        val network = ProxyNetwork(
            cidr = trimmedCidr,
            mappedCidr = mappedCidr.trim().ifBlank { null },
            allow = allow.split(",", " ").map(String::trim).filter(String::isNotEmpty),
        )
        draft = when (editingIndex) {
            null -> draft + network
            else -> draft.mapIndexed { i, existing -> if (i == editingIndex) network else existing }
        }
        reset()
    }

    AlertDialog(
        onDismissRequest = { if (draft != networks) onChange(draft); onDismiss() },
        title = { Text(stringResource(R.string.list_proxy_networks)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(if (editingIndex != null) R.string.list_edit_network else R.string.list_add_network),
                    style = MaterialTheme.typography.labelLarge,
                )
                OutlinedTextField(
                    value = cidr,
                    onValueChange = { cidr = it },
                    label = { Text(stringResource(R.string.list_cidr)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = mappedCidr,
                    onValueChange = { mappedCidr = it },
                    label = { Text(stringResource(R.string.list_mapped_cidr_optional)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = allow,
                    onValueChange = { allow = it },
                    label = { Text(stringResource(R.string.list_allow_protocols)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (editingIndex != null) {
                        TextButton(onClick = { reset() }) { Text(stringResource(R.string.action_cancel)) }
                    }
                    Button(
                        onClick = { commit() },
                        enabled = cidr.trim().isNotEmpty(),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Icon(if (editingIndex == null) Icons.Default.Add else Icons.Default.Check, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(if (editingIndex == null) R.string.action_add else R.string.action_update))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (draft.isEmpty()) {
                    Text(
                        stringResource(R.string.list_no_proxy_networks),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        draft.forEachIndexed { index, network ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(network.cidr, style = MaterialTheme.typography.bodyMedium)
                                    val extra = listOfNotNull(
                                        network.mappedCidr?.takeIf { it.isNotBlank() }?.let {
                                            stringResource(R.string.list_mapped_summary, it)
                                        },
                                        network.allow.takeIf { it.isNotEmpty() }?.joinToString(",")?.let {
                                            stringResource(R.string.list_allow_summary, it)
                                        },
                                    ).joinToString(" • ")
                                    if (extra.isNotEmpty()) {
                                        Text(
                                            extra,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                IconButton(onClick = {
                                    editingIndex = index
                                    cidr = network.cidr
                                    mappedCidr = network.mappedCidr.orEmpty()
                                    allow = network.allow.joinToString(",")
                                }) {
                                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.content_edit_network))
                                }
                                IconButton(onClick = { draft = draft.filterIndexed { i, _ -> i != index } }) {
                                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.content_delete_network))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (draft != networks) onChange(draft); onDismiss() },
                shape = MaterialTheme.shapes.medium,
            ) { Text(stringResource(R.string.action_done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
internal fun PortForwardListField(forwards: List<PortForward>, error: String?, onChange: (List<PortForward>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.list_port_forwards)
    val fieldValue = when {
        forwards.isEmpty() -> ""
        forwards.size == 1 -> forwards.first().bindAddr
        else -> pluralStringResource(R.plurals.port_forward_count, forwards.size, forwards.size)
    }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = fieldValue,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            supportingText = if (error == null) null else { { Text(error) } },
            isError = error != null,
            trailingIcon = {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.content_edit_field, label),
                )
            },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(modifier = Modifier.matchParentSize().clickable { open = true })
    }
    if (open) PortForwardEditorDialog(forwards, { open = false }, onChange)
}

@Composable
internal fun PortForwardEditorDialog(forwards: List<PortForward>, onDismiss: () -> Unit, onChange: (List<PortForward>) -> Unit) {
    var draft by remember(forwards) { mutableStateOf(forwards) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var bindAddr by remember { mutableStateOf("") }
    var dstAddr by remember { mutableStateOf("") }
    var proto by remember { mutableStateOf("tcp") }

    fun reset() {
        editingIndex = null
        bindAddr = ""
        dstAddr = ""
        proto = "tcp"
    }

    fun commit() {
        if (bindAddr.isBlank() || dstAddr.isBlank()) {
            reset()
            return
        }
        val forward = PortForward(bindAddr = bindAddr.trim(), dstAddr = dstAddr.trim(), proto = proto.trim().lowercase())
        draft = when (editingIndex) {
            null -> draft + forward
            else -> draft.mapIndexed { i, existing -> if (i == editingIndex) forward else existing }
        }
        reset()
    }

    AlertDialog(
        onDismissRequest = { if (draft != forwards) onChange(draft); onDismiss() },
        title = { Text(stringResource(R.string.list_port_forwards)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(if (editingIndex != null) R.string.list_edit_forward else R.string.list_add_forward),
                    style = MaterialTheme.typography.labelLarge,
                )
                OutlinedTextField(
                    value = bindAddr,
                    onValueChange = { bindAddr = it },
                    label = { Text(stringResource(R.string.list_bind_address)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = dstAddr,
                    onValueChange = { dstAddr = it },
                    label = { Text(stringResource(R.string.list_destination_address)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                ChoiceRow(
                    stringResource(R.string.protocol_label),
                    proto,
                    listOf(
                        ChoiceOption("tcp", stringResource(R.string.protocol_tcp)),
                        ChoiceOption("udp", stringResource(R.string.protocol_udp)),
                    ),
                ) { proto = it }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (editingIndex != null) {
                        TextButton(onClick = { reset() }) { Text(stringResource(R.string.action_cancel)) }
                    }
                    Button(
                        onClick = { commit() },
                        enabled = bindAddr.isNotBlank() && dstAddr.isNotBlank(),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Icon(if (editingIndex == null) Icons.Default.Add else Icons.Default.Check, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(if (editingIndex == null) R.string.action_add else R.string.action_update))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (draft.isEmpty()) {
                    Text(
                        stringResource(R.string.list_no_port_forwards),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        draft.forEachIndexed { index, forward ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(R.string.list_port_forward_summary, forward.bindAddr, forward.dstAddr, forward.proto),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = {
                                    editingIndex = index
                                    bindAddr = forward.bindAddr
                                    dstAddr = forward.dstAddr
                                    proto = forward.proto
                                }) {
                                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.content_edit_forward))
                                }
                                IconButton(onClick = { draft = draft.filterIndexed { i, _ -> i != index } }) {
                                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.content_delete_forward))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (draft != forwards) onChange(draft); onDismiss() },
                shape = MaterialTheme.shapes.medium,
            ) { Text(stringResource(R.string.action_done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
