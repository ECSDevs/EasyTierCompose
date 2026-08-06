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
import androidx.compose.ui.unit.dp
import cc.ptoe.easytier.compose.data.Peer
import cc.ptoe.easytier.compose.data.PortForward
import cc.ptoe.easytier.compose.data.ProxyNetwork

@Composable
internal fun PeerListField(peers: List<Peer>, error: String?, onChange: (List<Peer>) -> Unit) {
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
internal fun PeerListEditorDialog(peers: List<Peer>, onDismiss: () -> Unit, onChange: (List<Peer>) -> Unit) {
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
internal fun ProxyNetworkListField(networks: List<ProxyNetwork>, error: String?, onChange: (List<ProxyNetwork>) -> Unit) {
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
internal fun ProxyNetworkEditorDialog(networks: List<ProxyNetwork>, onDismiss: () -> Unit, onChange: (List<ProxyNetwork>) -> Unit) {
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
internal fun PortForwardListField(forwards: List<PortForward>, error: String?, onChange: (List<PortForward>) -> Unit) {
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
internal fun PortForwardEditorDialog(forwards: List<PortForward>, onDismiss: () -> Unit, onChange: (List<PortForward>) -> Unit) {
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
