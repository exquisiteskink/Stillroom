package app.stillroom

import app.stillroom.domain.PantryDueUrgency
import app.stillroom.domain.PantryListAdd
import app.stillroom.domain.RecipeSnapshot
import app.stillroom.domain.pantryDue
import app.stillroom.domain.pantryDueNotOverdueIds
import app.stillroom.domain.pantryListRequest
import app.stillroom.domain.pantryRunningLowIds
import app.stillroom.domain.pantryUseSoonIds
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class PantryDueTest {
    private val today = LocalDate.of(2026, 10, 7)
    private val locale = Locale.US

    @Test fun blankAndNeverExpireAreHidden() {
        assertNull(pantryDue("", today, locale))
        assertNull(pantryDue("2999-12-31", today, locale))
    }

    @Test fun overdueUsesShortDate() {
        val due = pantryDue("2026-10-02", today, locale)!!
        assertEquals("Oct 2", due.text)
        assertEquals(PantryDueUrgency.Overdue, due.urgency)
    }

    @Test fun todayAndNextThreeDaysAreSoon() {
        assertEquals(PantryDueUrgency.Soon, pantryDue("2026-10-07", today, locale)!!.urgency)
        assertEquals(PantryDueUrgency.Soon, pantryDue("2026-10-10", today, locale)!!.urgency)
        assertEquals("Oct 7", pantryDue("2026-10-07", today, locale)!!.text)
    }

    @Test fun laterDatesStayNeutral() {
        val due = pantryDue("2026-10-11", today, locale)!!
        assertEquals("Oct 11", due.text)
        assertEquals(PantryDueUrgency.None, due.urgency)
    }

    @Test fun useSoonUnionsDueOverdueAndExpired() {
        val volatile = Json.parseToJsonElement(
            """{"due_products":[{"product_id":"5"}],"overdue_products":[{"product_id":5}],"expired_products":[{"product_id":6}],"missing_products":[{"product_id":4}]}""",
        ).jsonObject
        assertEquals(setOf("5", "6"), pantryUseSoonIds(volatile))
        assertEquals(setOf("4"), pantryRunningLowIds(volatile))
        assertEquals(emptySet<String>(), pantryUseSoonIds(null))
        assertEquals(emptySet<String>(), pantryDueNotOverdueIds(volatile))
    }

    @Test fun useSoonShopPlanSeparatesDueFromOverdueAndExpired() {
        val volatile = Json.parseToJsonElement(
            """{"due_products":[{"product_id":5},{"product_id":9}],"overdue_products":[{"product_id":5}],"expired_products":[{"product_id":6}],"missing_products":[{"product_id":4}]}""",
        ).jsonObject
        assertEquals(setOf("9"), pantryDueNotOverdueIds(volatile))
        val soon = pantryListRequest(volatile, "use-soon")
        assertEquals(listOf(PantryListAdd.Overdue, PantryListAdd.Expired), soon.bulk)
        assertEquals(listOf(9L), soon.dueProductIds)
        val low = pantryListRequest(volatile, "running-low")
        assertEquals(listOf(PantryListAdd.Missing), low.bulk)
        assertEquals(emptyList<Long>(), low.dueProductIds)
    }

    @Test fun fulfillmentBadgeDoesNotImplyAStockBooking() {
        val fulfilled = buildJsonObject { put("recipe_id", 3); put("need_fulfilled", 1); put("missing_products_count", 0) }
        val missing = buildJsonObject { put("recipe_id", 4); put("need_fulfilled", 0); put("missing_products_count", 2) }
        val snapshot = RecipeSnapshot(mapOf("recipes_fulfillment" to listOf(fulfilled, missing)))
        assertEquals("In stock", snapshot.fulfillmentBadge(3))
        assertEquals("Missing 2", snapshot.fulfillmentBadge(4))
        assertEquals(null, snapshot.fulfillmentBadge(9))
    }
}
