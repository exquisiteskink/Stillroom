package app.stillroom

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.stillroom.data.*
import app.stillroom.domain.*
import java.math.BigDecimal
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Production catalog repository + Room outbox against a MockWebServer standing in for Grocy. Not a live server test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ProductEditorIntegrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    companion object { private const val spec = """{"components":{"schemas":{"ExposedEntity":{"enum":["products","locations","quantity_units","userfields","product_groups","product_barcodes","quantity_unit_conversions"]}}}}""" }

    private class FakeGrocy(val userfieldStatus: Int = 204) : Dispatcher() {
        val writes = mutableListOf<Pair<String, String>>()
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path!!.removePrefix("/api")
            val body = request.body.readUtf8()
            if (request.method != "GET") writes += "${request.method} $path" to body
            return when {
                request.method == "GET" && path == "/openapi/specification" -> MockResponse().setBody(spec)
                request.method == "GET" && path == "/objects/locations" -> MockResponse().setBody("""[{"id":3,"name":"Fridge"}]""")
                request.method == "GET" && path == "/objects/quantity_units" -> MockResponse().setBody("""[{"id":4,"name":"Piece"},{"id":5,"name":"Pack"}]""")
                request.method == "GET" && path == "/objects/userfields" -> MockResponse().setBody("""[{"entity":"products","name":"brand","caption":"Brand","type":"text-single-line"},{"entity":"products","name":"label","caption":"Label","type":"image","input_required":0}]""")
                request.method == "GET" && path == "/objects/products" -> MockResponse().setBody("[]")
                request.method == "GET" && path == "/objects/products/41" -> MockResponse().setBody("""{"id":41,"name":"Oat milk","qu_id_stock":4,"qu_id_purchase":5}""")
                request.method == "GET" && path == "/objects/quantity_unit_conversions" -> MockResponse().setBody("""[{"id":77,"product_id":41,"from_qu_id":5,"to_qu_id":4,"factor":1}]""")
                request.method == "GET" -> MockResponse().setBody("[]")
                request.method == "POST" && path == "/objects/products" -> MockResponse().setBody("""{"created_object_id":"41"}""")
                path.startsWith("/userfields/") -> if (userfieldStatus == 204) MockResponse().setResponseCode(204) else MockResponse().setResponseCode(userfieldStatus).setBody("""{"error_message":"Brand is not allowed"}""")
                else -> MockResponse().setBody("""{"created_object_id":"1"}""")
            }
        }
    }

    private fun withRepo(name: String, grocy: FakeGrocy, grants: Set<String> = setOf("ADMIN"), block: suspend (ManageCatalog, AccountDatabase) -> Unit) = runBlocking(Dispatchers.IO) {
        val server = MockWebServer(); server.dispatcher = grocy; server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        val address = ServerAddress.parse("http://127.0.0.1:${server.port}", true); val id = AccountId.of(address, 1)
        AccountDatabase.delete(context, id, name); val db = AccountDatabase(context, id, name)
        try { block(ManageCatalog(GrocyCatalogRepository(grants, db, CachedGrocyRepository(db, address, "private", MutationTransport(2000)))), db) }
        finally { db.close(); AccountDatabase.delete(context, id, name); server.shutdown() }
    }

    private val fields = buildJsonObject { put("name", "Oat milk"); put("location_id", 3); put("qu_id_stock", 4); put("qu_id_purchase", 5); put("qu_id_consume", 4); put("qu_id_price", 5) }

    @Test fun followUpsAreSentAfterTheCreateAndOnlyOnce() {
        val grocy = FakeGrocy()
        withRepo("product_sequence", grocy) { catalog, _ ->
            assertEquals(CatalogSaveOutcome.Saved(41), catalog.saveAndSync(CatalogEntity.Products, null, fields, buildJsonObject { put("brand", "Oatly") }, ProductExtras(BigDecimal(6), listOf("7310865004703"))))
            val order = grocy.writes.map { it.first }
            assertEquals(listOf("POST /objects/products", "PUT /userfields/products/41", "PUT /objects/quantity_unit_conversions/77", "POST /objects/product_barcodes"), order)
            assertEquals("""{"brand":"Oatly"}""", grocy.writes[1].second)
            assertEquals("""{"factor":6}""", grocy.writes[2].second)
            assertEquals("""{"product_id":41,"barcode":"7310865004703"}""", grocy.writes[3].second)
            catalog.sync(); catalog.sync()
            assertEquals(4, grocy.writes.size) // a resync never repeats a follow-up
        }
    }

    @Test fun failedUserfieldWriteIsReportedWithGrocysReasonAndTheProductIsKept() {
        val grocy = FakeGrocy(userfieldStatus = 400)
        withRepo("product_partial", grocy) { catalog, _ ->
            val outcome = catalog.saveAndSync(CatalogEntity.Products, null, fields, buildJsonObject { put("brand", "Oatly") })
            assertTrue(outcome.toString(), outcome is CatalogSaveOutcome.Partial && outcome.id == 41L && outcome.message.contains("Brand is not allowed"))
            assertEquals(1, grocy.writes.count { it.first == "POST /objects/products" })
            catalog.sync()
            assertEquals(1, grocy.writes.count { it.first.startsWith("PUT /userfields") }) // failed stays failed, visible, not retried silently
            // Retry as an edit of the created product: only the userfields are sent.
            assertEquals(CatalogSaveOutcome.Partial::class, catalog.saveAndSync(CatalogEntity.Products, 41, JsonObject(emptyMap()), buildJsonObject { put("brand", "Oatly") })::class)
            assertEquals(1, grocy.writes.count { it.first == "POST /objects/products" })
        }
    }

    @Test fun offlineSaveQueuesNothingAndKeepsTheForm() = runBlocking(Dispatchers.IO) {
        val address = ServerAddress.parse("http://127.0.0.1:9", true); val id = AccountId.of(address, 1)
        AccountDatabase.delete(context, id, "product_offline"); val db = AccountDatabase(context, id, "product_offline")
        try {
            val catalog = ManageCatalog(GrocyCatalogRepository(setOf("ADMIN"), db, CachedGrocyRepository(db, address, "private", MutationTransport(500))))
            val outcome = catalog.saveAndSync(CatalogEntity.Products, null, fields, JsonObject(emptyMap()))
            assertTrue(outcome is CatalogSaveOutcome.Failed)
            assertTrue(db.operations().isEmpty())
        } finally { db.close(); AccountDatabase.delete(context, id, "product_offline") }
    }

    @Test fun readOnlyAccountsCannotSaveAndFilesAreNeverWritten() {
        val grocy = FakeGrocy()
        withRepo("product_denied", grocy, grants = setOf("STOCK")) { catalog, db ->
            assertFalse(CatalogEntity.Products.writable(setOf("STOCK")))
            assertTrue(catalog.saveAndSync(CatalogEntity.Products, null, fields) is CatalogSaveOutcome.Failed)
            assertTrue(db.operations().isEmpty())
        }
        withRepo("product_file", grocy) { catalog, db ->
            assertTrue(catalog.saveAndSync(CatalogEntity.Products, 41, JsonObject(emptyMap()), buildJsonObject { put("label", "x_eA==") }) is CatalogSaveOutcome.Failed)
            assertTrue(db.operations().isEmpty())
        }
    }
}
