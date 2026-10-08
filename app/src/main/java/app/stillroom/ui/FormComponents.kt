package app.stillroom.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** Persistent visible labels remain available after input, at every font size. */
@Composable
fun LabeledTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    isError: Boolean = false,
    supportingText: String? = null,
    placeholder: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = value, onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth().semantics { contentDescription = label },
            enabled = enabled, singleLine = singleLine, isError = isError,
            supportingText = supportingText?.let { text -> { Text(text) } },
            placeholder = placeholder, visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
            shape = MaterialTheme.shapes.small,
        )
    }
}

/** Bounded, searchable selection of existing records; no additional domain operation. */
@Composable
fun ChoiceField(
    label: String,
    options: List<Pair<Long, String>>,
    selected: Long?,
    onSelect: (Long?) -> Unit,
    allowNone: Boolean = false,
    noneLabel: String = "None",
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val current = options.firstOrNull { it.first == selected }?.second
        ?: if (selected == null && allowNone) noneLabel else "Choose"
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        SecondaryButton(
            onClick = { query = ""; expanded = true }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .semantics { contentDescription = "$label: $current" },
            shape = MaterialTheme.shapes.small,
        ) { Text(current, modifier = Modifier.weight(1f)); if (current != "Choose") Text("Change") }
    }
    if (expanded) {
        AlertDialog(
            onDismissRequest = { expanded = false }, title = { Text("Choose ${label.lowercase()}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    LabeledTextField(query, { query = it }, "Search ${label.lowercase()}", singleLine = true)
                    val matches = options.filter { it.second.contains(query, ignoreCase = true) }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        if (allowNone && (query.isBlank() || noneLabel.contains(query, true))) {
                            item { QuietButton(onClick = { onSelect(null); expanded = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(noneLabel, Modifier.weight(1f)) } }
                        }
                        items(matches, key = { it.first }) { option ->
                            QuietButton(onClick = { onSelect(option.first); expanded = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(option.second, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                                if (option.first == selected) Text("Selected")
                            }
                        }
                        if (matches.isEmpty() && !(allowNone && (query.isBlank() || noneLabel.contains(query, true)))) item { Text("No matching options", style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }, confirmButton = { QuietButton(onClick = { expanded = false }) { Text("Cancel") } },
        )
    }
}
