package app.stillroom

import app.stillroom.domain.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PriorityOneTasksTest {
    private val address=ServerAddress.parse("example.org")
    private val account=Account(AccountId.of(address,4),address,4,"Member","4.7.1",setOf("ADMIN"))
    private val rows=Json.parseToJsonElement("""[{"id":1,"name":"Mine","assigned_to_user_id":4,"done":0,"due_date":"2026-10-09"},{"id":2,"name":"Everyone","done":0,"due_date":"2026-10-08"},{"id":3,"name":"Other","assigned_to_user_id":5,"done":0,"due_date":"2026-10-08"},{"id":4,"name":"Finished","assigned_to_user_id":4,"done":1},{"id":5,"name":"Future","assigned_to_user_id":4,"done":0,"due_date":"2027-01-01"},{"id":6,"name":"Undated","done":0}]""").jsonArray.map { it.jsonObject }
    @Test fun completedViewKeepsPersonalAssignmentPolicy() {
        assertEquals(listOf("Finished"),tasksForUser(rows,account,completed=true).map { it.houseText("name") })
        assertTrue(tasksForUser(rows,account.copy(permissions=emptySet()),completed=true).isEmpty())
    }
    @Test fun remindersExcludeFutureUndatedCompletedAndOtherMembers() {
        val snapshot=TodaySnapshot(account=account,tasks=rows)
        assertEquals(setOf("Mine","Everyone"),duePersonalTasks(snapshot,LocalDate.parse("2026-10-09")).map { it.houseText("name") }.toSet())
        assertTrue(duePersonalTasks(snapshot.copy(accessDenied=true),LocalDate.now()).isEmpty())
    }
    @Test fun completedRecordsAreRetainedSeparatelyInTodayCache() {
        val snapshot=CachedToday.build(account,mapOf("/tasks" to JsonArray(rows).toString()),LocalDate.parse("2026-10-09"))
        assertEquals(listOf(4L),snapshot.completedTasks.map { it.houseId("id") })
        assertFalse(snapshot.tasks.any { it.houseId("id")==4L })
    }
}
