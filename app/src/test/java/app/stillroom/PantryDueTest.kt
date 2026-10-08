package app.stillroom

import app.stillroom.domain.PantryDueUrgency
import app.stillroom.domain.pantryDue
import app.stillroom.domain.pantryRunningLowIds
import app.stillroom.domain.pantryUseSoonIds
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
    }
}
