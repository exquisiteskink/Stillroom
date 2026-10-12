package app.stillroom

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.stillroom.data.*
import app.stillroom.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A confirmed creation is followed up exactly once; a later sync must not re-send it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ScanFollowUpIdempotenceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var db: AccountDatabase? = null

    @After fun cleanup() { db?.close(); db?.let { AccountDatabase.delete(context, it.identity(), "scan_followup") }; db = null }

    private fun AccountDatabase.identity() = AccountId.of(ServerAddress.parse("http://127.0.0.1:1", true), 1)

    @Test fun repeatedSyncDoesNotRepostConfirmedProductOrBarcode() = runBlocking(Dispatchers.IO) {
        val server = MockWebServer(); server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        val posted = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!.removePrefix("/api")
                if (request.method != "POST") return MockResponse().setBody(when (path) {
                    "/objects/quantity_units" -> """[{"id":4,"name":"Piece"}]"""
                    "/objects/locations" -> """[{"id":1,"name":"Shelf"}]"""
                    else -> "[]"
                })
                synchronized(posted) { posted += "$path ${request.body.readUtf8()}" }
                return when {
                    path == "/objects/products" -> MockResponse().setBody("""{"created_object_id":"55"}""")
                    else -> MockResponse().setResponseCode(204)
                }
            }
        }
        val address = ServerAddress.parse("http://127.0.0.1:${server.port}", true)
        val id = AccountId.of(address, 1); AccountDatabase.delete(context, id, "scan_followup")
        db = AccountDatabase(context, id, "scan_followup")
        try {
            val cache = CachedGrocyRepository(db!!, address, "key", MutationTransport(2000))
            val repo = GrocyScanRepository(setOf("ADMIN"), db!!, cache, { error("No external lookup") }, publicFallbackEnabled = false)
            val code = ScanCode("00123457", ScanFormat.Ean8)
            val operation = repo.create(ScanReview(code, "Test tea", "", 4, 1))
            repo.sync()
            assertEquals(55L, repo.createdProduct(operation))
            val afterFirst = synchronized(posted) { posted.toList() }
            assertEquals(1, afterFirst.count { it.startsWith("/objects/products ") })
            assertEquals(1, afterFirst.count { it.startsWith("/objects/product_barcodes ") })
            // A second sync finds the same confirmed operations and must not send anything again.
            repo.sync()
            repo.sync()
            val afterThird = synchronized(posted) { posted.toList() }
            assertEquals("sync replayed a confirmed creation", afterFirst, afterThird)
        } finally { server.shutdown() }
    }
}
