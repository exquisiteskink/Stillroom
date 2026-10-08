package app.stillroom.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import app.stillroom.domain.*
import app.stillroom.fractions.QuantityFractions
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.util.Locale

@Composable
internal fun ShoppingItemForm(listId: Long, snapshot: ShoppingSnapshot, baseline: JsonObject?, seed: String?, busy: Boolean, onBack: () -> Unit, onSave: (ShoppingDraft) -> Unit) {
    val quantities=LocalQuantityFormatter.current
    val initial = seed?.let { Json.parseToJsonElement(it).jsonObject } ?: baseline
    var productId by remember { mutableStateOf(initial?.shoppingText("product_id").orEmpty()) }
    var unit by remember { mutableStateOf(initial?.shoppingText("qu_id").orEmpty()) }
    var note by remember { mutableStateOf(initial?.shoppingText("note").orEmpty()) }
    val initialFactor = remember { snapshot.factor(productId, unit) ?: BigDecimal.ONE }
    val originalAmount = remember { initial?.shoppingDecimal("amount") ?: BigDecimal.ONE }
    var amount by remember { mutableStateOf(quantities.format(originalAmount.divide(initialFactor, MathContext.DECIMAL128), Locale.getDefault())) }
    var amountChanged by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Text(if (seed != null) "Review merged edit" else if (baseline == null) "Add item" else "Edit item", style = MaterialTheme.typography.headlineMedium)
    QuietButton(onClick = onBack) { Text("Back to list") }
    ChoiceField("Product", snapshot.rows("products").map { it.shoppingText("id").toLong() to it.shoppingText("name") }, productId.toLongOrNull(), { id ->
        productId = id?.toString().orEmpty()
        unit = snapshot.rows("products").firstOrNull { it.shoppingText("id") == productId }?.shoppingText("qu_id_stock").orEmpty()
    }, allowNone = true, noneLabel = "Note only", enabled = !busy)
    if (productId.isNotBlank()) {
        ChoiceField("Quantity unit", snapshot.rows("quantity_units").filter { snapshot.factor(productId, it.shoppingText("id")) != null }.map { it.shoppingText("id").toLong() to it.shoppingText("name") }, unit.toLongOrNull(), { unit = it?.toString().orEmpty() }, enabled = !busy)
    }
    val quantityValid = QuantityFractions().parse(amount, Locale.getDefault())?.value?.signum()?.let { it >= 0 } == true
    LabeledTextField(note, { note = it }, label = "Notes", modifier = Modifier.fillMaxWidth())
    LabeledTextField(amount, { amount = it; amountChanged = true }, label = "Quantity (fractions accepted)", isError = !quantityValid, supportingText = if (!quantityValid) "Enter a quantity of zero or more." else null)
    error?.let { Text(it) }
    PrimaryButton(onClick = {
        try {
            val parsed = QuantityFractions().parse(amount, Locale.getDefault()) ?: error("Quantity")
            val factor = if (productId.isBlank()) BigDecimal.ONE else snapshot.factor(productId, unit) ?: error("Conversion")
            val storedAmount = when {
                !amountChanged && factor.compareTo(initialFactor) == 0 -> originalAmount
                !amountChanged -> originalAmount.multiply(factor).divide(initialFactor, MathContext.DECIMAL128)
                else -> parsed.value.multiply(factor)
            }
            val draft = ShoppingDraft(listId, productId.toLongOrNull(), note, storedAmount, unit.toLongOrNull(), initial?.shoppingText("done") == "1")
            draft.payload(); onSave(draft)
        } catch (_: Exception) { error = "Enter a valid quantity, unit, and product or note." }
    }, enabled = !busy && quantityValid && (productId.isNotBlank() || note.isNotBlank())) { Text("Save item") }
}

