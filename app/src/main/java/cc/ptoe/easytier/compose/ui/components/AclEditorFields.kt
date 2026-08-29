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
import cc.ptoe.easytier.compose.data.Acl
import cc.ptoe.easytier.compose.data.AclAction
import cc.ptoe.easytier.compose.data.AclChain
import cc.ptoe.easytier.compose.data.AclChainType
import cc.ptoe.easytier.compose.data.AclGroupDeclare
import cc.ptoe.easytier.compose.data.AclProtocol
import cc.ptoe.easytier.compose.data.AclRule

/**
 * ACL editor entry point: an enable switch plus a field that opens the
 * chain/group editor. `null` disables the ACL entirely.
 */
@Composable
internal fun AclField(acl: Acl?, error: String?, onChange: (Acl?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val enabled = acl != null
    SwitchRow(stringResource(R.string.editor_acl), enabled) { checked ->
        onChange(if (checked) Acl() else null)
    }
    if (acl != null) {
        val label = stringResource(R.string.list_acl_chains)
        val fieldValue = when {
            acl.chains.isEmpty() && acl.group.declares.isEmpty() && acl.group.members.isEmpty() -> ""
            acl.chains.size == 1 -> acl.chains.first().name
            else -> pluralStringResource(
                R.plurals.acl_chain_count,
                acl.chains.size,
                acl.chains.size
            )
        }
        Box(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = fieldValue,
                onValueChange = {},
                readOnly = true,
                label = { Text(label) },
                supportingText = if (error == null) null else {
                    { Text(error) }
                },
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
            Box(modifier = Modifier
                .matchParentSize()
                .clickable { open = true })
        }
    }
    if (open) {
        acl?.let { AclEditorDialog(it, { open = false }, onChange) }
    }
}

@Composable
private fun AclEditorDialog(acl: Acl, onDismiss: () -> Unit, onChange: (Acl) -> Unit) {
    var draft by remember(acl) { mutableStateOf(acl) }
    var editingChain by remember { mutableStateOf<Int?>(null) }
    var editingDeclare by remember { mutableStateOf<Int?>(null) }
    var declareName by remember { mutableStateOf("") }
    var declareSecret by remember { mutableStateOf("") }

    fun commitDeclare() {
        val name = declareName.trim()
        if (name.isEmpty()) {
            editingDeclare = null
            declareName = ""
            declareSecret = ""
            return
        }
        val declare = AclGroupDeclare(groupName = name, groupSecret = declareSecret)
        draft = draft.copy(
            group = draft.group.copy(
                declares = when (editingDeclare) {
                    null -> draft.group.declares + declare
                    else -> draft.group.declares.mapIndexed { i, d -> if (i == editingDeclare) declare else d }
                },
            ),
        )
        editingDeclare = null
        declareName = ""
        declareSecret = ""
    }

    AlertDialog(
        onDismissRequest = { if (draft != acl) onChange(draft); onDismiss() },
        title = { Text(stringResource(R.string.editor_acl)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Chains
                Text(
                    stringResource(R.string.list_acl_chains),
                    style = MaterialTheme.typography.labelLarge
                )
                if (draft.chains.isEmpty()) {
                    Text(
                        stringResource(R.string.list_no_chains),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        draft.chains.forEachIndexed { index, chain ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(
                                        R.string.list_chain_summary,
                                        chain.name,
                                        chain.chainType.name,
                                        chain.rules.size
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { editingChain = index }) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = stringResource(R.string.content_edit_chain)
                                    )
                                }
                                IconButton(onClick = {
                                    draft =
                                        draft.copy(chains = draft.chains.filterIndexed { i, _ -> i != index })
                                }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.content_delete_chain)
                                    )
                                }
                            }
                        }
                    }
                }
                Button(
                    onClick = { editingChain = draft.chains.size },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.list_add_chain))
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Group declarations
                Text(
                    stringResource(R.string.list_acl_group),
                    style = MaterialTheme.typography.labelLarge
                )
                ListField(
                    stringResource(R.string.list_acl_members),
                    draft.group.members,
                    null,
                ) { members -> draft = draft.copy(group = draft.group.copy(members = members)) }
                Text(
                    stringResource(R.string.list_acl_group_declares),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (draft.group.declares.isEmpty()) {
                    Text(
                        stringResource(R.string.list_no_group_declares),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        draft.group.declares.forEachIndexed { index, declare ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(
                                        R.string.list_group_declare_summary,
                                        declare.groupName
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = {
                                    editingDeclare = index
                                    declareName = declare.groupName
                                    declareSecret = declare.groupSecret
                                }) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = stringResource(R.string.content_edit_group)
                                    )
                                }
                                IconButton(onClick = {
                                    draft =
                                        draft.copy(group = draft.group.copy(declares = draft.group.declares.filterIndexed { i, _ -> i != index }))
                                }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.content_delete_group)
                                    )
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = declareName,
                    onValueChange = { declareName = it },
                    label = { Text(stringResource(R.string.list_group_name)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = declareSecret,
                    onValueChange = { declareSecret = it },
                    label = { Text(stringResource(R.string.list_group_secret)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { commitDeclare() },
                    enabled = declareName.trim().isNotEmpty(),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(
                        if (editingDeclare == null) Icons.Default.Add else Icons.Default.Check,
                        null,
                        Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(if (editingDeclare == null) R.string.list_add_group_declare else R.string.list_edit_group_declare))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (draft != acl) onChange(draft); onDismiss() },
                shape = MaterialTheme.shapes.medium,
            ) { Text(stringResource(R.string.action_done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )

    editingChain?.let { index ->
        AclChainEditorDialog(
            chain = draft.chains.getOrNull(index) ?: AclChain(),
            title = stringResource(if (index == draft.chains.size) R.string.list_add_chain else R.string.list_edit_chain),
            onDismiss = { editingChain = null },
            onChange = { chain ->
                draft = if (index == draft.chains.size) {
                    draft.copy(chains = draft.chains + chain)
                } else {
                    draft.copy(chains = draft.chains.mapIndexed { i, c -> if (i == index) chain else c })
                }
                editingChain = null
            },
        )
    }
}

@Composable
private fun AclChainEditorDialog(
    chain: AclChain,
    title: String,
    onDismiss: () -> Unit,
    onChange: (AclChain) -> Unit
) {
    var name by remember(chain) { mutableStateOf(chain.name) }
    var chainType by remember(chain) { mutableStateOf(chain.chainType.value.toString()) }
    var description by remember(chain) { mutableStateOf(chain.description) }
    var enabled by remember(chain) { mutableStateOf(chain.enabled) }
    var defaultAction by remember(chain) { mutableStateOf(chain.defaultAction.value.toString()) }
    var rules by remember(chain) { mutableStateOf(chain.rules) }
    var editingRule by remember { mutableStateOf<Int?>(null) }

    fun commit() {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            onDismiss()
            return
        }
        onChange(
            AclChain(
                name = trimmedName,
                chainType = AclChainType.fromValue(chainType.toIntOrNull() ?: 3)
                    ?: AclChainType.Forward,
                description = description.trim(),
                enabled = enabled,
                rules = rules,
                defaultAction = AclAction.fromValue(defaultAction.toIntOrNull() ?: 1)
                    ?: AclAction.Allow,
            ),
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.list_chain_name)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                ChoiceRow(
                    stringResource(R.string.list_chain_type),
                    chainType,
                    listOf(
                        ChoiceOption(
                            AclChainType.Inbound.value.toString(),
                            stringResource(R.string.choice_chain_inbound)
                        ),
                        ChoiceOption(
                            AclChainType.Outbound.value.toString(),
                            stringResource(R.string.choice_chain_outbound)
                        ),
                        ChoiceOption(
                            AclChainType.Forward.value.toString(),
                            stringResource(R.string.choice_chain_forward)
                        ),
                    ),
                ) { chainType = it }
                ChoiceRow(
                    stringResource(R.string.list_chain_default_action),
                    defaultAction,
                    listOf(
                        ChoiceOption(
                            AclAction.Allow.value.toString(),
                            stringResource(R.string.choice_action_allow)
                        ),
                        ChoiceOption(
                            AclAction.Drop.value.toString(),
                            stringResource(R.string.choice_action_drop)
                        ),
                    ),
                ) { defaultAction = it }
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.list_chain_description)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow(stringResource(R.string.list_chain_enabled), enabled) { enabled = it }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    stringResource(R.string.list_rules),
                    style = MaterialTheme.typography.titleSmall
                )
                if (rules.isEmpty()) {
                    Text(
                        stringResource(R.string.list_no_rules),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        rules.forEachIndexed { index, rule ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(
                                        R.string.list_rule_summary,
                                        rule.name,
                                        rule.action.name
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { editingRule = index }) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = stringResource(R.string.content_edit_rule)
                                    )
                                }
                                IconButton(onClick = {
                                    rules = rules.filterIndexed { i, _ -> i != index }
                                }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.content_delete_rule)
                                    )
                                }
                            }
                        }
                    }
                }
                Button(
                    onClick = { editingRule = rules.size },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.list_add_rule))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { commit() },
                enabled = name.trim().isNotEmpty(),
                shape = MaterialTheme.shapes.medium,
            ) { Text(stringResource(R.string.action_done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )

    editingRule?.let { index ->
        AclRuleEditorDialog(
            rule = rules.getOrNull(index) ?: AclRule(),
            title = stringResource(if (index == rules.size) R.string.list_add_rule else R.string.list_edit_rule),
            onDismiss = { editingRule = null },
            onChange = { rule ->
                rules = if (index == rules.size) {
                    rules + rule
                } else {
                    rules.mapIndexed { i, r -> if (i == index) rule else r }
                }
                editingRule = null
            },
        )
    }
}

@Composable
private fun AclRuleEditorDialog(
    rule: AclRule,
    title: String,
    onDismiss: () -> Unit,
    onChange: (AclRule) -> Unit
) {
    var name by remember(rule) { mutableStateOf(rule.name) }
    var description by remember(rule) { mutableStateOf(rule.description) }
    var priority by remember(rule) { mutableStateOf(rule.priority.toString()) }
    var enabled by remember(rule) { mutableStateOf(rule.enabled) }
    var protocol by remember(rule) { mutableStateOf(rule.protocol.value.toString()) }
    var action by remember(rule) { mutableStateOf(rule.action.value.toString()) }
    var ports by remember(rule) { mutableStateOf(rule.ports) }
    var sourceIps by remember(rule) { mutableStateOf(rule.sourceIps) }
    var destinationIps by remember(rule) { mutableStateOf(rule.destinationIps) }
    var sourcePorts by remember(rule) { mutableStateOf(rule.sourcePorts) }
    var sourceGroups by remember(rule) { mutableStateOf(rule.sourceGroups) }
    var destinationGroups by remember(rule) { mutableStateOf(rule.destinationGroups) }
    var rateLimit by remember(rule) { mutableStateOf(rule.rateLimit.toString()) }
    var burstLimit by remember(rule) { mutableStateOf(rule.burstLimit.toString()) }
    var stateful by remember(rule) { mutableStateOf(rule.stateful) }

    fun commit() {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            onDismiss()
            return
        }
        onChange(
            AclRule(
                name = trimmedName,
                description = description.trim(),
                priority = priority.trim().toIntOrNull() ?: 0,
                enabled = enabled,
                protocol = AclProtocol.fromValue(protocol.toIntOrNull() ?: 5) ?: AclProtocol.Any,
                ports = ports,
                sourceIps = sourceIps,
                destinationIps = destinationIps,
                sourcePorts = sourcePorts,
                action = AclAction.fromValue(action.toIntOrNull() ?: 1) ?: AclAction.Allow,
                rateLimit = rateLimit.trim().toIntOrNull() ?: 0,
                burstLimit = burstLimit.trim().toIntOrNull() ?: 0,
                stateful = stateful,
                sourceGroups = sourceGroups,
                destinationGroups = destinationGroups,
            ),
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.list_rule_name)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.list_rule_description)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                ChoiceRow(
                    stringResource(R.string.list_rule_protocol),
                    protocol,
                    listOf(
                        ChoiceOption(
                            AclProtocol.TCP.value.toString(),
                            stringResource(R.string.choice_protocol_tcp)
                        ),
                        ChoiceOption(
                            AclProtocol.UDP.value.toString(),
                            stringResource(R.string.choice_protocol_udp)
                        ),
                        ChoiceOption(
                            AclProtocol.ICMP.value.toString(),
                            stringResource(R.string.choice_protocol_icmp)
                        ),
                        ChoiceOption(
                            AclProtocol.ICMPv6.value.toString(),
                            stringResource(R.string.choice_protocol_icmpv6)
                        ),
                        ChoiceOption(
                            AclProtocol.Any.value.toString(),
                            stringResource(R.string.choice_protocol_any)
                        ),
                    ),
                ) { protocol = it }
                ChoiceRow(
                    stringResource(R.string.list_rule_action),
                    action,
                    listOf(
                        ChoiceOption(
                            AclAction.Allow.value.toString(),
                            stringResource(R.string.choice_action_allow)
                        ),
                        ChoiceOption(
                            AclAction.Drop.value.toString(),
                            stringResource(R.string.choice_action_drop)
                        ),
                    ),
                ) { action = it }
                OutlinedTextField(
                    value = priority,
                    onValueChange = { priority = it },
                    label = { Text(stringResource(R.string.list_rule_priority)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow(stringResource(R.string.list_rule_enabled), enabled) { enabled = it }
                SwitchRow(stringResource(R.string.list_rule_stateful), stateful) { stateful = it }
                ListField(stringResource(R.string.list_rule_ports), ports, null) { ports = it }
                ListField(
                    stringResource(R.string.list_rule_source_ips),
                    sourceIps,
                    null
                ) { sourceIps = it }
                ListField(
                    stringResource(R.string.list_rule_destination_ips),
                    destinationIps,
                    null
                ) { destinationIps = it }
                ListField(
                    stringResource(R.string.list_rule_source_ports),
                    sourcePorts,
                    null
                ) { sourcePorts = it }
                ListField(
                    stringResource(R.string.list_rule_source_groups),
                    sourceGroups,
                    null
                ) { sourceGroups = it }
                ListField(
                    stringResource(R.string.list_rule_destination_groups),
                    destinationGroups,
                    null
                ) { destinationGroups = it }
                OutlinedTextField(
                    value = rateLimit,
                    onValueChange = { rateLimit = it },
                    label = { Text(stringResource(R.string.list_rule_rate_limit)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = burstLimit,
                    onValueChange = { burstLimit = it },
                    label = { Text(stringResource(R.string.list_rule_burst_limit)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { commit() },
                enabled = name.trim().isNotEmpty(),
                shape = MaterialTheme.shapes.medium,
            ) { Text(stringResource(R.string.action_done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
