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
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ShoppingIntegrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val databases = mutableListOf<Pair<AccountDatabase, Pair<AccountId, String>>>()
    private fun client(address: ServerAddress, key: String, name: String, grants: Set<String> = setOf("ADMIN"), timeout: Long = 1000): Pair<GrocyShoppingRepository, AccountDatabase> {
        val id = AccountId.of(address, 1); AccountDatabase.delete(context, id, name)
        val db = AccountDatabase(context, id, name); databases += db to (id to name)
        val cache = CachedGrocyRepository(db, address, key, MutationTransport(timeout))
        val stock = GrocyStockRepository(grants, cache) { emptyList() }
        return GrocyShoppingRepository(db, cache, stock, grants) to db
    }
    @After fun cleanup() { databases.forEach { (db, identity) -> db.close(); AccountDatabase.delete(context, identity.first, identity.second) }; databases.clear() }
    private fun row(note: String = "base", amount: String = "2.5") = Json.parseToJsonElement("""{"id":7,"product_id":3,"shopping_list_id":1,"note":"$note","amount":$amount,"qu_id":4,"done":0,"row_created_timestamp":"2026-10-07 14:00:00"}""").jsonObject

    /** Simulates the official unique primary key, with barriers/drops outside the mutation layer. */
    private class FixtureDispatcher(var current: JsonObject) : okhttp3.mockwebserver.Dispatcher() {
        val claims = ConcurrentHashMap<String, String>()
        var online = true; var mutations = 0; var purchases = 0; var losePurchaseResponse = false
        var changedAfterPurchase = false
        private var entity = false
        val journal = mutableListOf<JsonObject>()
        @Synchronized override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path!!.removePrefix("/api")
            if (!online) return MockResponse().setResponseCode(503)
            if (request.method == "GET") {
                return when {
                    path == "/objects/shopping_list/7" -> MockResponse().setBody(current.toString())
                    path.startsWith("/objects/userentities/") -> if (entity) MockResponse().setBody("{\"name\":\"${ShoppingClaims.ENTITY_NAME}\"}") else MockResponse().setResponseCode(404)
                    path.startsWith("/objects/userobjects/") -> claims[path.substringAfterLast('/')]?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404)
                    path == "/objects/stock_log" -> MockResponse().setBody(JsonArray(journal).toString())
                    else -> MockResponse().setBody("[]")
                }
            }
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            return when {
                path == "/objects/userentities" -> { entity = true; MockResponse().setBody("{\"created_object_id\":\"${ShoppingClaims.ENTITY_ID}\"}") }
                path == "/objects/userobjects" -> {
                    val id = body.shoppingText("id")
                    if (claims.putIfAbsent(id, body.toString()) != null) MockResponse().setResponseCode(400)
                    else MockResponse().setBody("{\"created_object_id\":\"$id\"}")
                }
                path.startsWith("/objects/userobjects/") -> { claims.remove(path.substringAfterLast('/')); MockResponse().setResponseCode(204) }
                path == "/stock/products/3/add" -> {
                    purchases++; journal += JsonObject(body + mapOf("product_id" to JsonPrimitive(3), "undone" to JsonPrimitive(0)))
                    if (changedAfterPurchase) current = JsonObject(current + ("note" to JsonPrimitive("remote changed during checkout")))
                    if (losePurchaseResponse) MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) else MockResponse().setBody("[]")
                }
                path == "/objects/shopping_list/7" -> { mutations++; current = JsonObject(current + body); MockResponse().setResponseCode(204) }
                else -> MockResponse().setBody("{\"created_object_id\":8}")
            }
        }
    }
    private suspend fun mockTest(block: suspend (ServerAddress, FixtureDispatcher) -> Unit) {
        val server = MockWebServer(); server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        val dispatcher = FixtureDispatcher(row()); server.dispatcher = dispatcher
        try { block(ServerAddress.parse("http://127.0.0.1:${server.port}", true), dispatcher) } finally { server.shutdown() }
    }
    @Test fun offlineEditsConsolidatePersistReplayAndConflictPreservesRemote() = runBlocking(Dispatchers.IO) { mockTest { address, fixture ->
        val (a, db) = client(address, "synthetic", "shopping_a")
        fixture.online = false
        val operation = a.save(ShoppingDraft(1, 3, "local1", BigDecimal("2.5"), 4), row())
        assertEquals(operation, a.save(ShoppingDraft(1, 3, "local2", BigDecimal("3.5"), 4), row()))
        a.sync(); assertEquals("pending", a.changes().single().state); assertEquals(0, fixture.mutations)
        db.close()
        val reopened = AccountDatabase(context, AccountId.of(address, 1), "shopping_a")
        val cache = CachedGrocyRepository(reopened, address, "synthetic", MutationTransport(1000))
        val restored = GrocyShoppingRepository(reopened, cache, GrocyStockRepository(setOf("ADMIN"), cache) { emptyList() }, setOf("ADMIN"))
        try {
            fixture.online = true; restored.sync(); restored.sync()
            assertEquals("confirmed", restored.changes().single().state); assertEquals("local2", fixture.current.shoppingText("note")); assertEquals(1, fixture.mutations)
            val baseline = fixture.current
            restored.save(ShoppingDraft(1, 3, "stale local", BigDecimal.ONE, 4), baseline)
            fixture.current = JsonObject(baseline + ("note" to JsonPrimitive("client B")))
            restored.sync(); assertEquals("conflict", restored.changes().last().state)
            assertEquals("client B", fixture.current.shoppingText("note")); assertEquals(1, fixture.mutations)
            restored.acceptServer(restored.changes().last().id); assertEquals("discarded", restored.changes().last().state)
        } finally { reopened.close() }
    } }
    @Test fun simultaneousClientsNeverOverwriteAndNeverDoublePurchase() = runBlocking(Dispatchers.IO) { mockTest { address, fixture ->
        val (a, _) = client(address, "synthetic", "shopping_a"); val (b, _) = client(address, "synthetic", "shopping_b")
        a.save(ShoppingDraft(1, 3, "client A", BigDecimal.ONE, 4), row())
        b.save(ShoppingDraft(1, 3, "client B", BigDecimal("2"), 4), row())
        awaitAll(async { a.sync() }, async { b.sync() })
        assertEquals(setOf("confirmed", "conflict"), setOf(a.changes().single().state, b.changes().single().state))
        assertEquals(1, fixture.mutations)
        val winner = if (a.changes().single().state == "confirmed") a else b
        val loser = if (winner === a) b else a
        loser.acceptServer(loser.changes().single().id)
        val baseline = fixture.current
        val booking = StockBooking(StockAction.Purchase, 3, BigDecimal.ONE)
        a.purchase(baseline, booking); b.purchase(baseline, booking)
        awaitAll(async { a.sync() }, async { b.sync() })
        a.sync(); b.sync()
        assertEquals(1, fixture.purchases)
        assertEquals("1", fixture.current.shoppingText("done"))
        assertEquals(setOf("confirmed", "conflict"), setOf(a.changes().last().state, b.changes().last().state))
        assertTrue(fixture.claims.isNotEmpty())
    } }
    @Test fun lostPurchaseResponseReconcilesReadOnlyAndRetainsReceiptAcrossClients() = runBlocking(Dispatchers.IO) { mockTest { address, fixture ->
        fixture.losePurchaseResponse = true
        val (a, db) = client(address, "synthetic", "shopping_a", timeout = 250)
        val (b, _) = client(address, "synthetic", "shopping_b")
        val baseline = fixture.current
        val booking = StockBooking(StockAction.Purchase, 3, BigDecimal("2.5"), note = "Review note")
        val operation = a.purchase(baseline, booking); a.sync()
        assertEquals("needs-review", a.changes().single().state); assertEquals(1, fixture.purchases)
        assertEquals("needs-review", db.operation(operation)!!.state)
        a.sync(); assertEquals("confirmed", a.changes().single().state); assertEquals(1, fixture.purchases)
        b.purchase(baseline, booking); b.sync(); assertEquals("conflict", b.changes().single().state)
        assertEquals(1, fixture.purchases)
        assertEquals(operation, a.purchase(baseline, booking))
    } }
    @Test fun changedDuringCheckoutDoesNotOverwriteListOrBuyAgain() = runBlocking(Dispatchers.IO) { mockTest { address, fixture ->
        fixture.changedAfterPurchase = true
        val (a, _) = client(address, "synthetic", "shopping_a")
        a.purchase(row(), StockBooking(StockAction.Purchase, 3, BigDecimal.ONE)); a.sync(); a.sync()
        assertEquals("conflict", a.changes().single().state); assertEquals(1, fixture.purchases)
        assertEquals("remote changed during checkout", fixture.current.shoppingText("note")); assertEquals("0", fixture.current.shoppingText("done"))
        assertEquals(0, fixture.mutations)
    } }
    @Test fun deniedShoppingNeverFetchesOrQueues() = runBlocking(Dispatchers.IO) { mockTest { address, fixture ->
        val (a, db) = client(address, "synthetic", "shopping_child", setOf("CHORES"))
        try { a.snapshot(); fail("Denied read") } catch (_: IllegalStateException) { }
        try { a.save(ShoppingDraft(1, 3, "", BigDecimal.ONE)); fail("Denied write") } catch (_: IllegalStateException) { }
        assertTrue(db.shoppingOperations().isEmpty()); assertEquals(0, fixture.mutations)
    } }

    @Test fun activationReplayDenialDoesNotEscapeAndKeepsQueuedIntent() = runBlocking(Dispatchers.IO) {
        val server = MockWebServer(); server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        try {
            val address = ServerAddress.parse("http://127.0.0.1:${server.port}", true)
            val (shopping, db) = client(address, "synthetic", "activation_denied")
            shopping.save(ShoppingDraft(1, 3, "Queued while authorized", BigDecimal.ONE, 4), row())
            server.enqueue(MockResponse().setResponseCode(403))
            resumeAccountOperations(CachedGrocyRepository(db, address, "synthetic"), shopping, setOf("ADMIN"))
            assertEquals("pending", shopping.changes().single().state)
            assertEquals("true", db.get("background", "access-denied"))
            assertEquals(1, server.requestCount)
            assertEquals("GET", server.takeRequest().method)
        } finally { server.shutdown() }
    }

    @Test fun confirmedClaimBeforeInterruptedDispatchRecoversWithoutLosingIntent() = runBlocking(Dispatchers.IO) { mockTest { address, fixture ->
        val (a, db) = client(address, "synthetic", "shopping_a")
        val id = a.save(ShoppingDraft(1, 3, "recovered", BigDecimal.ONE, 4), row())
        val original = db.shoppingOperation(id)!!
        val claimId = ShoppingClaims.id("shopping_list", row())
        fixture.claims[claimId.toString()] = "{\"id\":$claimId}"
        val claimOperation = java.util.UUID.nameUUIDFromBytes("$id/claim".toByteArray()).toString()
        db.enqueue(OutboxOperation(claimOperation, "POST", "/objects/userobjects", "{}", "/objects/userobjects/$claimId", null))
        assertTrue(db.claim(claimOperation)); db.finish(claimOperation, "confirmed", responsePayload = "{\"created_object_id\":\"$claimId\"}")
        db.saveShopping(original.copy(state = "preparing")); db.close()
        val reopened = AccountDatabase(context, AccountId.of(address, 1), "shopping_a")
        try {
            assertEquals("needs-review", reopened.shoppingOperation(id)!!.state)
            val cache = CachedGrocyRepository(reopened, address, "synthetic", MutationTransport(1000))
            val restored = GrocyShoppingRepository(reopened, cache, GrocyStockRepository(setOf("ADMIN"), cache) { emptyList() }, setOf("ADMIN"))
            restored.sync(); restored.sync()
            assertEquals("confirmed", restored.changes().single().state)
            assertEquals("recovered", fixture.current.shoppingText("note")); assertEquals(1, fixture.mutations)
            assertTrue(fixture.claims.isEmpty())
        } finally { reopened.close() }
    } }

    @Test fun purchaseReceiptAllowsLaterEditsButBlocksAnotherCheckout() = runBlocking(Dispatchers.IO) { mockTest { address, fixture ->
        val (a, _) = client(address, "synthetic", "shopping_a"); val (b, _) = client(address, "synthetic", "shopping_b")
        a.purchase(row(), StockBooking(StockAction.Purchase, 3, BigDecimal.ONE)); a.sync()
        assertEquals("confirmed", a.changes().single().state)
        assertFalse(fixture.claims.containsKey(ShoppingClaims.id("shopping_list", row()).toString()))
        assertTrue(fixture.claims.containsKey(ShoppingClaims.purchaseId(row()).toString()))
        b.save(ShoppingDraft(1, 3, "edited after purchase", BigDecimal.ONE, 4, false), fixture.current); b.sync()
        assertEquals("confirmed", b.changes().last().state); assertEquals("0", fixture.current.shoppingText("done"))
        b.purchase(fixture.current, StockBooking(StockAction.Purchase, 3, BigDecimal.ONE)); b.sync()
        assertEquals("conflict", b.changes().last().state); assertEquals(1, fixture.purchases)
    } }

    @Test fun queuedTransportWriteCannotBypassFreshGuardAfterProcessReopen() = runBlocking(Dispatchers.IO) { mockTest { address, fixture ->
        val (a, db) = client(address, "synthetic", "shopping_a")
        val id = a.save(ShoppingDraft(1, 3, "local queued write", BigDecimal.ONE, 4), row())
        val intent = db.shoppingOperation(id)!!
        val cache = CachedGrocyRepository(db, address, "synthetic", MutationTransport(1000))
        cache.enqueue("PUT", "/objects/shopping_list/7", intent.desired, "/objects/shopping_list/7", operationId = id, guarded = true)
        db.saveShopping(intent.copy(state = "dispatching")); db.close()
        fixture.current = JsonObject(row() + ("note" to JsonPrimitive("remote changed while stopped")))
        val reopened = AccountDatabase(context, AccountId.of(address, 1), "shopping_a")
        try {
            val transport = CachedGrocyRepository(reopened, address, "synthetic", MutationTransport(1000))
            transport.drain(); assertEquals(0, fixture.mutations)
            assertEquals("guarded", reopened.operation(id)!!.state)
            val restored = GrocyShoppingRepository(reopened, transport, GrocyStockRepository(setOf("ADMIN"), transport) { emptyList() }, setOf("ADMIN"))
            restored.sync(); transport.drain()
            assertEquals("conflict", restored.changes().single().state)
            assertEquals(0, fixture.mutations); assertEquals("remote changed while stopped", fixture.current.shoppingText("note"))
        } finally { reopened.close() }
    } }

    @Test fun liveContainersAddEditDeleteOfflineReplayConflictsAndTwoClientPurchase() = runBlocking(Dispatchers.IO) {
        val file = System.getenv("STILLROOM_STAGE7_FIXTURES")
        Assume.assumeTrue("Only stage:7 supplies live fixtures", file != null)
        val fixtures = Json.parseToJsonElement(File(file!!).readText()).jsonObject["fixtures"]!!.jsonArray
        val reports = mutableListOf<JsonObject>()
        for (value in fixtures) {
            val fixture = value.jsonObject
            val address = ServerAddress.parse(fixture.shoppingText("base_url"), true); val key = fixture.shoppingText("parent_key")
            val transport = MutationTransport()
            suspend fun http(method: String, path: String, body: String = "{}"): JsonElement? {
                val (status, payload) = transport.request(address, key, method, path, body)
                if (status == 404) return null
                assertTrue("Fixture HTTP $status at $path", status in 200..299)
                return if (payload.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(payload)
            }
            suspend fun create(entity: String, body: String) = http("POST", "/objects/$entity", body)!!.jsonObject.shoppingText("created_object_id").toLong()
            val token = java.util.UUID.randomUUID().toString().take(8)
            val list = create("shopping_lists", "{\"name\":\"Stage7 $token\"}")
            val location = create("locations", "{\"name\":\"Stage7 $token\"}")
            val qu = create("quantity_units", "{\"name\":\"Stage7 $token\"}")
            val product = create("products", "{\"name\":\"Stage7 $token\",\"location_id\":$location,\"qu_id_stock\":$qu,\"qu_id_purchase\":$qu,\"min_stock_amount\":0}")
            val (a, _) = client(address, key, "stage7_live_a"); val (b, _) = client(address, key, "stage7_live_b")
            val claimIds = mutableSetOf<Long>(); val rowIds = mutableSetOf<Long>(); var secondList: Long? = null
            try {
                a.createList("Stage7 second $token"); a.sync(); assertEquals("confirmed", a.changes().last().state)
                secondList = http("GET", "/objects/shopping_lists")!!.jsonArray.first { it.jsonObject.shoppingText("name") == "Stage7 second $token" }.jsonObject.shoppingText("id").toLong()
                a.save(ShoppingDraft(list, product, "original", BigDecimal("2.5"), qu)); a.sync()
                assertEquals("confirmed", a.changes().last().state)
                var row = http("GET", "/objects/shopping_list")!!.jsonArray.first { it.jsonObject.shoppingText("shopping_list_id") == list.toString() }.jsonObject
                rowIds += row.shoppingText("id").toLong(); claimIds += ShoppingClaims.id("shopping_list", row); claimIds += ShoppingClaims.purchaseId(row)
                a.save(ShoppingDraft(list, product, "edited", BigDecimal("3.5"), qu), row); a.sync()
                assertEquals("confirmed", a.changes().last().state)
                row = http("GET", "/objects/shopping_list/${row.shoppingText("id")}")!!.jsonObject
                assertEquals("edited", row.shoppingText("note")); assertEquals(0, row.shoppingDecimal("amount")!!.compareTo(BigDecimal("3.5")))
                // A phone queues an edit offline; another client writes before reconnect.
                val disconnectedCache = CachedGrocyRepository(databases.last { it.second.second == "stage7_live_a" }.first, ServerAddress.parse("http://127.0.0.1:1", true), key, MutationTransport(200))
                val offline = GrocyShoppingRepository(databases.last { it.second.second == "stage7_live_a" }.first, disconnectedCache, GrocyStockRepository(setOf("ADMIN"), disconnectedCache) { emptyList() }, setOf("ADMIN"))
                offline.save(ShoppingDraft(list, product, "offline stale", BigDecimal.ONE, qu), row); offline.sync()
                assertEquals("pending", offline.changes().last().state)
                b.save(ShoppingDraft(list, product, "client B", BigDecimal("4.5"), qu), row); b.sync()
                assertEquals("confirmed", b.changes().last().state)
                a.sync(); assertEquals("conflict", a.changes().last().state)
                var remote = http("GET", "/objects/shopping_list/${row.shoppingText("id")}")!!.jsonObject
                assertEquals("client B", remote.shoppingText("note")); assertEquals(0, remote.shoppingDecimal("amount")!!.compareTo(BigDecimal("4.5")))
                a.acceptServer(a.changes().last().id)
                offline.save(ShoppingDraft(list, product, "offline replayed", BigDecimal("2.5"), qu), remote); offline.sync()
                assertEquals("pending", offline.changes().last().state); a.sync(); a.sync()
                assertEquals("confirmed", a.changes().last().state)
                remote = http("GET", "/objects/shopping_list/${row.shoppingText("id")}")!!.jsonObject
                assertEquals("offline replayed", remote.shoppingText("note"))
                // Both clients review the same row, then race checkout against the actual container.
                val booking = StockBooking(StockAction.Purchase, product, BigDecimal("2.5"), location = location, date = "2027-01-01", price = BigDecimal("4.00"))
                val purchase = a.purchase(remote, booking); b.purchase(remote, booking)
                awaitAll(async { a.sync() }, async { b.sync() }); a.sync(); b.sync()
                assertEquals(setOf("confirmed", "conflict"), setOf(a.changes().last().state, b.changes().last().state))
                assertEquals(0, http("GET", "/stock/products/$product")!!.jsonObject.shoppingDecimal("stock_amount")!!.compareTo(BigDecimal("2.5")))
                val journal = http("GET", "/objects/stock_log")!!.jsonArray.filter { it.jsonObject.shoppingText("product_id") == product.toString() && it.jsonObject.shoppingText("transaction_type") == "purchase" }
                assertEquals(1, journal.size)
                assertEquals("1", http("GET", "/objects/shopping_list/${row.shoppingText("id")}")!!.jsonObject.shoppingText("done"))
                val snapshot = a.snapshot(); assertTrue(snapshot.rows("shopping_lists").any { it.shoppingText("id") == list.toString() }); assertFalse(snapshot.stale)
                // Delete a separate note row; preserve the purchased row's receipt until fixture cleanup.
                a.save(ShoppingDraft(list, null, "delete me", BigDecimal.ONE)); a.sync()
                val note = http("GET", "/objects/shopping_list")!!.jsonArray.first { it.jsonObject.shoppingText("shopping_list_id") == list.toString() && it.jsonObject.shoppingText("note") == "delete me" }.jsonObject
                rowIds += note.shoppingText("id").toLong(); claimIds += ShoppingClaims.id("shopping_list", note)
                a.delete(note); a.sync(); assertEquals("confirmed", a.changes().last().state)
                assertNull(http("GET", "/objects/shopping_list/${note.shoppingText("id")}"))
                // The real container applies a purchase, while this test proxy drops its acknowledgement.
                a.save(ShoppingDraft(list, product, "lost response", BigDecimal("1.5"), qu)); a.sync()
                val lostRow = http("GET", "/objects/shopping_list")!!.jsonArray.first { it.jsonObject.shoppingText("shopping_list_id") == list.toString() && it.jsonObject.shoppingText("note") == "lost response" }.jsonObject
                rowIds += lostRow.shoppingText("id").toLong(); claimIds += ShoppingClaims.id("shopping_list", lostRow); claimIds += ShoppingClaims.purchaseId(lostRow)
                val proxy = MockWebServer(); proxy.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
                val purchaseCalls = java.util.concurrent.atomic.AtomicInteger()
                proxy.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = runBlocking {
                        val path = request.path!!.removePrefix("/api")
                        val (status, body) = transport.request(address, key, request.method!!, path, request.body.readUtf8())
                        if (request.method == "POST" && path == "/stock/products/$product/add") {
                            purchaseCalls.incrementAndGet(); MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                        } else MockResponse().setResponseCode(status).setBody(body)
                    }
                }
                try {
                    val proxyAddress = ServerAddress.parse("http://127.0.0.1:${proxy.port}", true)
                    val (throughProxy, proxyDb) = client(proxyAddress, key, "stage7_lost_ack", timeout = 500)
                    val actual = StockBooking(StockAction.Purchase, product, BigDecimal("1.5"), location = location, date = "2027-01-01", price = BigDecimal("4"))
                    throughProxy.purchase(lostRow, actual); throughProxy.sync()
                    assertEquals("needs-review", throughProxy.changes().single().state)
                    assertEquals(1, purchaseCalls.get())
                    b.purchase(lostRow, actual); b.sync()
                    assertEquals("conflict", b.changes().last().state)
                    proxyDb.close()
                    val reopened = AccountDatabase(context, AccountId.of(proxyAddress, 1), "stage7_lost_ack")
                    try {
                        val cache = CachedGrocyRepository(reopened, proxyAddress, key, MutationTransport(1000))
                        val recovered = GrocyShoppingRepository(reopened, cache, GrocyStockRepository(setOf("ADMIN"), cache) { emptyList() }, setOf("ADMIN"))
                        recovered.sync(); recovered.sync()
                        assertEquals("confirmed", recovered.changes().single().state)
                        assertEquals(1, purchaseCalls.get())
                    } finally { reopened.close() }
                    assertEquals(0, http("GET", "/stock/products/$product")!!.jsonObject.shoppingDecimal("stock_amount")!!.compareTo(BigDecimal("4")))
                    assertEquals(2, http("GET", "/objects/stock_log")!!.jsonArray.count { it.jsonObject.shoppingText("product_id") == product.toString() && it.jsonObject.shoppingText("transaction_type") == "purchase" })
                } finally { proxy.shutdown() }
                reports += buildJsonObject { put("version", fixture.shoppingText("version")); put("add_edit_delete", true); put("offline_replay", true); put("conflict_preserved_remote", true); put("two_client_stock", 2.5); put("purchase_journal_count", journal.size); put("no_duplicate_purchase", true); put("lost_ack_reconciled_after_reopen", true) }
            } finally {
                rowIds.forEach { if (http("GET", "/objects/shopping_list/$it") != null) http("DELETE", "/objects/shopping_list/$it") }
                claimIds.forEach { if (http("GET", "/objects/userobjects/$it") != null) http("DELETE", "/objects/userobjects/$it") }
                http("DELETE", "/objects/products/$product"); http("DELETE", "/objects/shopping_lists/$list")
                secondList?.let { http("DELETE", "/objects/shopping_lists/$it") }
                http("DELETE", "/objects/locations/$location"); http("DELETE", "/objects/quantity_units/$qu")
                val claimRows = http("GET", "/objects/userobjects")!!.jsonArray.filter { it.jsonObject.shoppingText("userentity_id") == ShoppingClaims.ENTITY_ID.toString() }
                if (claimRows.isEmpty()) http("DELETE", "/objects/userentities/${ShoppingClaims.ENTITY_ID}")
            }
        }
        File("build/reports/stage7").mkdirs(); File("build/reports/stage7/container.json").writeText(JsonArray(reports).toString())
    }
}
