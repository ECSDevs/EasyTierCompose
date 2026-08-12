@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cc.ptoe.easytier.compose.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cc.ptoe.easytier.compose.R

@Composable
internal fun SectionCard(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(
        shape = MaterialTheme.shapes.large,
        colors = androidx.compose.material3.CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
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
internal fun FormField(
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
internal fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onChange, enabled = enabled)
    }
}

internal data class ChoiceOption(val value: String, val label: String)
@Composable
internal fun ChoiceRow(label: String, value: String, options: List<ChoiceOption>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.value == value }?.label ?: value
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option.label) }, onClick = { onChange(option.value); expanded = false })
            }
        }
    }
}

@Composable
internal fun ListField(
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
        else -> pluralStringResource(R.plurals.list_entry_count, items.size, items.size)
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
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.content_edit_field, label))
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
internal fun ListEditorDialog(
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
                Text(
                    stringResource(if (editingIndex != null) R.string.list_edit_entry else R.string.list_new_entry),
                    style = MaterialTheme.typography.labelLarge,
                )
                OutlinedTextField(
                    value = editingValue,
                    onValueChange = { editingValue = it },
                    label = { Text(stringResource(if (editingIndex != null) R.string.list_entry_value else R.string.list_new_entry_label)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (editingValue.isNotEmpty()) {
                            IconButton(onClick = { editingValue = "" }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_clear))
                            }
                        }
                    },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (editingIndex != null) {
                        TextButton(onClick = { editingIndex = null; editingValue = "" }) { Text(stringResource(R.string.action_cancel)) }
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
                        Text(stringResource(if (editingIndex == null) R.string.action_add else R.string.action_update))
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                if (draft.isEmpty()) {
                    Text(
                        text = stringResource(R.string.list_empty),
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
                                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.content_edit_entry))
                                }
                                IconButton(onClick = { draft = draft.filterIndexed { i, _ -> i != index } }) {
                                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.content_delete_entry))
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
            ) { Text(stringResource(R.string.action_done)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