@Composable
internal fun PurchaseFromListReview(row: JsonObject, snapshot: ShoppingSnapshot, busy: Boolean, onBack: () -> Unit, onConfirm: (StockBooking) -> Unit) {
    val quantities=LocalQuantityFormatter.current
    val product = snapshot.rows("products").find { it.shoppingText("id") == row.shoppingText("product_id") }
    if (product == null) {
        Text("This product is unavailable. Return to the list and refresh.")
        QuietButton(onClick = onBack) { Text("Back to list") }
        return
    }
    val quantityInput = remember { RecipeAmountInput(row.shoppingDecimal("amount") ?: BigDecimal.ZERO,Locale.getDefault(),quantities) }
    var amount by remember { mutableStateOf(quantityInput.text) }
    var price by remember { mutableStateOf(snapshot.price(row.shoppingText("product_id"))?.toPlainString().orEmpty()) }
    var date by remember { mutableStateOf("") }
    var location by remember { mutableStateOf(product.shoppingText("location_id").toLongOrNull()) }
    var store by remember { mutableStateOf(product.shoppingText("shopping_location_id").toLongOrNull()) }
    var note by remember { mutableStateOf(row.shoppingText("note")) }
    var error by remember { mutableStateOf<String?>(null) }
    val stockUnit = snapshot.rows("quantity_units").find { it.shoppingText("id") == product.shoppingText("qu_id_stock") }?.shoppingText("name").orEmpty()
    Text("Review stock purchase", style = MaterialTheme.typography.headlineMedium)
    Text(product.shoppingText("name"), style = MaterialTheme.typography.titleLarge)
    Text("This purchase adds stock through Grocy and marks the item completed only after confirmation. Review the quantity, stock unit, due date, price, store, and location.")
    QuietButton(onClick = onBack, enabled = !busy) { Text("Back without purchasing") }
    val amountValid = runCatching { quantityInput.saved().signum() > 0 }.getOrDefault(false)
    val dateValid = date.isBlank() || runCatching { LocalDate.parse(date) }.isSuccess
    val priceValid = price.isBlank() || price.toBigDecimalOrNull()?.signum()?.let { it >= 0 } == true
    LabeledTextField(amount, { amount = it; quantityInput.change(it) }, label = "Actual quantity in $stockUnit", isError = !amountValid, supportingText = if (!amountValid) "Enter a quantity greater than zero." else null)
    if (product.shoppingText("enable_tare_weight_handling") == "1") Text("Enter gross weight including the container. Grocy calculates net stock.")
    LabeledTextField(price, { price = it }, label = "Price per stock unit (optional)", isError = !priceValid, supportingText = if (!priceValid) "Enter a decimal price of zero or more." else null)
    LabeledTextField(date, { date = it }, label = "Due date (optional)", isError = !dateValid, supportingText = if (!dateValid) "Enter a date as YYYY-MM-DD." else "Use the date printed on the package.")
    LabeledTextField(note, { note = it }, label = "Purchase note")
    ChoiceField("Stock location", snapshot.rows("locations").map { it.shoppingText("id").toLong() to it.shoppingText("name") }, location, { location = it }, allowNone = true, noneLabel = "Product default", enabled = !busy)
    ChoiceField("Store", snapshot.rows("shopping_locations").map { it.shoppingText("id").toLong() to it.shoppingText("name") }, store, { store = it }, allowNone = true, noneLabel = "No store", enabled = !busy)
    error?.let { Text(it) }
    PrimaryButton(onClick = {
        try {
            val quantity = quantityInput.saved()
            require(note.length <= 4000)
            val booking = StockBooking(StockAction.Purchase, row.shoppingText("product_id").toLong(), quantity, location = location, date = date.takeIf { it.isNotBlank() }, price = price.takeIf { it.isNotBlank() }?.toBigDecimal(), note = note, store = store)
            booking.payload(); onConfirm(booking)
        } catch (_: Exception) { error = "Enter a valid positive quantity, date, and decimal price." }
    }, enabled = !busy && amountValid && dateValid && priceValid && note.length <= 4000) { Text("Add stock") }
}
