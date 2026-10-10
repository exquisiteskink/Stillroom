package app.stillroom.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.stillroom.R
import app.stillroom.domain.*
import app.stillroom.fractions.QuantityFractions
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale

@Composable @ReadOnlyComposable
internal fun shoppingQuantity(value: BigDecimal) = quantityText(value)

@Composable
fun ShoppingScreen(model: ShoppingViewModel, grants: Set<String>?) {
    val state by model.state.collectAsState()
    var listId by remember { mutableStateOf<Long?>(null) }
    var inStore by remember { mutableStateOf(false) }
    var grouping by remember { mutableStateOf("Category") }
    var editRow by remember { mutableStateOf<JsonObject?>(null) }
    var adding by remember { mutableStateOf(false) }
    var conflict by remember { mutableStateOf<ShoppingChange?>(null) }
    var mergeChange by remember { mutableStateOf<ShoppingChange?>(null) }
    var creatingList by remember { mutableStateOf(false) }
    var listName by remember { mutableStateOf("") }
    BackHandler(enabled = adding || editRow != null || conflict != null || creatingList) {
        when {
            adding || editRow != null -> { adding = false; editRow = null; mergeChange = null }
            creatingList -> creatingList = false
            else -> conflict = null
        }
    }
    if (state.denied || !ShoppingAccess.allowed(grants)) { PermissionDeniedState(); return }
    LaunchedEffect(Unit) { if (!state.denied) model.refresh() }

    LaunchedEffect(state.snapshot) {
        if (state.snapshot.rows("shopping_lists").none { it.shoppingText("id") == listId.toString() }) listId = state.snapshot.rows("shopping_lists").firstOrNull()?.shoppingText("id")?.toLong()
    }
    conflict?.let { change ->
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            QuietButton(onClick = { conflict = null }) { Text("Back to list") }
            ShoppingConflict(change, state.busy, onServer = { model.acceptServer(change.id); conflict = null }, onMerge = { observed -> conflict = null; mergeChange = change; editRow = observed; adding = false })
        }
        return
    }
    val selected = listId
    if ((adding || editRow != null) && selected != null) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ShoppingItemForm(selected, state.snapshot, editRow, mergeChange?.desired, state.busy, onBack = { adding = false; editRow = null; mergeChange = null }, onSave = { draft ->
                val merged = mergeChange
                if (merged != null && editRow != null) model.merge(merged.id, draft, editRow!!) else model.save(draft, editRow)
                adding = false; editRow = null; mergeChange = null
            })
        }
        return
    }
    val projection = state.snapshot.projected(state.changes)
    val estimate = selected?.let { projection.estimate(it) }
    val rows = if (selected == null) emptyList() else projection.rows("shopping_list").filter { it.shoppingText("shopping_list_id") == selected.toString() }
    val grouped = rows.groupBy { if (grouping == "None") "Items" else projection.group(it, grouping == "Store") }.toSortedMap()
    KitchenList(state.busy, model::refresh, spacedBy = 0.dp) {
        item {
            Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                KitchenWhisper(if (state.snapshot.stale) "You're shopping from the last saved list. Changes wait until you're online." else null)
                KitchenError(state.error)
                ChoiceField("Shopping list", state.snapshot.rows("shopping_lists").map { it.shoppingText("id").toLong() to it.shoppingText("name") }, selected, { listId = it }, enabled = !state.busy)
                QuietButton(onClick = { creatingList = !creatingList }, enabled = !state.busy) { Text(if (creatingList) "Cancel new list" else "Create list") }
                if (creatingList) {
                    LabeledTextField(listName, { listName = it }, "List name", singleLine = true)
                    SecondaryButton(onClick = { model.createList(listName); listName = ""; creatingList = false }, enabled = !state.busy && listName.isNotBlank() && listName.length <= 100) { Text("Save list") }
                }
                state.changes.firstOrNull { it.state in setOf("conflict", "needs-review") }?.let { change ->
                    Text("A shopping change needs review.", color = MaterialTheme.colorScheme.error)
                    SecondaryButton(onClick = { conflict = change }) { Text("Review change") }
                }
                if (estimate != null) Text("About ${estimate.knownTotal.stripTrailingZeros().toPlainString()} · ${estimate.unknownRows} items without a price yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilterChip(inStore, { inStore = !inStore }, { Text("At the store") })
                PrimaryButton(onClick = { adding = true }, enabled = !state.busy && selected != null, modifier = Modifier.fillMaxWidth()) { Text("Add item") }
                RemoveCheckedShoppingItems(rows, state.changes, state.busy, model::removeChecked)
                Text(if(LocalAddonSettings.current.shoppingPurchaseOwner=="external")"Tap a quantity to change it. Hold an item to remove it. Check an item to cross it out; your other tool adds pantry stock." else "Tap a quantity to change it. Hold an item to remove it. Check a product to add it to your pantry.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Group by", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Category", "Store", "None").forEach { value -> FilterChip(grouping == value, { grouping = value }, { Text(if (value == "None") "No grouping" else value) }) } }
            }
        }
        if (state.busy && rows.isEmpty()) item { Text(if(selected == null) "Loading shopping lists…" else "Loading list items…") }
        else if (rows.isEmpty() && state.error != null) item { ErrorState(state.error!!, model::refresh) }
        else if (selected == null) item { KitchenEmpty("No shopping lists", "Create a list for this week's groceries.", R.drawable.empty_shop) }
        else if (rows.isEmpty() && !state.busy) item { KitchenEmpty("This list is empty", "Add a product or a note to this list.", R.drawable.empty_shop) }
        else grouped.forEach { (group, entries) ->
            item { Box(Modifier.padding(vertical = 12.dp)) { KitchenSectionTitle(group) } }
            items(entries.sortedWith(compareBy({ it.shoppingText("done") == "1" }, { it.shoppingText("note") })), key = { it.shoppingText("id") }) { row ->
                val baseline = state.snapshot.rows("shopping_list").find { it.shoppingText("id") == row.shoppingText("id") }
                val change = state.changes.lastOrNull { it.rowId?.toString() == row.shoppingText("id") && it.state !in setOf("confirmed", "discarded") }
                ShoppingItem(row, baseline, projection, change, inStore, state.busy, StockAccess.canRead(grants),
                    onDone = { draft -> model.save(draft, baseline) }, onDelete = { baseline?.let(model::delete) }, onPurchase = { booking -> baseline?.let { model.purchase(it, booking) } })
            }
        }
    }
}

