package app.stillroom.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.stillroom.domain.UserfieldDefinition
import app.stillroom.domain.UserfieldText
import app.stillroom.domain.UserfieldTypes
import app.stillroom.domain.UserfieldValues
import kotlinx.serialization.json.JsonPrimitive

/**
 * The one userfield input, used by the product editor (Pantry) and Household → Records.
 * [value] is the string Grocy stores; [onChange] receives the new stored string.
 */
@Composable
internal fun UserfieldEditor(field: UserfieldDefinition, value: String, onChange: (String) -> Unit, enabled: Boolean = true) {
    val label = field.label + if (field.inputRequired) " (required)" else ""
    val error = if (UserfieldTypes.editable(field.type)) UserfieldValues.error(field, value) else null
    when (field.canonicalType.takeIf { UserfieldTypes.editable(field.type) }) {
        UserfieldTypes.TEXT -> LabeledTextField(value, onChange, label, singleLine = true, enabled = enabled, isError = error != null, supportingText = error)
        UserfieldTypes.MULTILINE -> LabeledTextField(value, onChange, label, enabled = enabled, isError = error != null, supportingText = error)
        UserfieldTypes.INTEGER -> LabeledTextField(value, onChange, label, singleLine = true, enabled = enabled, isError = error != null, supportingText = error ?: "Whole number")
        UserfieldTypes.DECIMAL -> LabeledTextField(value, onChange, label, singleLine = true, enabled = enabled, isError = error != null, supportingText = error ?: "Number; 1.5 and 1½ both work")
        UserfieldTypes.CURRENCY -> LabeledTextField(value, onChange, label, singleLine = true, enabled = enabled, isError = error != null, supportingText = error ?: "Amount, e.g. 4.50")
        UserfieldTypes.DATE -> LabeledTextField(value, onChange, label, singleLine = true, enabled = enabled, isError = error != null, supportingText = error ?: "YYYY-MM-DD")
        UserfieldTypes.DATETIME -> LabeledTextField(value, onChange, label, singleLine = true, enabled = enabled, isError = error != null, supportingText = error ?: "YYYY-MM-DD HH:mm")
        UserfieldTypes.CHECKBOX -> CheckRow(field.label, value == "1", enabled) { onChange(if (it) "1" else "0") }
        UserfieldTypes.PRESET_LIST -> {
            val options = UserfieldValues.options(field).let { if (value.isNotBlank() && value !in it) it + value else it }
            ChoiceField(label, options.mapIndexed { i, o -> i.toLong() to o }, options.indexOf(value).takeIf { it >= 0 }?.toLong(),
                { selected -> onChange(selected?.let { options[it.toInt()] }.orEmpty()) }, allowNone = true, noneLabel = "Not set", enabled = enabled)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        UserfieldTypes.PRESET_CHECKLIST -> {
            val options = UserfieldValues.options(field)
            val selected = UserfieldValues.checklist(value)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                (options + selected.filter { it !in options }).forEach { option ->
                    CheckRow(option, option in selected, enabled) { checked ->
                        onChange(UserfieldValues.checklistValue(if (checked) selected + option else selected - option, options))
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        UserfieldTypes.LINK -> LabeledTextField(value, onChange, label, singleLine = true, enabled = enabled, isError = error != null, supportingText = error ?: "Full link, e.g. https://example.org")
        UserfieldTypes.LINK_WITH_TITLE -> {
            val link = UserfieldValues.link(value)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledTextField(link.title, { onChange(UserfieldValues.linkValue(it, link.link)) }, "$label: title", singleLine = true, enabled = enabled)
                LabeledTextField(link.link, { onChange(UserfieldValues.linkValue(link.title, it)) }, "$label: link", singleLine = true, enabled = enabled, isError = error != null, supportingText = error ?: "Full link, e.g. https://example.org")
            }
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // File, image and unknown types: shown, never written.
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(UserfieldText.render(field.type, JsonPrimitive(value), androidx.compose.runtime.remember { app.stillroom.fractions.QuantityFormatter() }, java.util.Locale.getDefault()) ?: "Not set")
            UserfieldTypes.reason(field)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
internal fun CheckRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, null, enabled = enabled)
        Text(label, Modifier.weight(1f))
    }
}
