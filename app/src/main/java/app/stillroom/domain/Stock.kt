package app.stillroom.domain

import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

object StockAccess {
    fun canRead(grants: Set<String>?) = grants?.any { it == "ADMIN" || it == "STOCK" } == true
    fun canWrite(grants: Set<String>?, action: StockAction) = canRead(grants) || grants?.contains(action.permission) == true
}
enum class StockAction(val endpoint: String, val permission: String) {
    Purchase("add", "STOCK_PURCHASE"), Consume("consume", "STOCK_CONSUME"),
    Open("open", "STOCK_OPEN"), Transfer("transfer", "STOCK_TRANSFER"),
    Inventory("inventory", "STOCK_INVENTORY"), Spoilage("consume", "STOCK_CONSUME")
}

/** Amount and price are already decimal values. Presentation fractions never enter storage. */
data class StockBooking(
    val action: StockAction, val productId: Long, val amount: BigDecimal,
    val factor: BigDecimal = BigDecimal.ONE, val location: Long? = null,
    val destination: Long? = null, val date: String? = null, val price: BigDecimal? = null,
    val note: String? = null, val store: Long? = null, val recipeId: Long? = null,
) {
    fun path(): String { require(productId > 0); return "/stock/products/$productId/${action.endpoint}" }
    fun payload(): JsonObject {
        require(productId > 0 && factor.signum() > 0)
        require(amount.signum() > 0 || (action == StockAction.Inventory && amount.signum() == 0))
        require(location == null || location > 0)
        require(price == null || price.signum() >= 0)
        require(store == null || store > 0)
        require(recipeId == null || (recipeId > 0 && action == StockAction.Consume))
        require(note == null || note.length <= 5000)
        date?.let { require(runCatching { LocalDate.parse(it) }.isSuccess) { "Invalid due date." } }
        if (action == StockAction.Transfer) require(location != null && destination != null && destination > 0 && location != destination)
        return buildJsonObject {
            put(if (action == StockAction.Inventory) "new_amount" else "amount", Json.parseToJsonElement(amount.multiply(factor).toPlainString()))
            when (action) {
                StockAction.Purchase -> put("transaction_type", "purchase")
                StockAction.Consume, StockAction.Spoilage -> { put("transaction_type", "consume"); put("spoiled", action == StockAction.Spoilage); put("exact_amount", true) }
                StockAction.Transfer -> { put("location_id_from", location!!); put("location_id_to", destination!!) }
                else -> Unit
            }
            recipeId?.let { put("recipe_id", it) }
            if (action != StockAction.Transfer && action != StockAction.Open) location?.let { put("location_id", it) }
            if (action == StockAction.Purchase || action == StockAction.Inventory) {
                date?.let { put("best_before_date", it) }
                note?.let { put("note", it) }
                store?.let { put("shopping_location_id", it) }
                price?.let { put("price", Json.parseToJsonElement(it.toPlainString())) }
            }
        }
    }
}

interface StockRepository {
    suspend fun read(path: String): StockRead
    suspend fun book(booking: StockBooking, operationId: String? = null, guarded: Boolean = false): String
    suspend fun undo(bookingId: Long): String
    suspend fun undoTransaction(transactionId: String): String
    suspend fun operations(): List<PendingChange>
}
data class StockRead(val value: JsonElement, val stale: Boolean)
enum class PantryDueUrgency { None, Soon, Overdue }

data class PantryDueLabel(val text: String, val urgency: PantryDueUrgency)

fun pantryVolatileProductIds(volatile: JsonObject?, vararg keys: String): Set<String> {
    if (volatile == null) return emptySet()
    return keys.flatMap { key ->
        (volatile[key] as? JsonArray)?.mapNotNull { element ->
            ((element as? JsonObject)?.get("product_id") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }.orEmpty()
    }.toSet()
}

fun pantryUseSoonIds(volatile: JsonObject?): Set<String> =
    pantryVolatileProductIds(volatile, "due_products", "overdue_products", "expired_products")

fun pantryRunningLowIds(volatile: JsonObject?): Set<String> =
    pantryVolatileProductIds(volatile, "missing_products")

/** Formats Grocy best-before dates for a compact pantry row. Far-future sentinels such as 2999-12-31 are omitted. */
fun pantryDue(raw: String, today: LocalDate, locale: java.util.Locale): PantryDueLabel? {
    if (raw.isBlank()) return null
    val date = runCatching { LocalDate.parse(raw.take(10)) }.getOrNull()
        ?: return PantryDueLabel("use by $raw", PantryDueUrgency.Soon)
    if (date.year >= 2099) return null
    val text = date.format(java.time.format.DateTimeFormatter.ofPattern("MMM d", locale))
    val urgency = when {
        date.isBefore(today) -> PantryDueUrgency.Overdue
        !date.isAfter(today.plusDays(3)) -> PantryDueUrgency.Soon
        else -> PantryDueUrgency.None
    }
    return PantryDueLabel(text, urgency)
}

class ManageStock(private val repository: StockRepository) {
    suspend fun read(path: String) = repository.read(path)
    suspend fun book(booking: StockBooking, operationId: String? = null, guarded: Boolean = false) = repository.book(booking, operationId, guarded)
    suspend fun undo(id: Long) = repository.undo(id)
    suspend fun undoTransaction(id: String) = repository.undoTransaction(id)
    suspend fun operations() = repository.operations()
}
