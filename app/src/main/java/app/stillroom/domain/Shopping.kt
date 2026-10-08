package app.stillroom.domain

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.serialization.json.*

object ShoppingAccess {
    fun allowed(grants: Set<String>?) = grants?.any { it == "ADMIN" || it == "SHOPPINGLIST" } == true
}
data class ShoppingDraft(val listId: Long, val productId: Long?, val note: String, val amount: BigDecimal, val unitId: Long? = null, val done: Boolean = false) {
    fun payload(): JsonObject {
        require(listId > 0 && (productId == null || productId > 0) && (unitId == null || unitId > 0))
        require(amount.signum() >= 0 && note.length <= 4000 && (productId != null || note.isNotBlank()))
        return buildJsonObject {
            put("shopping_list_id", listId); put("product_id", productId?.let { JsonPrimitive(it) } ?: JsonNull)
            put("note", note); put("amount", Json.parseToJsonElement(amount.toPlainString()))
            put("qu_id", unitId?.let { JsonPrimitive(it) } ?: JsonNull); put("done", if (done) 1 else 0)
        }
    }
}
fun JsonObject.shoppingText(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
fun JsonObject.shoppingDecimal(key: String) = shoppingText(key).toBigDecimalOrNull()

/** Claims use the server's UNIQUE primary key, shared by all participating clients. */
object ShoppingClaims {
    const val ENTITY_ID = -1700000007L
    const val ENTITY_NAME = "stillroom_shopping_sync_v1"
    fun id(entity: String, row: JsonObject): Long = hash(entity, row, "row")
    fun purchaseId(row: JsonObject): Long = hash("shopping_list", row, "purchase")
    private fun hash(entity: String, row: JsonObject, purpose: String): Long {
        require(entity in setOf("shopping_list", "shopping_lists"))
        require(row.shoppingText("id").toLong() > 0 && row.shoppingText("row_created_timestamp").isNotBlank())
        val bytes = MessageDigest.getInstance("SHA-256").digest("$ENTITY_NAME/$purpose/$entity/${row.shoppingText("id")}/${row.shoppingText("row_created_timestamp")}".toByteArray(Charsets.UTF_8))
        return -((ByteBuffer.wrap(bytes).long and Long.MAX_VALUE).coerceAtLeast(1))
    }
}

data class ShoppingEstimate(val knownTotal: BigDecimal, val unknownRows: Int)
data class ShoppingSnapshot(val resources: Map<String, JsonArray>, val stale: Boolean) {
    fun rows(entity: String) = resources[entity]?.map { it.jsonObject }.orEmpty()
    fun projected(changes: List<ShoppingChange>): ShoppingSnapshot {
        val rows = rows("shopping_list").toMutableList()
        for (change in changes.filter { it.entity == "shopping_list" && it.state in setOf("pending", "preparing", "dispatching") && it.kind != "purchase" }) {
            val index = rows.indexOfFirst { it.shoppingText("id") == change.rowId?.toString() }
            when (change.kind) {
                "delete" -> if (index >= 0) rows.removeAt(index)
                "edit" -> if (index >= 0) rows[index] = JsonObject(rows[index] + Json.parseToJsonElement(change.desired).jsonObject)
                "add" -> rows.add(JsonObject(Json.parseToJsonElement(change.desired).jsonObject + ("id" to JsonPrimitive("local-${change.id}"))))
            }
        }
        return copy(resources = resources + ("shopping_list" to JsonArray(rows)))
    }
    fun price(productId: String) = rows("products_last_purchased").find { it.shoppingText("product_id") == productId }?.shoppingDecimal("price")
    fun estimate(listId: Long): ShoppingEstimate {
        var total = BigDecimal.ZERO; var unknown = 0
        rows("shopping_list").filter { it.shoppingText("shopping_list_id") == listId.toString() && it.shoppingText("done") != "1" }.forEach {
            val price = price(it.shoppingText("product_id"))
            if (price == null) unknown++ else total += price.multiply(it.shoppingDecimal("amount") ?: BigDecimal.ZERO)
        }
        return ShoppingEstimate(total, unknown)
    }
    fun group(row: JsonObject, byStore: Boolean): String {
        val product = rows("products").find { it.shoppingText("id") == row.shoppingText("product_id") }
        val last = rows("products_last_purchased").find { it.shoppingText("product_id") == row.shoppingText("product_id") }
        val id = if (byStore) product?.shoppingText("shopping_location_id").orEmpty().ifBlank { last?.shoppingText("shopping_location_id").orEmpty() } else product?.shoppingText("product_group_id").orEmpty()
        return rows(if (byStore) "shopping_locations" else "product_groups").find { it.shoppingText("id") == id }?.shoppingText("name") ?: if (byStore) "No known store" else "Uncategorized"
    }
    fun factor(productId: String, unitId: String): BigDecimal? {
        val product = rows("products").find { it.shoppingText("id") == productId } ?: return null
        if (product.shoppingText("qu_id_stock") == unitId) return BigDecimal.ONE
        return rows("quantity_unit_conversions_resolved").find { it.shoppingText("product_id") == productId && it.shoppingText("from_qu_id") == unitId && it.shoppingText("to_qu_id") == product.shoppingText("qu_id_stock") }?.shoppingDecimal("factor")
    }
}
data class ShoppingChange(
    val id: String, val kind: String, val entity: String, val rowId: Long?, val baseline: String?, val desired: String,
    val state: String, val observed: String?, val detail: String?, val claimOwned: Boolean,
)
interface ShoppingRepository {
    suspend fun snapshot(): ShoppingSnapshot
    suspend fun save(draft: ShoppingDraft, baseline: JsonObject? = null): String
    suspend fun delete(baseline: JsonObject): String
    suspend fun createList(name: String): String
    suspend fun purchase(baseline: JsonObject, booking: StockBooking): String
    suspend fun sync()
    suspend fun changes(): List<ShoppingChange>
    suspend fun acceptServer(id: String)
}
class ManageShopping(private val repository: ShoppingRepository) {
    suspend fun snapshot() = repository.snapshot()
    suspend fun save(draft: ShoppingDraft, baseline: JsonObject? = null) = repository.save(draft, baseline)
    suspend fun delete(row: JsonObject) = repository.delete(row)
    suspend fun createList(name: String) = repository.createList(name)
    suspend fun purchase(row: JsonObject, booking: StockBooking) = repository.purchase(row, booking)
    suspend fun sync() = repository.sync()
    suspend fun changes() = repository.changes()
    suspend fun acceptServer(id: String) = repository.acceptServer(id)
}
