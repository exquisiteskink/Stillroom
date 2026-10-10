package app.stillroom.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import app.stillroom.R
import app.stillroom.domain.*
import app.stillroom.fractions.QuantityFractions
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

/** Input parsing only; display goes through the shared quantity formatter. */
private val fractions = QuantityFractions()

/** Negative journal amounts keep their leading minus; the shared formatter handles the sign. */
@Composable @ReadOnlyComposable
private fun quantity(value: BigDecimal): String = quantityText(value)

@Composable
fun StockScreen(model: StockViewModel, grants: Set<String>?, barcodeOnly: Boolean = false, scannerActions: Boolean = false, searchAll: Boolean = false) {
    val state by model.state.collectAsState()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(if(searchAll) "All" else "In stock") }
    var location by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { if (!state.denied) model.refresh() }
    if (state.denied || !StockAccess.canRead(grants)) { PermissionDeniedState(); return }
    ProductEditorHost(state, model)
    val selected = state.selected
    androidx.activity.compose.BackHandler(enabled=selected!=null) { if(!state.busy) model.select(null) }
    if (selected != null) {
        KitchenList(state.busy, model::refresh) {
            item { QuietButton(onClick = { model.select(null) }) { Text("Back to pantry") } }
            item { key(selected) { StockDetail(state, selected, model, grants, scannerActions) } }
        }
        return
    }
    val tabs = listOf("All", "In stock", "Use soon", "Running low", "Opened")
    val barcodeIds = state.rows("/objects/product_barcodes").filter { it.text("barcode") == query }.map { it.text("product_id") }.toSet()
    val volatile = state.resources["/stock/volatile"] as? JsonObject
    val useSoonIds = pantryUseSoonIds(volatile)
    val runningLowIds = pantryRunningLowIds(volatile)
    val attentionIds = when (filter) {
        "Use soon" -> useSoonIds
        "Running low" -> runningLowIds
        else -> null
    }
    val stock = state.rows("/stock")
    val catalog = state.rows("/objects/products")
    val today = remember { LocalDate.now() }
    val locale = remember { Locale.getDefault() }
    val searching = barcodeOnly || query.isNotBlank()
    // Settings → Stock → Shown details. Built once per state change, not per row.
    val details = remember(state.detailSettings, state.resources["/objects/userfields"]) { state.stockDetails() }
    val lookups = remember(stock, state.resources["/objects/locations"], state.resources["/objects/product_groups"], state.resources["/objects/product_barcodes"]) {
        StockRowLookups(stock, state.rows("/objects/locations"), state.rows("/objects/product_groups"), state.rows("/objects/product_barcodes"))
    }
    val products = catalog.filter { product ->
        val id = product.text("id")
        val row = stock.find { it.text("product_id") == id }
        val matches = if (barcodeOnly) query.isNotEmpty() && id in barcodeIds else query.isBlank() || product.text("name").contains(query, true) || id in barcodeIds
        matches && (filter != "In stock" || query.isNotBlank() || (product.text("hide_on_stock_overview") != "1" && product.text("active") != "0")) && (location == null || state.rows("/stock/locations/$location/entries").any { it.text("product_id") == id }) && when {
            attentionIds != null -> id in attentionIds
            filter == "In stock" -> (row?.decimal("amount") ?: BigDecimal.ZERO).signum() > 0
            filter == "Opened" -> (row?.decimal("amount_opened") ?: BigDecimal.ZERO).signum() > 0
            else -> true
        }
    }
    fun labeled(tab: String) = when (tab) {
        "Use soon" -> if (useSoonIds.isEmpty()) tab else "$tab · ${useSoonIds.size}"
        "Running low" -> if (runningLowIds.isEmpty()) tab else "$tab · ${runningLowIds.size}"
        else -> tab
    }
    KitchenList(state.busy, model::refresh, spacedBy = 0.dp) {
        item {
            Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                KitchenWhisper(if (state.stale) "Showing last synced stock." else null)
                KitchenError(state.error)
                LabeledTextField(query, { query = it }, label = if (barcodeOnly) "Barcode" else "Search products", modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tabs.forEach { tab -> FilterChip(filter == tab, { filter = tab }, { Text(labeled(tab)) }) }
                }
                if (!barcodeOnly && CatalogEntity.Products.writable(grants)) PrimaryButton(onClick = { model.openProductEditor(null) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Add product") }
                if (ShoppingAccess.allowed(grants) && filter == "Use soon" && useSoonIds.isNotEmpty()) SecondaryButton(onClick = { model.openPantryShop("use-soon") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Add use soon to a shopping list") }
                if (ShoppingAccess.allowed(grants) && filter == "Running low" && runningLowIds.isNotEmpty()) SecondaryButton(onClick = { model.openPantryShop("running-low") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Add running low to a shopping list") }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuietButton(onClick={filter="Locations"}) { Text("Browse locations") }
                    QuietButton(onClick={filter="Journal"}) { Text("View stock history") }
                }
                KitchenWhisper(state.productMessage)
                location?.let { id ->
                    Text("In " + (state.rows("/objects/locations").find { it.text("id") == id.toString() }?.text("name") ?: "this location"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    QuietButton(onClick = { location = null }) { Text("Show every location") }
                }
            }
        }
        if (filter == "Journal") item { Journal(state, null, model, grants) }
        else if (filter == "Locations") {
            val locations = state.rows("/objects/locations")
            if (locations.isEmpty()) item { KitchenEmpty("No locations yet", "Locations from Grocy will appear here.", R.drawable.empty_pantry) }
            else items(locations, key = { it.text("id") }) { row ->
                KitchenStockRow(row.text("name"), amount = "", onClick = { location = row.text("id").toLong(); filter = "All" })
            }
        } else {
            val showAttention = filter == "In stock" && !searching && location == null && (useSoonIds.isNotEmpty() || runningLowIds.isNotEmpty())
            fun byDue(ids: Set<String>) = catalog.filter { it.text("id") in ids }.sortedBy { product ->
                stock.find { it.text("product_id") == product.text("id") }?.text("best_before_date").orEmpty()
            }
            if (showAttention) {
                if (useSoonIds.isNotEmpty()) {
                    item { KitchenSectionTitle("Use soon", Modifier.padding(top = 8.dp)) }
                    if (ShoppingAccess.allowed(grants)) item { SecondaryButton(onClick = { model.openPantryShop("use-soon") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) { Text("Add use soon to a shopping list") } }
                    items(byDue(useSoonIds), key = { "soon"+it.text("id") }) { product ->
                        PantryProductRow(product, details, lookups, state, location, today, locale) { model.select(product.text("id").toLong()) }
                    }
                }
                if (runningLowIds.isNotEmpty()) {
                    item { KitchenSectionTitle("Running low", Modifier.padding(top = 12.dp)) }
                    if (ShoppingAccess.allowed(grants)) item { SecondaryButton(onClick = { model.openPantryShop("running-low") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) { Text("Add running low to a shopping list") } }
                    items(catalog.filter { it.text("id") in runningLowIds }, key = { "low"+it.text("id") }) { product ->
                        PantryProductRow(product, details, lookups, state, location, today, locale) { model.select(product.text("id").toLong()) }
                    }
                }
                item { KitchenSectionTitle("In the pantry", Modifier.padding(top = 12.dp)) }
            }
            val rest = if (showAttention) products.filter { it.text("id") !in useSoonIds && it.text("id") !in runningLowIds } else products
            if (rest.isEmpty() && !showAttention) item {
                KitchenEmpty(
                    if (barcodeOnly) "No matching barcode"
                    else if (filter == "Use soon") "Nothing to use soon"
                    else if (filter == "Running low") "Nothing is running low"
                    else "Nothing in this view",
                    if (barcodeOnly) "Try another code from the package."
                    else if (filter == "Use soon") "Grocy will list food that is due, overdue, or expired here."
                    else if (filter == "Running low") "Items below Grocy's minimum stock will show here."
                    else "Products from Grocy will show with their amounts here.",
                    R.drawable.empty_pantry,
                )
            } else if (rest.isEmpty() && showAttention) {
                item { Text("Everything else is already listed above.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp)) }
            } else items(rest, key = { it.text("id") }) { product ->
                PantryProductRow(product, details, lookups, state, location, today, locale) { model.select(product.text("id").toLong()) }
            }
        }
    }
    state.shopKind?.let { kind -> PantryShopDialog(kind, state.shopLists, state.busy, model::closePantryShop, model::shopAttention) }
}

@Composable
private fun PantryShopDialog(kind: String, lists: List<Pair<Long, String>>, busy: Boolean, close: () -> Unit, add: (String, Long) -> Unit) {
    var listId by remember(lists) { mutableStateOf(lists.firstOrNull()?.first) }
    val title = if (kind == "running-low") "Add running low" else "Add use soon"
    val detail = if (kind == "running-low") "Grocy adds products that are below their minimum stock. This does not remove them from the pantry."
        else "Grocy adds overdue and expired products. Food that is due, but not overdue yet, is added as a normal shopping row."
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(detail)
            if (lists.isEmpty()) Text("Create a shopping list in Shop first.")
            else ChoiceField("Shopping list", lists, listId, { listId = it }, enabled = !busy)
            Text("If the connection drops after sending, check Pending changes before trying again.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, dismissButton = { QuietButton(onClick = close, enabled = !busy) { Text("Cancel") } }, confirmButton = {
        PrimaryButton(onClick = { listId?.let { add(kind, it) } }, enabled = !busy && listId != null) { Text("Add to list") }
    })
}

@Composable
private fun PantryProductRow(
    product: JsonObject,
    details: StockDetails,
    lookups: StockRowLookups,
    state: StockUiState,
    location: Long?,
    today: LocalDate,
    locale: Locale,
    onClick: () -> Unit,
) {
    val row = lookups.stockByProduct[product.text("id")]
    val amount = if (location == null) row?.decimal("amount") ?: BigDecimal.ZERO else state.rows("/stock/locations/$location/entries").filter { it.text("product_id") == product.text("id") }.fold(BigDecimal.ZERO) { total, entry -> total + entry.decimal("amount") }
    val unit = if (details.shows(StockBuiltIn.Unit)) state.rows("/objects/quantity_units").find { it.text("id") == product.text("qu_id_stock") }?.text("name").orEmpty() else ""
    val due = if (details.shows(StockBuiltIn.DueDate)) pantryDue(row?.text("best_before_date").orEmpty(), today, locale) else null
    val formatter = LocalQuantityFormatter.current
    val extras = stockRowExtras(details, product, lookups, formatter, locale)
    KitchenStockRow(
        name = product.text("name"),
        amount = ((if (details.shows(StockBuiltIn.Amount)) quantity(amount) else "") + " $unit").trim(),
        due = due?.text,
        tone = when (due?.urgency) {
            PantryDueUrgency.Overdue -> ColorTone.Overdue
            PantryDueUrgency.Soon -> ColorTone.Expiring
            else -> ColorTone.Neutral
        },
        details = extras,
        onClick = onClick,
    )
}

@Composable
private fun StockDetail(state: StockUiState, id: Long, model: StockViewModel, grants: Set<String>?, scannerActions: Boolean) {
    val detail = state.resources["/stock/products/$id"] as? JsonObject
    if (detail == null) {
        if (state.busy) Text("Loading product…")
        else ErrorState("Could not load this product.",model::refresh)
        return
    }
    val product = detail["product"] as? JsonObject
    if (product == null) { ErrorState("Could not load this product.", model::refresh); return }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Text(product.text("name"), style = MaterialTheme.typography.titleLarge)
    GrocyMediaPreview("productpictures",product.text("picture_file_name"),true)
    KitchenError(state.error)
    if(state.busy) TaskProgress()
    if(product.text("description").isNotBlank()) Text(product.text("description"))
    if (CatalogEntity.Products.writable(grants)) SecondaryButton(onClick = { model.openProductEditor(id) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Edit product") }
    else Text("Editing products needs Grocy's master-data permission (MASTER_DATA_EDIT) for this account.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    KitchenWhisper(state.productMessage)
    Text("Stock ${quantity(detail.decimal("stock_amount"))} · opened ${quantity(detail.decimal("stock_amount_opened"))} · due ${detail.text("next_due_date")}")
    StockForm(state, product, model::book, model::clearBookingOutcome, grants, scannerActions)
    var showDetails by remember(id) { mutableStateOf(false) }
    QuietButton(onClick={showDetails=!showDetails}) { Text(if(showDetails) "Hide product details" else "Show product details") }
    if(showDetails) {
    if (product.text("parent_product_id").isNotBlank()) Text("Parent product: ${product.text("parent_product_id")}")
    val overview = state.rows("/stock").find { it.text("product_id") == id.toString() }
    overview?.let { Text("Including subproducts: ${quantity(it.decimal("amount_aggregated"))}") }
    Text("Stock unit: ${detail["quantity_unit_stock"]?.jsonObject?.text("name")}")
    Text("Current price: ${detail.text("current_price")} · last: ${detail.text("last_price")} · average: ${detail.text("avg_price")}")
    Text("Barcodes: " + state.rows("/objects/product_barcodes").filter { it.text("product_id") == id.toString() }.joinToString { it.text("barcode") })
    Text("Locations", style = MaterialTheme.typography.titleMedium)
    state.rows("/stock/products/$id/locations").forEach { row ->
        val name = state.rows("/objects/locations").find { it.text("id") == row.text("location_id") }?.text("name") ?: row.text("location_name")
        Text("$name · ${quantity(row.decimal("amount"))}")
    }
    Text("Stock entries", style = MaterialTheme.typography.titleMedium)
    state.rows("/stock/products/$id/entries").forEach { row -> Text("${quantity(row.decimal("amount"))} · location ${row.text("location_id")} · due ${row.text("best_before_date")} · opened ${row.text("open")} · price ${row.text("price")}") }
    Text("Price history", style = MaterialTheme.typography.titleMedium)
    state.rows("/stock/products/$id/price-history").forEach { row -> Text("${row.text("date")} ${row.text("purchased_date")} · ${row.text("price")} · store ${(row["shopping_location"] as? JsonObject)?.text("name").orEmpty()}") }
    Journal(state, id, model, grants)
    }
    }
}

@Composable internal fun StockJournalEntry(row: JsonObject, productName: String, locationName: String) {
    val action=when(row.text("transaction_type")) { "purchase"->"Added";"consume"->if(row.text("spoiled")=="1") "Spoiled" else "Used";"inventory"->"Counted";"transfer"->"Moved";"product-opened"->"Opened";else->"Changed" }
    Column(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(productName,style=MaterialTheme.typography.titleMedium)
        Text("$action · ${quantity(row.decimal("amount"))}" + if(locationName.isBlank()) "" else " · $locationName")
        Text(row.text("row_created_timestamp"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(row.text("undone")=="1") Text("Undone",style=MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun Journal(state: StockUiState, product: Long?, model: StockViewModel, grants: Set<String>?) {
    Text("Stock history", style = MaterialTheme.typography.titleMedium)
    val paths = (state.resources["/openapi/specification"] as? JsonObject)?.get("paths") as? JsonObject
    val undoSupported = paths?.containsKey("/stock/bookings/{bookingId}/undo") == true && !state.stale
    val transactionUndoSupported = paths?.containsKey("/stock/transactions/{transactionId}/undo") == true && !state.stale
    val transactionsShown = mutableSetOf<String>()
    state.rows("/objects/stock_log").filter { product == null || it.text("product_id") == product.toString() }.asReversed().forEach { row ->
        val productName = state.rows("/objects/products").find { it.text("id") == row.text("product_id") }?.text("name") ?: row.text("product_id")
        val locationName = state.rows("/objects/locations").find { it.text("id") == row.text("location_id") }?.text("name").orEmpty()
        StockJournalEntry(row, productName, locationName)
        if (row.text("note").isNotBlank()) Text(row.text("note"))
        if (StockAccess.canRead(grants) && row.text("undone") != "1" && row.text("transaction_type") != "stock-edit") {
            val transaction = row.text("transaction_id")
            if (transactionUndoSupported && transaction.isNotBlank()) {
                if (transactionsShown.add(transaction)) QuietButton(onClick = { model.undoTransaction(transaction) }, enabled = !state.busy) { Text("Undo transaction $transaction") }
            } else if (undoSupported) {
                QuietButton(onClick = { model.undo(row.text("id").toLong()) }, enabled = !state.busy) { Text("Undo booking ${row.text("id")}") }
            }
        }
    }
}

@Composable
internal fun StockForm(state: StockUiState, product: JsonObject, book: (StockBooking) -> Unit, clearBookingOutcome: () -> Unit, grants: Set<String>?, scannerActions: Boolean, scanAction:StockAction?=null,barcode:BarcodeMetadata?=null,stockEntryId:String?=null,submitLabel:String?=null) {
    var chosenAction by remember(product.text("id"), scannerActions) {
        mutableStateOf(if (!scannerActions && StockAccess.canWrite(grants, StockAction.Consume)) StockAction.Consume else StockAction.entries.firstOrNull { StockAccess.canWrite(grants, it) } ?: StockAction.Purchase)
    }
    val action=scanAction ?: chosenAction
    val unresolved=state.operations.firstOrNull { it.path=="/stock/products/${product.text("id")}/${action.endpoint}" && it.state in setOf("pending","guarded","in-flight","needs-review") }
    val boundOperation=state.bookingOperation ?: unresolved?.clientOperationId
    val submitted = boundOperation != null
    val enabled = !state.busy && !submitted
    var amount by remember { mutableStateOf(if(stockEntryId!=null) "1" else barcode?.amount ?: "1") }
    var unit by remember { mutableStateOf(if(stockEntryId!=null)null else barcode?.unit) }
    var location by remember { mutableStateOf<Long?>(null) }
    var destination by remember { mutableStateOf<Long?>(null) }
    var date by remember { mutableStateOf("") }
    var price by remember { mutableStateOf(barcode?.price.orEmpty()) }
    var store by remember { mutableStateOf(barcode?.store) }
    var totalPrice by remember { mutableStateOf(barcode?.price!=null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showPrice by remember { mutableStateOf(barcode?.price!=null) }
    if(scanAction==null) ChoiceField("Stock action",StockAction.entries.filter { StockAccess.canWrite(grants,it) && (!scannerActions || it in setOf(StockAction.Purchase,StockAction.Consume)) }.map { it.ordinal.toLong() to stockActionLabel(it) },chosenAction.ordinal.toLong(),{ selected-> selected?.let { chosenAction=StockAction.entries[it.toInt()] } },enabled=enabled)
    val stockUnit = product.text("qu_id_stock").toLong()
    val conversions = state.rows("/objects/quantity_unit_conversions_resolved").filter { it.text("product_id") == product.text("id") && it.text("to_qu_id") == stockUnit.toString() }
    val selectedUnit = unit ?: stockUnit
    val labeledEntry=stockEntryId?.takeIf { action==StockAction.Consume }
    if(labeledEntry!=null)Text("Using stock entry $labeledEntry. Quantity must be one entry.")
    val factor = if (selectedUnit == stockUnit) BigDecimal.ONE else conversions.find { it.text("from_qu_id") == selectedUnit.toString() }?.decimal("factor")
    ChoiceField("Quantity unit",state.rows("/objects/quantity_units").filter { it.text("id") == stockUnit.toString() || conversions.any { conversion -> conversion.text("from_qu_id") == it.text("id") } }.map { it.text("id").toLong() to it.text("name") },selectedUnit,{unit=it},enabled=enabled)
    val parsedQuantity = fractions.parse(amount, Locale.getDefault())
    val quantityError=if(parsedQuantity==null || (parsedQuantity.value.signum()<=0 && !(action==StockAction.Inventory && parsedQuantity.value.signum()==0))) "Enter a positive quantity" else null
    val purchaseFields=action==StockAction.Purchase || action==StockAction.Inventory
    val dueDates=LocalServerCapabilities.current.enabled("STOCK_BEST_BEFORE_DATE_TRACKING")
    val prices=LocalServerCapabilities.current.enabled("STOCK_PRICE_TRACKING")
    val dateError=if(!purchaseFields || !dueDates) null else if(date.isNotBlank() && runCatching { LocalDate.parse(date) }.isFailure) "Use YYYY-MM-DD" else if(scannerActions && action==StockAction.Purchase && date.isBlank()) "Enter the package due date" else null
    val tare=if(product.text("enable_tare_weight_handling")=="1")product.decimal("tare_weight") ?: BigDecimal.ZERO else BigDecimal.ZERO
    val reviewedPrice=if(price.isNotBlank() && purchaseFields && prices)runCatching { reviewedStockPrice(price.toBigDecimal(),parsedQuantity!!.value,factor!!,totalPrice,tare) }.getOrNull() else null
    val priceError=if(!purchaseFields || !prices || price.isBlank())null else if(price.toBigDecimalOrNull()?.signum()?.let { it>=0 }!=true)"Enter a price of zero or more" else if(reviewedPrice==null)"Enter a valid quantity above the tare weight." else null
    LabeledTextField(amount, { amount = it;error=null }, label = if (action == StockAction.Inventory) "New total quantity" else "Quantity",supportingText=quantityError ?: "Decimals and fractions accepted",isError=quantityError!=null,enabled=enabled,modifier=Modifier.fillMaxWidth())
    parsedQuantity?.let { Text("Input: ${quantity(it.value)} · stock quantity: ${quantity(it.value.multiply(factor ?: BigDecimal.ONE))}") }
    val locationChoices=state.rows("/objects/locations").map { it.text("id").toLong() to it.text("name") }
    if (action != StockAction.Open) {
        ChoiceField(if(action==StockAction.Transfer) "From location (required)" else "Location",locationChoices,location,{location=it},allowNone=action!=StockAction.Transfer,noneLabel="Product default",enabled=enabled)
    }
    if (action == StockAction.Transfer) ChoiceField("To location (required)",locationChoices,destination,{destination=it},enabled=enabled)
    if (action == StockAction.Purchase || action == StockAction.Inventory) {
        if(barcode?.store!=null)ChoiceField("Store",state.rows("/objects/shopping_locations").map { it.text("id").toLong() to it.text("name") },store,{store=it},allowNone=true,enabled=enabled)
        if (scannerActions && dueDates) Text("Read the due date from the package.")
        if(dueDates)LabeledTextField(date, { date = it;error=null }, label = if(scannerActions) "Due date (required)" else "Due date (optional)",supportingText=dateError ?: "YYYY-MM-DD",isError=date.isNotBlank() && dateError!=null,enabled=enabled,modifier=Modifier.fillMaxWidth())
        if(scannerActions && prices) QuietButton(onClick={showPrice=!showPrice},enabled=enabled) { Text(if(showPrice) "Hide price" else "Add price") }
        if(prices && (!scannerActions || showPrice)) {
            if(barcode!=null)ChoiceField("Price type",listOf(0L to "Price per stock unit",1L to "Total purchase price"),if(totalPrice)1L else 0L,{totalPrice=it==1L},enabled=enabled)
            LabeledTextField(price, { price = it;error=null }, label = if(totalPrice)"Total purchase price (optional)" else "Price per stock unit (optional)",supportingText=priceError,isError=priceError!=null,enabled=enabled,modifier=Modifier.fillMaxWidth())
            if(totalPrice && reviewedPrice!=null)Text("Price per stock unit: ${reviewedPrice.stripTrailingZeros().toPlainString()}",style=MaterialTheme.typography.bodySmall)
        }
    }
    if (product.text("enable_tare_weight_handling") == "1" && action != StockAction.Consume && action != StockAction.Spoilage && action != StockAction.Open) Text("Tare handling: purchase/inventory quantity is gross weight. Grocy calculates net stock. Transfer is unavailable.")
    if(action==StockAction.Transfer && (location==null || destination==null || location==destination)) Text("Choose two different locations.",color=MaterialTheme.colorScheme.onSurfaceVariant)
    error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    scanBookingOutcome(boundOperation,state.operations)?.let { Text(it,modifier=Modifier.semantics { liveRegion=androidx.compose.ui.semantics.LiveRegionMode.Polite }) }
    if(!scannerActions && state.operations.any { it.clientOperationId==state.bookingOperation && it.state=="confirmed" }) QuietButton(onClick=clearBookingOutcome,enabled=!state.busy) { Text("Record another change") }
    if(scannerActions && dueDates && action==StockAction.Purchase && runCatching { LocalDate.parse(date) }.isFailure) Text("Enter the package due date to add stock.",color=MaterialTheme.colorScheme.onSurfaceVariant)
    PrimaryButton(onClick = {
        try {
            val parsed = fractions.parse(amount, Locale.getDefault()) ?: error("Invalid quantity")
            val booking = StockBooking(action, product.text("id").toLong(), parsed.value, factor ?: error("No unit conversion"), location, destination, date.takeIf { purchaseFields && dueDates && it.isNotBlank() }, reviewedPrice,note=barcode?.note?.takeIf { it.isNotBlank() },store=store.takeIf { purchaseFields },stockEntryId=labeledEntry,reviewedStockUnit=stockUnit)
            booking.payload(); error = null; book(booking)
        } catch (_: Exception) { error = "Enter a valid quantity, decimal price, date, and required locations." }
    }, enabled = enabled && quantityError==null && priceError==null && dateError==null && (action!=StockAction.Transfer || (location!=null && destination!=null && location!=destination)) && factor != null && (!scannerActions || action != StockAction.Purchase || !dueDates || runCatching { java.time.LocalDate.parse(date) }.isSuccess) && (labeledEntry==null || parsedQuantity?.value?.multiply(factor ?: BigDecimal.ONE)?.compareTo(BigDecimal.ONE)==0) && LocalServerCapabilities.current.allows("/stock/products/${product.text("id")}/${action.endpoint}") && StockAccess.canWrite(grants, action) && !(action == StockAction.Transfer && product.text("enable_tare_weight_handling") == "1") && !(action == StockAction.Open && product.text("disable_open") == "1"), modifier=Modifier.fillMaxWidth()) { Text(if(state.busy) "Saving…" else if(submitLabel!=null)submitLabel else if(scannerActions) if(action==StockAction.Purchase) "Confirm add" else "Confirm use" else stockActionLabel(action)) }
}

internal fun stockActionLabel(action:StockAction):String = when(action) {
    StockAction.Purchase->"Add stock"
    StockAction.Consume->"Use stock"
    StockAction.Open->"Mark opened"
    StockAction.Transfer->"Move stock"
    StockAction.Inventory->"Set stock total"
    StockAction.Spoilage->"Record spoilage"
}

@Composable
internal fun ScannerStockReview(state:StockUiState,id:Long,model:StockViewModel,grants:Set<String>?,action:StockAction,barcode:BarcodeMetadata?=null,stockEntryId:String?=null,draftPurchase:((StockBooking)->Unit)?=null) {
    val detail=state.resources["/stock/products/$id"] as? JsonObject
    val product=(detail?.get("product") as? JsonObject)
    if(product==null || state.selected!=id) {
        if(state.busy) Text("Loading product…") else ErrorState("Could not load this product.",model::refresh)
        return
    }
    Text(product.text("name"),style=MaterialTheme.typography.titleLarge)
    GrocyMediaPreview("productpictures",product.text("picture_file_name"),true)
    Text("In stock: ${quantity(detail.decimal("stock_amount"))} ${detail["quantity_unit_stock"]?.jsonObject?.text("name").orEmpty()}")
    KitchenWhisper(if(state.stale) "Showing last synced stock." else null)
    KitchenError(state.error)
    key(id,action,barcode,stockEntryId) {
        val formState=if(draftPurchase!=null)state.copy(bookingOperation=null,operations=emptyList()) else state
        StockForm(formState,product,draftPurchase ?: model::book,model::clearBookingOutcome,grants,true,action,barcode,stockEntryId,if(draftPurchase!=null)"Add to trip review" else null)
    }
}

/** Pantry → Add product / Edit product, using the shared record editor. */
@Composable
private fun ProductEditorHost(state: StockUiState, model: StockViewModel) {
    val target = state.productEditor ?: return
    val catalog = state.catalog
    val row = target.id?.let { id -> catalog?.rows("products")?.find { it.text("id") == id.toString() }
        // After a partial create the product exists even if the refreshed list did not arrive: keep the entries.
        ?: (state.productOutcome as? CatalogSaveOutcome.Partial)?.takeIf { it.id == id }?.let { buildJsonObject { put("id", id) } } }
    if (catalog == null || (target.id != null && row == null)) {
        AlertDialog(onDismissRequest = {}, title = { Text(if (target.id == null) "Add product" else "Edit product") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.busy) { TaskProgress(); Text("Loading product data from Grocy…") }
                else Text(state.error ?: "This product could not be loaded from Grocy.", color = MaterialTheme.colorScheme.error)
            } },
            dismissButton = { QuietButton(onClick = model::closeProductEditor, enabled = !state.busy) { Text("Close") } },
            confirmButton = {})
        return
    }
    // No key: after a partial create the same editor continues on the new product with the user's entries.
    CatalogEditor(CatalogEntity.Products, row, catalog, state.busy, state.productOutcome, model::closeProductEditor, model::saveProduct)
}
