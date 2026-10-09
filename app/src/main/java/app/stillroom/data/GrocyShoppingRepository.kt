package app.stillroom.data

import app.stillroom.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.math.BigDecimal
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
    override suspend fun addPantryAttention(kind: String, listId: Long): List<String> = withContext(Dispatchers.IO) {
        access()
        require(listId > 0)
        val lists = cached.readFresh("/objects/shopping_lists") ?: error("Shopping lists could not be read from Grocy.")
        require(lists.jsonArray.map { it.jsonObject }.any { it.shoppingText("id") == listId.toString() }) { "Choose a shopping list." }
        val volatile = cached.readFresh("/stock/volatile") ?: error("Grocy stock status could not be read.")
        val request = pantryListRequest(volatile.jsonObject, kind)
        val ids = mutableListOf<String>()
        for (bulk in request.bulk) ids += enqueueBulk(bulk, listId)
        if (request.dueProductIds.isNotEmpty()) {
            val products = cached.readFresh("/objects/products")?.jsonArray?.map { it.jsonObject }.orEmpty()
            val onList = cached.readFresh("/objects/shopping_list")?.jsonArray?.map { it.jsonObject }.orEmpty()
            val pending = database.shoppingOperations().filter { it.state !in setOf("confirmed", "discarded", "failed") }
            for (productId in request.dueProductIds) {
                val listed = onList.any { it.shoppingText("shopping_list_id") == listId.toString() && it.shoppingText("product_id") == productId.toString() && it.shoppingText("done") != "1" }
                val queued = pending.any { operation ->
                    val desired = runCatching { Json.parseToJsonElement(operation.desired).jsonObject }.getOrNull() ?: return@any false
                    desired.shoppingText("shopping_list_id") == listId.toString() && desired.shoppingText("product_id") == productId.toString()
                }
                if (listed || queued) continue
                val product = products.find { it.shoppingText("id") == productId.toString() } ?: continue
                ids += save(ShoppingDraft(listId, productId, "", BigDecimal.ONE, product.shoppingText("qu_id_stock").toLongOrNull()))
            }
        }
        cached.drain()
        sync()
        ids
    }
    private suspend fun enqueueBulk(kind: PantryListAdd, listId: Long): String {
        val path = when (kind) {
            PantryListAdd.Missing -> "/stock/shoppinglist/add-missing-products"
            PantryListAdd.Overdue -> "/stock/shoppinglist/add-overdue-products"
            PantryListAdd.Expired -> "/stock/shoppinglist/add-expired-products"
        }
        val payload = buildJsonObject { put("list_id", listId) }.toString()
        val existing = database.operations().filter { it.method == "POST" && it.path == path && it.payload == payload }
        if (existing.any { it.state == "needs-review" }) error("A previous add is waiting in Pending changes. Resolve it before adding these again.")
        existing.firstOrNull { it.state in setOf("pending", "in-flight", "guarded") }?.let { return it.clientOperationId }
        return cached.enqueue("POST", path, payload, "/objects/shopping_list")
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
