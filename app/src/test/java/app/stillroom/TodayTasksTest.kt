package app.stillroom

import app.stillroom.domain.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class TodayTasksTest {
    private val address = ServerAddress.parse("example.org")
    private val account = Account(AccountId.of(address,4),address,4,"Member","4.7.1",setOf("TASKS"))
    private val tasks = """[
        {"id":1,"name":"Mine","assigned_to_user_id":4,"done":0,"due_date":"2026-10-09"},
        {"id":2,"name":"Everyone","assigned_to_user_id":null,"done":0},
        {"id":3,"name":"Other","assigned_to_user_id":5,"done":0},
        {"id":4,"name":"Finished","assigned_to_user_id":4,"done":1},
        {"id":5,"name":"Future","assigned_to_user_id":4,"done":0,"due_date":"2027-01-01"}
    ]"""
    private fun build(user: Account = account, records: String = tasks) = CachedToday.build(user,mapOf("/tasks" to records),LocalDate.parse("2026-10-09"))
    @Test fun currentUserAndEveryoneAppearIncludingFutureTasks() {
        assertEquals(setOf("Mine","Everyone","Future"),build().tasks.map { it.houseText("name") }.toSet())
        assertTrue("/tasks" in CachedToday.paths(account))
    }
    @Test fun administratorsStillOnlySeeTheirOwnAndEveryoneTasks() {
        assertEquals(setOf("Mine","Everyone","Future"),build(account.copy(permissions=setOf("ADMIN"))).tasks.map { it.houseText("name") }.toSet())
    }
    @Test fun accountSwitchChangesAssignedTasks() {
        val other = account.copy(id=AccountId.of(address,5),userId=5)
        assertEquals(setOf("Everyone","Other"),build(other).tasks.map { it.houseText("name") }.toSet())
    }
    @Test fun deniedAndUnknownPermissionsNeverUseCachedTasks() {
        listOf(null,emptySet(),setOf("CHORES")).forEach { grants ->
            val user=account.copy(permissions=grants)
            assertFalse("/tasks" in CachedToday.paths(user));assertTrue(build(user).tasks.isEmpty())
        }
    }
    @Test fun missingTaskCacheIsReported() {
        assertEquals(listOf("/tasks"),CachedToday.build(account,emptyMap(),LocalDate.now()).missing)
    }
    @Test fun embeddedAssignmentCannotLeakOtherUsersTasks() {
        val records="""[{"id":1,"name":"Other","done":0,"assigned_to_user":{"id":5}},{"id":2,"name":"Mine","done":0,"assigned_to_user":{"id":4}}]"""
        assertEquals(listOf("Mine"),build(records=records).tasks.map { it.houseText("name") })
    }
}
