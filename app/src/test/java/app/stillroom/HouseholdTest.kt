package app.stillroom
import app.stillroom.domain.*
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*
import java.time.LocalDate

class HouseholdTest {
    @Test fun grantsAreExplicitAndUnknownGrantsCannotExposeHousehold() {
        assertFalse(HouseholdAccess.has(null, "CHORES"))
        assertFalse(HouseholdAccess.parent(setOf("CHORES","CHORE_TRACK_EXECUTION")))
        assertTrue(HouseholdAccess.parent(setOf("MASTER_DATA_EDIT")))
        assertTrue(HouseholdAccess.has(setOf("ADMIN"), "TASKS_MARK_COMPLETED"))
        assertFalse(HouseholdAccess.has(emptySet(), "TASKS"))
        assertTrue(HouseholdAccess.canReadUsers(setOf("ADMIN")))
        assertTrue(HouseholdAccess.canReadUsers(setOf("USERS")))
        assertTrue(HouseholdAccess.canReadUsers(setOf("USERS_READ")))
        assertFalse(HouseholdAccess.canReadUsers(setOf("MASTER_DATA_EDIT", "CHORES")))
        assertFalse(HouseholdAccess.canReadUsers(null))
    }
    @Test fun serverDatesAndAssignmentControlChildGroups() {
        fun row(id: Int, due: String, user: Int) = Json.parseToJsonElement("""{"chore_id":$id,"next_estimated_execution_time":"$due","next_execution_assigned_to_user_id":$user}""").jsonObject
        val rows = listOf(row(1,"2026-10-06 23:59:59",7), row(2,"2026-10-07 23:59:59",7), row(3,"2026-10-08 00:00:00",7), row(4,"2026-10-06 00:00:00",8))
        val groups = choreGroups(rows, 7, LocalDate.parse("2026-10-07"))
        assertEquals(listOf(1,2,3), groups.values.flatten().map { it["chore_id"]!!.jsonPrimitive.int })
        assertEquals(1, groups.getValue("Overdue").size)
        assertEquals(1, groups.getValue("Today").size)
        assertEquals(1, groups.getValue("Upcoming").size)
    }
    @Test fun unknownAndManualDueDatesStayUpcoming() {
        val rows = listOf(Json.parseToJsonElement("""{"chore_id":1,"next_estimated_execution_time":null,"next_execution_assigned_to_user_id":7}""").jsonObject)
        assertEquals(rows, choreGroups(rows, 7, LocalDate.now()).getValue("Upcoming"))
        assertTrue(choreGroups(rows,8,LocalDate.now()).values.all { it.isEmpty() })
    }

    @Test fun dueDayHidesTime() {
        val locale = java.util.Locale.US
        assertEquals("Oct 7", houseDueDay("2026-10-07 23:59:59", locale))
        assertEquals("Oct 7", houseDueDay("2026-10-07", locale))
        assertNull(houseDueDay("", locale))
        assertNull(houseDueDay("null", locale))
    }

    @Test fun membersFallBackToAssignedPeopleWhenUserListIsEmpty() {
        val assigned = Json.parseToJsonElement("""{"id":7,"display_name":"Sam","username":"sam"}""").jsonObject
        val chore = Json.parseToJsonElement("""{"chore_id":1,"next_execution_assigned_user":{"id":7,"display_name":"Sam","username":"sam"}}""").jsonObject
        val loaded = Json.parseToJsonElement("""{"id":2,"display_name":"Admin","username":"admin"}""").jsonObject
        assertEquals(listOf(7L), householdMembers(emptyList(), listOf(chore)).map { it.houseId("id") })
        assertEquals(listOf(2L), householdMembers(listOf(loaded), listOf(chore)).map { it.houseId("id") })
        assertEquals("Sam", householdAssigneeName(assigned))
    }
}
