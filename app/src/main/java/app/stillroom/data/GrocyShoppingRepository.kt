package app.stillroom.data

import app.stillroom.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.UUID

class GrocyShoppingRepository(
    private val database: AccountDatabase, private val cached: CachedGrocyRepository,
    private val stock: StockRepository, private val grants: Set<String>?,
) : ShoppingRepository {
    private val lock = Mutex()
    private fun access() = check(ShoppingAccess.allowed(grants)) { "Shopping access denied." }
    override suspend fun snapshot(): ShoppingSnapshot {
        access(); val resources = mutableMapOf<String, JsonArray>(); var stale = false
        for (entity in listOf("shopping_lists", "shopping_list", "products", "quantity_units", "quantity_unit_conversions_resolved", "shopping_locations", "product_groups", "products_last_purchased", "locations")) {
            val read = cached.read("/objects/$entity")
            resources[entity] = Json.parseToJsonElement(read.payload).jsonArray; stale = stale || read.stale
        }
        return ShoppingSnapshot(resources, stale)
    }
    private suspend fun queue(kind: String, desired: JsonObject, baseline: JsonObject?, entity: String = "shopping_list"): String = withContext(Dispatchers.IO) {
        access()
        val rowId = baseline?.shoppingText("id")?.toLong()?.also { require(it > 0) }
        baseline?.let { ShoppingClaims.id(entity, it) }
        database.queueShopping(ShoppingOperation(UUID.randomUUID().toString(), kind, entity, rowId, baseline?.toString(), desired.toString()))
    }
    override suspend fun save(draft: ShoppingDraft, baseline: JsonObject?) = queue(if (baseline == null) "add" else "edit", draft.payload(), baseline)
    override suspend fun delete(baseline: JsonObject) = queue("delete", JsonObject(emptyMap()), baseline)
    override suspend fun createList(name: String): String {
        require(name.isNotBlank() && name.length <= 100)
        return queue("add", buildJsonObject { put("name", name) }, null, "shopping_lists")
    }
    override suspend fun purchase(baseline: JsonObject, booking: StockBooking): String {
        access()
        check(StockAccess.canWrite(grants, StockAction.Purchase) && StockAccess.canRead(grants)) { "Stock access denied." }
        require(booking.action == StockAction.Purchase && booking.productId.toString() == baseline.shoppingText("product_id"))
        require(baseline.shoppingText("done") != "1" && booking.note.orEmpty().length <= 4000)
        return queue("purchase", JsonObject(booking.payload() + ("product_id" to JsonPrimitive(booking.productId))), baseline)
    }
    override suspend fun changes() = withContext(Dispatchers.IO) { access(); database.shoppingOperations().map { it.view() } }
    override suspend fun sync() = withContext(Dispatchers.IO) {
        access(); lock.withLock { ShoppingSync(database, cached, stock).drain() }
    }
    override suspend fun acceptServer(id: String) = withContext(Dispatchers.IO) {
        access(); lock.withLock { ShoppingSync(database, cached, stock).discard(id) }
    }
}
