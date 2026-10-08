package app.stillroom

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.stillroom.data.*
import app.stillroom.domain.AccountId
import app.stillroom.domain.ServerAddress
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class OutboxTest {
    private lateinit var server: MockWebServer
    private lateinit var db: AccountDatabase
    private lateinit var context: Context
    private lateinit var address: ServerAddress
    private lateinit var id: AccountId
    private val namespace = "outbox_test"
    private fun repository(database: AccountDatabase = db) = CachedGrocyRepository(database, address, "synthetic-key", MutationTransport(250))
    private suspend fun enqueue(repo: CachedGrocyRepository) = repo.enqueue("POST", "/objects/shopping_list", "{\"amount\":1}", "/objects/shopping_list/1", "{\"amount\":1}")

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer(); server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        address = ServerAddress.parse("http://127.0.0.1:${server.port}", true)
        id = AccountId.of(address, 1)
        AccountDatabase.delete(context, id, namespace)
        db = runBlocking(Dispatchers.IO) { AccountDatabase(context, id, namespace) }
    }
    @After fun cleanup() { db.close(); AccountDatabase.delete(context, id, namespace); server.shutdown() }

    @Test fun timeoutAfterPossibleApplyNeverReplaysAndReconcilesByRead() = runBlocking(Dispatchers.IO) {
        var applies = 0
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (request.method == "POST") {
                applies++; MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            } else MockResponse().setBody("{\"amount\":1}")
        }
        val repo = repository(); val operation = enqueue(repo)
        repo.drain(); assertEquals("needs-review", db.operation(operation)!!.state)
        repo.drain(); assertEquals(1, applies)
        repo.reconcile(operation)
        assertEquals("confirmed", db.operation(operation)!!.state)
        assertEquals(1, applies); assertEquals(2, server.requestCount)
    }

    @Test fun server500BeforeApplyFailsWithoutAutomaticRetry() = runBlocking(Dispatchers.IO) {
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
        val repo = repository(); val operation = enqueue(repo)
        repo.drain(); repo.drain()
        // A 5xx alone cannot establish that Grocy did not apply it.
        assertEquals("needs-review", db.operation(operation)!!.state)
        assertEquals(1, server.requestCount)
    }

    @Test fun unauthorizedFailsAndIsNotReplayed() = runBlocking(Dispatchers.IO) {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        val repo = repository(); val operation = enqueue(repo)
        repo.drain(); repo.drain()
        assertEquals("failed", db.operation(operation)!!.state)
        assertEquals(1, server.requestCount)
    }

    @Test fun malformedMutationResponseNeverReplays() = runBlocking(Dispatchers.IO) {
        server.enqueue(MockResponse().setBody("{bad"))
        val repo = repository(); val operation = enqueue(repo)
        repo.drain(); repo.drain()
        assertEquals("needs-review", db.operation(operation)!!.state)
        assertEquals(1, server.requestCount)
    }

    @Test fun readsKeepLastGoodPayloadOnMalformedAndTimeoutButHonorDenial() = runBlocking(Dispatchers.IO) {
        val repo = repository()
        server.enqueue(MockResponse().setBody("{\"amount\":2}"))
        assertFalse(repo.read("/objects/shopping_list/1").stale)
        for (response in listOf(MockResponse().setBody("bad"), MockResponse().setBody("{amount:2}"), MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))) {
            server.enqueue(response)
            val result = repo.read("/objects/shopping_list/1")
            assertTrue(result.stale); assertEquals("{\"amount\":2}", result.payload)
        }
        server.enqueue(MockResponse().setResponseCode(401))
        try { repo.read("/objects/shopping_list/1"); fail("Cached read bypassed denial") }
        catch (error: GrocyFailure) { assertEquals(401, error.status) }
    }

    @Test fun revokedCacheCannotReappearOfflineAfterReopen() = runBlocking(Dispatchers.IO) {
        var repo = repository()
        server.enqueue(MockResponse().setBody("[{\"name\":\"Private stock\"}]"))
        repo.read("/stock")
        server.enqueue(MockResponse().setResponseCode(403))
        try { repo.read("/stock"); fail("Denied read") } catch (_: GrocyFailure) { }
        db.close(); db = AccountDatabase(context, id, namespace); repo = repository()
        server.enqueue(MockResponse().setResponseCode(503))
        try { repo.read("/stock"); fail("Revoked cache reappeared offline") }
        catch (error: GrocyFailure) { assertEquals(403, error.status) }
        // An independently authorized live response remains readable.
        server.enqueue(MockResponse().setBody("[]"))
        assertFalse(repo.read("/stock").stale)
    }

    @Test fun secondLeaseRecoverDoesNotDropAnInFlightSuccess() = runBlocking(Dispatchers.IO) {
        val operation = enqueue(repository())
        assertTrue(db.claim(operation))
        val second = AccountDatabase(context, id, namespace)
        try {
            assertEquals("needs-review", second.operation(operation)!!.state)
            db.finish(operation, "confirmed", responsePayload = "{\"created\":1}")
            val stored = second.operation(operation)!!
            assertEquals("confirmed", stored.state)
            assertEquals("{\"created\":1}", stored.responsePayload)
            db.finish(operation, "needs-review", detail = "late")
            assertEquals("confirmed", second.operation(operation)!!.state)
        } finally { second.close() }
    }

    @Test fun pendingAndInterruptedOperationsSurviveReopenWithoutDuplicateApply() = runBlocking(Dispatchers.IO) {
        val repo = repository(); val pending = enqueue(repo); val interrupted = enqueue(repo)
        assertTrue(db.claim(interrupted))
        db.close(); db = AccountDatabase(context, id, namespace)
        val restored = repository()
        assertEquals("pending", db.operation(pending)!!.state)
        assertEquals("needs-review", db.operation(interrupted)!!.state)
        server.enqueue(MockResponse().setResponseCode(204))
        restored.drain(); restored.drain()
        assertEquals("confirmed", db.operation(pending)!!.state)
        assertEquals(1, server.requestCount)
    }

    @Test fun closedAccountCannotSendJobsAndOtherAccountHasOwnQueue() = runBlocking(Dispatchers.IO) {
        val old = repository(); val operation = enqueue(old)
        db.close()
        val otherId = AccountId.of(address, 2)
        AccountDatabase.delete(context, otherId, namespace)
        val other = AccountDatabase(context, otherId, namespace)
        try {
            assertTrue(other.operations().isEmpty())
            repository(other).drain()
            assertTrue(runCatching { old.drain() }.isFailure)
            assertEquals(0, server.requestCount)
        } finally { other.close(); AccountDatabase.delete(context, otherId, namespace) }
        db = AccountDatabase(context, id, namespace)
        assertEquals("pending", db.operation(operation)!!.state)
    }

    @Test fun mismatchAndMalformedReconciliationLeaveNeedsReview() = runBlocking(Dispatchers.IO) {
        server.enqueue(MockResponse().setBody("bad"))
        val repo = repository(); val operation = enqueue(repo); repo.drain()
        server.enqueue(MockResponse().setBody("{\"amount\":0}")); repo.reconcile(operation)
        assertEquals("needs-review", db.operation(operation)!!.state)
        assertEquals("{\"amount\":0}", db.operation(operation)!!.observedPayload)
        server.enqueue(MockResponse().setBody("bad")); repo.reconcile(operation)
        assertEquals("needs-review", db.operation(operation)!!.state)
        assertEquals(3, server.requestCount)
    }
    @Test fun activeRequestCanceledBySwitchCannotReplayOnReopen() = runBlocking(Dispatchers.IO) {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val repo = repository(); val operation = enqueue(repo)
        val worker = launch { repo.drain() }
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
        worker.cancelAndJoin(); db.close()
        db = AccountDatabase(context, id, namespace)
        assertEquals("needs-review", db.operation(operation)!!.state)
        repository().drain()
        assertEquals(1, server.requestCount)
    }

    @Test fun simultaneousDrainsClaimEachOperationOnce() = runBlocking(Dispatchers.IO) {
        val repo = repository(); val operation = enqueue(repo)
        server.enqueue(MockResponse().setBody("{}"))
        coroutineScope { repeat(5) { launch { repo.drain() } } }
        assertEquals("confirmed", db.operation(operation)!!.state)
        assertEquals(1, server.requestCount)
    }

    @Test fun stage3CacheMigratesWithoutLosingLastPayload() = runBlocking(Dispatchers.IO) {
        db.close(); AccountDatabase.delete(context, id, namespace)
        context.openOrCreateDatabase(AccountDatabase.name(id, namespace), 0, null).use {
            it.execSQL("CREATE TABLE cached_rows(resource TEXT NOT NULL, row_id TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(resource,row_id))")
            it.execSQL("INSERT INTO cached_rows VALUES ('system_info', 'current', '{}')")
            it.version = 1
        }
        db = AccountDatabase(context, id, namespace)
        assertEquals("{}", db.get("system_info", "current"))
        val repo = repository(); val operation = enqueue(repo)
        assertEquals("pending", db.operation(operation)!!.state)
    }

    @Test fun stage6SchemaMigratesAndPreservesPendingOperation() = runBlocking(Dispatchers.IO) {
        db.close(); AccountDatabase.delete(context, id, namespace)
        val path = context.getDatabasePath(AccountDatabase.name(id, namespace))
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null).use {
            it.execSQL("CREATE TABLE cached_rows (resource TEXT NOT NULL, row_id TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(resource,row_id))")
            it.execSQL("CREATE TABLE outbox (clientOperationId TEXT NOT NULL PRIMARY KEY, method TEXT NOT NULL, path TEXT NOT NULL, payload TEXT NOT NULL, readPath TEXT NOT NULL, expectedPayload TEXT, state TEXT NOT NULL, createdAt INTEGER NOT NULL, observedPayload TEXT, detail TEXT)")
            it.execSQL("INSERT INTO outbox VALUES ('old-operation','POST','/stock/products/1/add','{}','/stock/products/1',NULL,'pending',1,NULL,NULL)")
            it.version = 2
        }
        db = AccountDatabase(context, id, namespace)
        assertEquals("pending", db.operation("old-operation")!!.state)
        assertNull(db.operation("old-operation")!!.responsePayload)
        assertTrue(db.shoppingOperations().isEmpty())
    }

    @Test fun invalidRequestsCannotEnterDurableQueue() = runBlocking(Dispatchers.IO) {
        val repo = repository()
        for (path in listOf("//other.example", "/../user", "/%2e%2e/user", "/user#fragment")) {
            assertTrue(runCatching { repo.enqueue("POST", path, "{}", "/user") }.isFailure)
        }
        assertTrue(runCatching { repo.enqueue("GET", "/user", "{}", "/user") }.isFailure)
        assertTrue(runCatching { repo.enqueue("POST", "/user", "bad", "/user") }.isFailure)
        assertTrue(runCatching { repo.enqueue("POST", "/user", "{}", "/user", "{} trailing") }.isFailure)
        assertTrue(db.operations().isEmpty()); assertEquals(0, server.requestCount)
    }

    @Test fun absentCacheAndNoExpectedStateRemainExplicitlyUnresolved() = runBlocking(Dispatchers.IO) {
        val repo = repository()
        server.enqueue(MockResponse().setResponseCode(401))
        assertTrue(runCatching { repo.read("/user") }.isFailure)
        val operation = repo.enqueue("POST", "/objects/shopping_list", "{}", "/objects/shopping_list")
        server.enqueue(MockResponse().setResponseCode(500)); repo.drain()
        server.enqueue(MockResponse().setBody("[]")); repo.reconcile(operation)
        assertEquals("needs-review", db.operation(operation)!!.state)
        server.enqueue(MockResponse().setResponseCode(401)); assertTrue(runCatching { repo.reconcile(operation) }.isFailure)
        assertEquals("needs-review", db.operation(operation)!!.state)
        assertEquals("[]", db.operation(operation)!!.observedPayload)
    }

}
