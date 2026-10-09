package app.stillroom.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.stillroom.domain.StockDetailItem
import app.stillroom.domain.UserfieldText

/**
 * Settings → Stock → Shown details. Lists the built-in pantry row attributes plus this
 * account's product userfields from Grocy; each can be switched on or off and extras can be
 * moved up or down. Saved per Grocy account on this phone. Display only.
 */
@Composable
internal fun StockDetailsSettings(model: StockViewModel) {
    val state by model.state.collectAsState()
    val details = state.stockDetails()
    Text("Stock", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    Text("Shown details", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
    Text("Choose what each pantry row shows. Saved for this Grocy account on this phone; nothing is changed in Grocy.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column(Modifier.testTag("stock-shown-details")) {
        details.main.forEach { item ->
            SettingToggle(item.label, item.visible, onChange = { model.setDetailVisible(item.key, it) })
        }
        Text("Extra details", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
        Text("Shown under the product name, in this order.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val extras = details.extras
        extras.forEachIndexed { index, item ->
            ExtraDetailRow(item, canMoveUp = index > 0, canMoveDown = index < extras.lastIndex, model)
        }
        when {
            details.fields == null -> {
                Text(if (state.busy) "Loading custom fields from Grocy…" else "Custom fields could not be loaded from Grocy. Saved choices for them are kept.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                QuietButton(onClick = model::refresh, enabled = !state.busy) { Text("Load custom fields") }
            }
            details.fields.isEmpty() -> Text("This Grocy account has no product custom fields (userfields).",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
        QuietButton(onClick = model::resetDetails, enabled = state.detailSettings != null) { Text("Reset shown details") }
    }
}

@Composable
private fun ExtraDetailRow(item: StockDetailItem, canMoveUp: Boolean, canMoveDown: Boolean, model: StockViewModel) {
    val supporting = item.field?.let { field ->
        "Custom field · ${UserfieldText.typeLabel(field.type)}" + if (field.showAsColumn) " · a column in Grocy's tables" else ""
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            SettingToggle(item.label, item.visible, supportingText = supporting, onChange = { model.setDetailVisible(item.key, it) })
        }
        IconButton(onClick = { model.moveDetail(item.key, up = true) }, enabled = canMoveUp) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move ${item.label} up")
        }
        IconButton(onClick = { model.moveDetail(item.key, up = false) }, enabled = canMoveDown) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move ${item.label} down")
        }
    }
}
