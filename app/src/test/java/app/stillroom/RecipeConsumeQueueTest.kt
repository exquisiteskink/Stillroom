package app.stillroom

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.stillroom.data.AccountDatabase
import app.stillroom.data.CachedGrocyRepository
import app.stillroom.data.GrocyRecipeRepository
import app.stillroom.data.GrocyShoppingRepository
import app.stillroom.domain.AccountId
import app.stillroom.domain.ManageShopping
import app.stillroom.domain.ManageStock
import app.stillroom.domain.PendingChange
import app.stillroom.domain.ServerAddress
import app.stillroom.domain.StockBooking
import app.stillroom.domain.StockRead
import app.stillroom.domain.StockRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A crashed consume must queue only the missing lines, and a later confirmed cook can book again. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RecipeConsumeQueueTest {
    private lateinit var server: MockWebServer
    private lateinit var db: AccountDatabase
    private val namespace = "recipe_consume_queue"

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        server = MockWebServer().also { it.start(java.net.InetAddress.getByName("127.0.0.1"), 0) }
        val address = ServerAddress.parse("http://127.0.0.1:${server.port}", true)
        val id = AccountId.of(address, 1)
        AccountDatabase.delete(context, id, namespace)
        db = runBlocking(Dispatchers.IO) { AccountDatabase(context, id, namespace) }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringAfter("/api")
                val body = fixtures[path] ?: return MockResponse().setResponseCode(404)
                return MockResponse().setBody(body)
            }
        }
    }

    @After fun cleanup() {
        db.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        AccountDatabase.delete(context, AccountId.of(ServerAddress.parse("http://127.0.0.1:${server.port}", true), 1), namespace)
        server.shutdown()
    }

    @Test fun partialQueueRetriesMissingLinesAndAConfirmedCookCanRunAgain() = runBlocking(Dispatchers.IO) {
        val address = ServerAddress.parse("http://127.0.0.1:${server.port}", true)
        val cache = CachedGrocyRepository(db, address, "synthetic")
        val stock = FlakyStock(cache)
        val recipes = GrocyRecipeRepository(setOf("ADMIN"), db, cache, ManageStock(stock), ManageShopping(GrocyShoppingRepository(db, cache, stock, setOf("ADMIN"))))
        val review = recipes.review(1)
        assertEquals(2, review.lines.size)
        try { recipes.consume(review); fail("second booking should stop the loop") } catch (_: IllegalStateException) {}
        assertEquals(1, db.operations().count { it.path.startsWith("/stock/products/") })
        assertEquals(review.id, recipes.review(1).id)
        val ids = recipes.consume(review)
        assertEquals(2, ids.size)
        assertEquals(ids, recipes.consume(review))
        assertEquals(2, db.operations().count { it.path.startsWith("/stock/products/") })
        ids.forEach { id -> assertTrue(db.claim(id)); db.finish(id, "confirmed") }
        val again = recipes.review(1)
        assertNotEquals(review.id, again.id)
        val secondCook = recipes.consume(again)
        assertEquals(2, secondCook.size)
        assertTrue(secondCook.intersect(ids.toSet()).isEmpty())
        assertEquals(4, db.operations().count { it.path.startsWith("/stock/products/") })
    }

    private class FlakyStock(private val cache: CachedGrocyRepository) : StockRepository {
        private var books = 0
        private var fail = true
        override suspend fun read(path: String): StockRead = error("unused")
        override suspend fun book(booking: StockBooking, operationId: String?, guarded: Boolean): String {
            books++
            if (books == 2 && fail) { fail = false; error("stopped") }
            return cache.enqueue("POST", booking.path(), booking.payload().toString(), "/stock/products/${booking.productId}", operationId = operationId, guarded = guarded)
        }
        override suspend fun undo(bookingId: Long): String = error("unused")
        override suspend fun undoTransaction(transactionId: String): String = error("unused")
        override suspend fun operations(): List<PendingChange> = emptyList()
    }

    private val fixtures = mapOf(
        "/objects/recipes" to """[{"id":1,"name":"Soup","type":"normal","base_servings":1,"desired_servings":1}]""",
        "/objects/recipes_pos" to """[{"id":1,"recipe_id":1,"product_id":7,"amount":1,"qu_id":4},{"id":2,"recipe_id":1,"product_id":8,"amount":1,"qu_id":4}]""",
        "/objects/products" to """[{"id":7,"name":"A","qu_id_stock":4},{"id":8,"name":"B","qu_id_stock":4}]""",
        "/objects/quantity_units" to """[{"id":4,"name":"Piece"}]""",
        "/objects/quantity_unit_conversions_resolved" to "[]",
        "/objects/recipes_pos_resolved" to """[{"recipe_id":1,"recipe_pos_id":1,"product_id":7,"qu_id":4,"recipe_amount":1,"recipe_variable_amount":""},{"recipe_id":1,"recipe_pos_id":2,"product_id":8,"qu_id":4,"recipe_amount":1,"recipe_variable_amount":""}]""",
        "/objects/meal_plan" to "[]",
        "/objects/meal_plan_sections" to "[]",
        "/objects/shopping_lists" to "[]",
        "/objects/shopping_list" to "[]",
        "/stock" to """[{"product_id":7,"amount":5},{"product_id":8,"amount":5}]""",
    )
}