@Composable
internal fun RemoveCheckedShoppingItems(rows: List<JsonObject>, changes: List<ShoppingChange>, busy: Boolean, onRemove: (List<JsonObject>) -> Unit) {
    val completed = rows.filter { it.shoppingText("done") == "1" }
    if (completed.isEmpty()) return
    val completedIds = completed.map { it.shoppingText("id") }.toSet()
    val unresolved = changes.any { it.rowId?.toString() in completedIds && it.state !in setOf("confirmed", "discarded") }
    SecondaryButton(onClick = { onRemove(completed) }, enabled = !busy && !unresolved, modifier = Modifier.fillMaxWidth()) {
        Text("Remove checked items")
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShoppingItem(row: JsonObject, baseline: JsonObject?, snapshot: ShoppingSnapshot, change: ShoppingChange?, inStore: Boolean, busy: Boolean, canPurchase: Boolean, onDone: (ShoppingDraft) -> Unit, onDelete: () -> Unit, onPurchase: (StockBooking) -> Unit) {
    val externalPurchases=LocalAddonSettings.current.shoppingPurchaseOwner=="external"
    val product = snapshot.rows("products").find { it.shoppingText("id") == row.shoppingText("product_id") }
    val noteOnly = row.shoppingText("product_id").isBlank()
    val name = product?.shoppingText("name") ?: row.shoppingText("note").ifBlank { "Unavailable product" }
    val unit = row.shoppingText("qu_id").ifBlank { product?.shoppingText("qu_id_stock").orEmpty() }
    val factor = snapshot.factor(row.shoppingText("product_id"), unit) ?: BigDecimal.ONE
    val stockAmount = row.shoppingDecimal("amount") ?: BigDecimal.ZERO
    val quantity = stockAmount.divide(factor, MathContext.DECIMAL128)
    val unitName = snapshot.rows("quantity_units").find { it.shoppingText("id") == unit }?.shoppingText("name").orEmpty()
    val done = row.shoppingText("done") == "1"
    val enabled = !busy && baseline != null && (change == null || change.state == "pending")
    var editingQuantity by remember(row) { mutableStateOf(false) }
    fun draft(amount: BigDecimal = stockAmount, completed: Boolean = done) = ShoppingDraft(
        row.shoppingText("shopping_list_id").toLong(), row.shoppingText("product_id").toLongOrNull(),
        row.shoppingText("note"), amount, unit.toLongOrNull(), completed)
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = if (inStore) 80.dp else 64.dp)
            .combinedClickable(enabled = enabled, onClick = {}, onLongClickLabel = "Remove $name", onLongClick = onDelete)
            .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(done, {
                if (noteOnly || externalPurchases) onDone(draft(completed = !done))
                else if (product != null) onPurchase(StockBooking(StockAction.Purchase, row.shoppingText("product_id").toLong(), stockAmount,
                    location = product.shoppingText("location_id").toLongOrNull()?.takeIf { it > 0 },
                    store = product.shoppingText("shopping_location_id").toLongOrNull()?.takeIf { it > 0 },
                    note = row.shoppingText("note")))
            }, enabled = enabled && (noteOnly || externalPurchases && change==null || (product != null && !done && change == null && canPurchase && stockAmount.signum() > 0)),
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                    contentDescription = if (noteOnly || externalPurchases) "Mark $name complete" else if (done) "$name added to pantry" else "Add $name to pantry"
                })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = if (inStore) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                if (product != null && row.shoppingText("note").isNotBlank()) Text(row.shoppingText("note"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (change != null && change.state in setOf("needs-review", "conflict", "failed", "pending", "in-flight")) KitchenWhisper(when (change.state) {
                    "needs-review", "conflict" -> "Needs review"; "failed" -> "Could not save"; "in-flight" -> "Saving to Grocy…"; else -> "Pending sync"
                })
            }
            TextButton(onClick = { editingQuantity = true }, enabled = enabled,
                modifier = Modifier.heightIn(min = if (inStore) 64.dp else 48.dp).semantics { contentDescription = "Change quantity for $name" }) {
                Text("${shoppingQuantity(quantity)} $unitName")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
    if (editingQuantity) {
        val formatter = LocalQuantityFormatter.current
        val input = remember { RecipeAmountInput(quantity, Locale.getDefault(), formatter) }
        var text by remember { mutableStateOf(input.text) }
        var changed by remember { mutableStateOf(false) }
        val parsed = if (changed) QuantityFractions().parse(text, Locale.getDefault())?.value else quantity
        AlertDialog(onDismissRequest = { editingQuantity = false }, title = { Text("Quantity for $name") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (unitName.isNotBlank()) Text(unitName)
                LabeledTextField(text, { text = it; input.change(it); changed = true }, "Quantity (fractions accepted)",
                    isError = parsed == null || parsed.signum() < 0,
                    supportingText = if (parsed == null || parsed.signum() < 0) "Enter a quantity of zero or more." else null)
            } },
            dismissButton = { TextButton(onClick = { editingQuantity = false }) { Text("Cancel") } },
            confirmButton = { TextButton(onClick = {
                onDone(draft(if (changed) input.saved().multiply(factor) else stockAmount)); editingQuantity = false
            }, enabled = enabled && parsed != null && parsed.signum() >= 0) { Text("Save quantity") } })
    }
}

@Composable
private fun ShoppingConflict(change: ShoppingChange, busy: Boolean, onServer: () -> Unit, onMerge: (JsonObject) -> Unit) {
    Text("Conflict review", style = MaterialTheme.typography.headlineMedium)
    Text(change.detail ?: "The server and queued changes need review. No request will be resent automatically.")
    fun fields(payload: String?) = payload?.let { Json.parseToJsonElement(it) as? JsonObject }?.let { "Quantity: ${it.shoppingText("amount")} · note: ${it.shoppingText("note")} · completed: ${it.shoppingText("done")}" } ?: "Item missing or outcome unavailable"
    Text("Original: ${fields(change.baseline)}")
    Text("Your change: ${fields(change.desired)}")
    Text("In Grocy: ${fields(change.observed)}")
    PrimaryButton(onClick = onServer, enabled = !busy) { Text("Keep server version") }
    val observed = change.observed?.let { Json.parseToJsonElement(it) as? JsonObject }
    if (change.kind == "edit" && change.state == "conflict" && observed != null) {
        SecondaryButton(onClick = { onMerge(observed) }, enabled = !busy) { Text("Review a merged edit") }
    }
    if (change.kind == "purchase") Text("Purchases are never resent from conflict review. A reserved or completed purchase keeps its receipt to prevent duplicates.")
}
