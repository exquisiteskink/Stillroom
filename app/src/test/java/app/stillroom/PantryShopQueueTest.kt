package app.stillroom

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.stillroom.data.AccountDatabase
import app.stillroom.data.CachedGrocyRepository
import app.stillroom.data.GrocyShoppingRepository
import app.stillroom.domain.AccountId
import app.stillroom.domain.ManageShopping
import app.stillroom.domain.PendingChange
import app.stillroom.domain.ServerAddress
import app.stillroom.domain.StockBooking
import app.stillroom.domain.StockRead
import app.stillroom.domain.StockRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Bulk shopping routes are sent once. A needs-review result is not posted again. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PantryShopQueueTest {
    private val lists = """[{"id":1,"name":"Groceries"}]"""
    private val products = """[{"id":9,"name":"Milk","qu_id_stock":4},{"id":10,"name":"Bread","qu_id_stock":4}]"""
    private val volatile = """{"due_products":[{"product_id":9},{"product_id":10}],"overdue_products":[{"product_id":10}],"expired_products":[],"missing_products":[{"product_id":4}]}"""

    @Test fun runningLowPostsAddMissingOnceAndDoesNotReplayANeedsReview() = runBlocking(Dispatchers.IO) {
        withServer(missingStatus = 500) { shopping, posts ->
            shopping.addPantryAttention("running-low", 1)
            assertEquals(listOf("/stock/shoppinglist/add-missing-products"), posts.toList())
            try { shopping.addPantryAttention("running-low", 1); fail("needs-review was posted again") } catch (error: IllegalStateException) {
                assertTrue(error.message!!.contains("Pending changes"))
            }
            assertEquals(1, posts.size)
        }
    }

    @Test fun useSoonPostsOverdueExpiredAndANormalRowForDueOnly() = runBlocking(Dispatchers.IO) {
        withServer(missingStatus = 204) { shopping, posts ->
            shopping.addPantryAttention("use-soon", 1)
            assertTrue(posts.contains("/stock/shoppinglist/add-overdue-products"))
            assertTrue(posts.contains("/stock/shoppinglist/add-expired-products"))
            assertTrue(posts.contains("/objects/shopping_list"))
            val added = posts.count { it == "/objects/shopping_list" }
            assertEquals(1, added)
        }
    }

    private suspend fun withServer(missingStatus: Int, block: suspend (ManageShopping, MutableList<String>) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val server = MockWebServer()
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        val address = ServerAddress.parse("http://127.0.0.1:${server.port}", true)
        val id = AccountId.of(address, 1)
        val namespace = "pantry_shop_${missingStatus}_${server.port}"
        AccountDatabase.delete(context, id, namespace)
        val db = AccountDatabase(context, id, namespace)
        val posts = mutableListOf<String>()
        var created = """{"id":50,"shopping_list_id":1,"product_id":9,"amount":1,"qu_id":4,"note":"","done":0}"""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringAfter("/api").substringBefore("?")
                if (request.method != "GET") {
                    posts += path
                    if (path == "/stock/shoppinglist/add-missing-products") return MockResponse().setResponseCode(missingStatus).setBody("{}")
                    if (path == "/objects/shopping_list") {
                        val sent = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        created = """{"id":50,"shopping_list_id":${sent["shopping_list_id"]!!.jsonPrimitive.content},"product_id":${sent["product_id"]!!.jsonPrimitive.content},"amount":${sent["amount"]!!.jsonPrimitive.content},"qu_id":${sent["qu_id"]!!.jsonPrimitive.content},"note":"","done":0}"""
                        return MockResponse().setBody("""{"created_object_id":50}""")
                    }
                    return MockResponse().setResponseCode(204)
                }
                val body = when (path) {
                    "/objects/shopping_lists" -> lists
                    "/objects/shopping_list" -> "[]"
                    "/objects/products" -> products
                    "/objects/quantity_units", "/objects/quantity_unit_conversions_resolved", "/objects/shopping_locations", "/objects/product_groups", "/objects/products_last_purchased", "/objects/locations" -> "[]"
                    "/stock/volatile" -> volatile
                    "/objects/shopping_list/50" -> created
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setBody(body)
            }
        }
        val cache = CachedGrocyRepository(db, address, "synthetic")
        val stock = object : StockRepository {
            override suspend fun read(path: String): StockRead = error("unused")
            override suspend fun book(booking: StockBooking, operationId: String?, guarded: Boolean): String = error("unused")
            override suspend fun undo(bookingId: Long): String = error("unused")
            override suspend fun undoTransaction(transactionId: String): String = error("unused")
            override suspend fun operations(): List<PendingChange> = emptyList()
        }
        try { block(ManageShopping(GrocyShoppingRepository(db, cache, stock, setOf("ADMIN"))), posts) }
        finally { db.close(); AccountDatabase.delete(context, id, namespace); server.shutdown() }
    }
}
