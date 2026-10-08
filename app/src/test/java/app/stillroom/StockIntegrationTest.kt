package app.stillroom

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.stillroom.data.*
import app.stillroom.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class StockIntegrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun pending(db: AccountDatabase) = db.operations().map { PendingChange(it.clientOperationId, it.method, it.path, it.state, it.detail, false) }

    @Test fun cachedStockDenialOverridesLastGoodResponseAndNeverReturnsChildCache() = runBlocking(Dispatchers.IO) {
        val server = MockWebServer(); server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        val address = ServerAddress.parse("http://127.0.0.1:${server.port}", true)
        val id = AccountId.of(address, 1)
        AccountDatabase.delete(context, id, "stock_test")
        val db = AccountDatabase(context, id, "stock_test")
        try {
            val cache = CachedGrocyRepository(db, address, "synthetic-key", MutationTransport(250))
            val stock = GrocyStockRepository(setOf("ADMIN"), cache) { pending(db) }
            server.enqueue(MockResponse().setBody("[]")); assertFalse(stock.read("/stock").stale)
            server.enqueue(MockResponse().setResponseCode(500)); assertTrue(stock.read("/stock").stale)
            server.enqueue(MockResponse().setResponseCode(403))
            try { stock.read("/stock"); fail("Denied stock was shown") } catch (e: GrocyFailure) { assertEquals(403, e.status) }
            val child = GrocyStockRepository(setOf("CHORES"), cache) { pending(db) }
            try { child.read("/stock"); fail("Child saw stock") } catch (_: IllegalStateException) { }
            try { child.book(StockBooking(StockAction.Consume, 1, BigDecimal.ONE)); fail("Child queued stock") } catch (_: IllegalStateException) { }
            try { child.undo(1); fail("Child queued undo") } catch (_: IllegalStateException) { }
            assertEquals(3, server.requestCount)
            assertTrue(db.operations().isEmpty())
        } finally { db.close(); AccountDatabase.delete(context, id, "stock_test"); server.shutdown() }
    }

    @Test fun outboxStockPayloadsPendingConfirmedUndoAndEndpointRestrictions() = runBlocking(Dispatchers.IO) {
        val server = MockWebServer(); server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        val address = ServerAddress.parse("http://127.0.0.1:${server.port}", true)
        val id = AccountId.of(address, 1); AccountDatabase.delete(context, id, "stock_test")
        val db = AccountDatabase(context, id, "stock_test")
        try {
            val cache = CachedGrocyRepository(db, address, "synthetic-key", MutationTransport(250))
            val stock = GrocyStockRepository(setOf("ADMIN"), cache) { pending(db) }
            for (action in StockAction.entries) {
                val operation = stock.book(StockBooking(action, 1, BigDecimal("1.5"), BigDecimal("2"), 1, 2, "2027-01-01", BigDecimal("4.25")))
                assertEquals("pending", stock.operations().last().state)
                assertEquals(0, server.requestCount - StockAction.entries.indexOf(action))
                server.enqueue(MockResponse().setBody("[]")); cache.drain()
                assertEquals("confirmed", db.operation(operation)!!.state)
                val request = server.takeRequest()
                assertEquals("POST", request.method)
                assertEquals("application/json", request.getHeader("Content-Type"))
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                assertEquals(BigDecimal("3.0"), body[if (action == StockAction.Inventory) "new_amount" else "amount"]!!.jsonPrimitive.content.toBigDecimal())
            }
            val undo = stock.undo(4); server.enqueue(MockResponse().setResponseCode(204)); cache.drain()
            assertEquals("confirmed", db.operation(undo)!!.state)
            assertEquals("/api/stock/bookings/4/undo", server.takeRequest().path)
            val transactionUndo = stock.undoTransaction("synthetic-transaction")
            server.enqueue(MockResponse().setResponseCode(204)); cache.drain()
            assertEquals("confirmed", db.operation(transactionUndo)!!.state)
            assertEquals("/api/stock/transactions/synthetic-transaction/undo", server.takeRequest().path)
            try { stock.undoTransaction("../other"); fail("Unsafe transaction id") } catch (_: IllegalArgumentException) { }
            try { stock.read("/stock/barcodes/external-lookup/123"); fail("External lookup allowed") } catch (_: IllegalArgumentException) { }
            try { stock.undo(0); fail("Invalid undo id") } catch (_: IllegalArgumentException) { }
            val reads = listOf("/stock", "/stock/volatile", "/objects/products", "/objects/locations", "/objects/quantity_units", "/objects/product_barcodes", "/objects/stock_log", "/objects/quantity_unit_conversions_resolved", "/stock/products/1", "/stock/products/1/locations", "/stock/products/1/entries", "/stock/products/1/price-history", "/stock/locations/1/entries", "/openapi/specification")
            for (path in reads) { server.enqueue(MockResponse().setBody("[]")); assertFalse(stock.read(path).stale) }
        } finally { db.close(); AccountDatabase.delete(context, id, "stock_test"); server.shutdown() }
    }

    @Test fun liveContainerStockAndJournalMatchGrocyOnBothVersions() = runBlocking(Dispatchers.IO) {
        val credentialsPath = System.getenv("STILLROOM_STAGE6_FIXTURES")
        Assume.assumeTrue("Live fixtures supplied only by stage:6", credentialsPath != null)
        val fixtures = Json.parseToJsonElement(File(credentialsPath!!).readText()).jsonObject["fixtures"]!!.jsonArray
        val report = mutableListOf<JsonObject>()
        for (fixtureValue in fixtures) {
            val fixture = fixtureValue.jsonObject
            fun field(name: String) = fixture[name]!!.jsonPrimitive.content
            val address = ServerAddress.parse(field("base_url"), true)
            val key = field("parent_key")
            val transport = MutationTransport()
            suspend fun http(method: String, path: String, body: String = "{}"): JsonElement {
                val (status, payload) = transport.request(address, key, method, path, body)
                assertTrue("Fixture HTTP $status at $path: " + if (status !in 200..299) Json.parseToJsonElement(payload).jsonObject["error_message"]?.jsonPrimitive?.content.orEmpty() else "", status in 200..299)
                return if (payload.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(payload)
            }
            suspend fun create(entity: String, body: String): Long = http("POST", "/objects/$entity", body).jsonObject["created_object_id"]!!.jsonPrimitive.content.toLong()
            val token = java.util.UUID.randomUUID().toString().take(12)
            val location = create("locations", "{\"name\":\"Stage6 shelf $token\"}")
            val other = create("locations", "{\"name\":\"Stage6 destination $token\"}")
            val qu = create("quantity_units", "{\"name\":\"Stage6 unit $token\"}")
            val pack = create("quantity_units", "{\"name\":\"Stage6 pack $token\"}")
            var product: Long? = null; var conversion: Long? = null; var barcode: Long? = null
            val accountId = AccountId.of(address, field("parent_user_id").toLong())
            AccountDatabase.delete(context, accountId, "stage6_live")
            val db = AccountDatabase(context, accountId, "stage6_live")
            try {
                product = create("products", "{\"name\":\"Stage6 product $token\",\"location_id\":$location,\"qu_id_stock\":$qu,\"qu_id_purchase\":$pack,\"qu_id_consume\":$qu,\"qu_id_price\":$qu,\"min_stock_amount\":0}")
                val automaticConversion = http("GET", "/objects/quantity_unit_conversions").jsonArray.firstOrNull {
                    val row = it.jsonObject
                    row["product_id"]?.jsonPrimitive?.content == product.toString() && row["from_qu_id"]?.jsonPrimitive?.content == pack.toString() && row["to_qu_id"]?.jsonPrimitive?.content == qu.toString()
                }?.jsonObject
                conversion = automaticConversion?.get("id")?.jsonPrimitive?.content?.toLong()
                    ?: create("quantity_unit_conversions", "{\"product_id\":$product,\"from_qu_id\":$pack,\"to_qu_id\":$qu,\"factor\":4}")
                http("PUT", "/objects/quantity_unit_conversions/$conversion", "{\"factor\":4}")
                barcode = create("product_barcodes", "{\"product_id\":$product,\"barcode\":\"Stage6-$token\"}")
                val cache = CachedGrocyRepository(db, address, key)
                val stock = ManageStock(GrocyStockRepository(setOf("ADMIN"), cache) { pending(db) })
                val resolved = stock.read("/objects/quantity_unit_conversions_resolved").value.jsonArray.first { it.jsonObject["product_id"]!!.jsonPrimitive.content == product.toString() && it.jsonObject["from_qu_id"]!!.jsonPrimitive.content == pack.toString() }.jsonObject
                val factor = resolved["factor"]!!.jsonPrimitive.content.toBigDecimal()
                assertEquals(BigDecimal("4"), factor)
                suspend fun quantity(): BigDecimal = stock.read("/stock/products/$product").value.jsonObject["stock_amount"]!!.jsonPrimitive.content.toBigDecimal()
                suspend fun book(action: StockAction, amount: String, conversionFactor: BigDecimal = BigDecimal.ONE, from: Long? = null, to: Long? = null): String {
                    val operation = stock.book(StockBooking(action, product!!, BigDecimal(amount), conversionFactor, from, to, "2027-01-01", BigDecimal("2.50")))
                    assertEquals("pending", db.operation(operation)!!.state); cache.drain()
                    assertEquals("confirmed", db.operation(operation)!!.state)
                    return operation
                }
                book(StockAction.Purchase, "1.25", factor, location)
                assertEquals(0, quantity().compareTo(BigDecimal("5")))
                book(StockAction.Consume, "1.5")
                assertEquals(0, quantity().compareTo(BigDecimal("3.5")))
                val journal = stock.read("/objects/stock_log").value.jsonArray.filter { it.jsonObject["product_id"]!!.jsonPrimitive.content == product.toString() }
                assertEquals(2, journal.size)
                assertEquals(listOf("purchase", "consume"), journal.map { it.jsonObject["transaction_type"]!!.jsonPrimitive.content })
                assertEquals(0, journal[0].jsonObject["amount"]!!.jsonPrimitive.content.toBigDecimal().compareTo(BigDecimal("5")))
                assertEquals(0, journal[1].jsonObject["amount"]!!.jsonPrimitive.content.toBigDecimal().abs().compareTo(BigDecimal("1.5")))
                // Independent official API reads verify the same database used by Grocy's web app.
                val direct = http("GET", "/stock/products/$product").jsonObject
                assertEquals(0, direct["stock_amount"]!!.jsonPrimitive.content.toBigDecimal().compareTo(quantity()))
                assertEquals(journal, http("GET", "/objects/stock_log").jsonArray.filter { it.jsonObject["product_id"]!!.jsonPrimitive.content == product.toString() })
                val paths = stock.read("/openapi/specification").value.jsonObject["paths"]!!.jsonObject
                for (suffix in listOf("/locations", "/entries", "/price-history")) assertFalse(stock.read("/stock/products/$product$suffix").stale)
                assertTrue(stock.read("/objects/product_barcodes").value.jsonArray.any { it.jsonObject["barcode"]!!.jsonPrimitive.content == "Stage6-$token" })
                book(StockAction.Open, "1")
                book(StockAction.Transfer, "1", from = location, to = other)
                if (paths.containsKey("/stock/transactions/{transactionId}/undo")) {
                    val transfer = stock.read("/objects/stock_log").value.jsonArray.last { it.jsonObject["product_id"]!!.jsonPrimitive.content == product.toString() }.jsonObject
                    val operation = stock.undoTransaction(transfer["transaction_id"]!!.jsonPrimitive.content)
                    cache.drain(); assertEquals("confirmed", db.operation(operation)!!.state)
                    val locations = stock.read("/stock/products/$product/locations").value.jsonArray
                    assertFalse(locations.any { it.jsonObject["location_id"]!!.jsonPrimitive.content == other.toString() && it.jsonObject["amount"]!!.jsonPrimitive.content.toBigDecimal().signum() > 0 })
                    assertEquals(0, quantity().compareTo(BigDecimal("3.5")))
                }
                book(StockAction.Inventory, "4")
                book(StockAction.Spoilage, "0.5")
                assertEquals(0, quantity().compareTo(BigDecimal("3.5")))
                if (paths.containsKey("/stock/bookings/{bookingId}/undo")) {
                    val last = stock.read("/objects/stock_log").value.jsonArray.last { it.jsonObject["product_id"]!!.jsonPrimitive.content == product.toString() }.jsonObject
                    val operation = stock.undo(last["id"]!!.jsonPrimitive.content.toLong()); cache.drain()
                    assertEquals("confirmed", db.operation(operation)!!.state)
                    assertEquals(0, quantity().compareTo(BigDecimal("4")))
                }
                val child = ManageStock(GrocyStockRepository(setOf("CHORES", "CHORE_TRACK_EXECUTION"), CachedGrocyRepository(db, address, field("child_key"))) { pending(db) })
                try { child.read("/stock"); fail("Denied child exposed stock") } catch (_: IllegalStateException) { }
                report += buildJsonObject { put("version", field("version")); put("purchase_stock", 5); put("consume_stock", 3.5); put("journal_matches", true); put("all_mutations_confirmed", true); put("denied_child_hidden", true) }
            } finally {
                // Remove only this test's synthetic records; no household or Stage 1 records are touched.
                barcode?.let { http("DELETE", "/objects/product_barcodes/$it") }
                conversion?.let { http("DELETE", "/objects/quantity_unit_conversions/$it") }
                product?.let { http("DELETE", "/objects/products/$it") }
                http("DELETE", "/objects/locations/$location"); http("DELETE", "/objects/locations/$other")
                http("DELETE", "/objects/quantity_units/$qu"); http("DELETE", "/objects/quantity_units/$pack")
                db.close(); AccountDatabase.delete(context, accountId, "stage6_live")
            }
        }
        File("build/reports/stage6").mkdirs()
        File("build/reports/stage6/container.json").writeText(JsonArray(report).toString())
    }
}
