package app.stillroom

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.stillroom.data.*
import app.stillroom.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], application=android.app.Application::class)
class HouseholdIntegrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun operations(db: AccountDatabase) = db.operations().map { PendingChange(it.clientOperationId,it.method,it.path,it.state,it.detail,false) }
    @Test fun childIdentityDenialAssignmentAndUnknownOutcome() = runBlocking(Dispatchers.IO) {
        val server=MockWebServer(); server.start(java.net.InetAddress.getByName("127.0.0.1"),0)
        val address=ServerAddress.parse("http://127.0.0.1:${server.port}",true)
        val id=AccountId.of(address,7); AccountDatabase.delete(context,id,"house_test")
        val db=AccountDatabase(context,id,"house_test")
        try {
            val cache=CachedGrocyRepository(db,address,"child-test-key",MutationTransport(200))
            val child=GrocyHouseholdRepository(setOf("CHORES","CHORE_TRACK_EXECUTION"),7,cache) { operations(db) }
            suspend fun denied(action: suspend () -> Unit) { try { action(); fail("Child action allowed") } catch (_: IllegalStateException) {} }
            denied { child.save(null,JsonObject(emptyMap())) }; denied { child.delete(1) }; denied { child.history(1) }; denied { child.completeTask(1) }
            assertEquals(0,server.requestCount)
            server.enqueue(MockResponse().setBody("""{"next_execution_assigned_user":{"id":8}}"""))
            denied { child.completeChore(1) }; assertTrue(db.operations().isEmpty()); server.takeRequest()
            server.enqueue(MockResponse().setBody("""{"next_execution_assigned_user":{"id":7}}"""))
            val operation=child.completeChore(1); server.takeRequest()
            assertEquals("pending",db.operation(operation)!!.state)
            server.enqueue(MockResponse().setBody("{}").setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)); child.sync()
            val request=server.takeRequest()
            assertEquals("child-test-key",request.getHeader("GROCY-API-KEY"))
            assertEquals("{\"done_by\":7}",request.body.readUtf8())
            assertEquals("needs-review",db.operation(operation)!!.state)
            child.sync(); assertEquals(3,server.requestCount)
            server.enqueue(MockResponse().setBody("""{"next_execution_assigned_user":{"id":7}}"""))
            denied { child.completeChore(1) }; assertEquals(1,db.operations().size)
        } finally { db.close(); AccountDatabase.delete(context,id,"house_test"); server.shutdown() }
    }
    @Test fun cachedReadsRemainFilteredAndDenialsNeverUseStaleChores() = runBlocking(Dispatchers.IO) {
        val server = MockWebServer()
        server.start(java.net.InetAddress.getByName("127.0.0.1"),0)
        val address = ServerAddress.parse("http://127.0.0.1:${server.port}",true)
        val id = AccountId.of(address,7)
        AccountDatabase.delete(context,id,"house_cache")
        val db = AccountDatabase(context,id,"house_cache")
        try {
            val cache = CachedGrocyRepository(db,address,"child-test-key",MutationTransport(200))
            val child = GrocyHouseholdRepository(setOf("CHORES"),7,cache) { operations(db) }
            val master = """[{"id":1,"description":"Instructions","active":1},{"id":2,"active":1},{"id":3,"active":0}]"""
            val current = """[{"chore_id":1,"next_execution_assigned_to_user_id":7},{"chore_id":2,"next_execution_assigned_to_user_id":8},{"chore_id":3,"next_execution_assigned_to_user_id":7}]"""
            server.enqueue(MockResponse().setBody(master))
            server.enqueue(MockResponse().setBody(current))
            val fresh = child.snapshot()
            assertFalse(fresh.stale)
            assertEquals(listOf(1L),fresh.chores.map { it.houseId("chore_id") })
            assertEquals("Instructions",fresh.chores.single().houseText("description"))
            assertTrue(fresh.tasks.isEmpty());assertTrue(fresh.users.isEmpty())
            server.enqueue(MockResponse().setResponseCode(500));server.enqueue(MockResponse().setResponseCode(500))
            assertTrue(child.snapshot().stale)
            server.enqueue(MockResponse().setResponseCode(403))
            try { child.snapshot(); fail("Denied cached chores displayed") } catch(e:GrocyFailure) { assertEquals(403,e.status) }
            val unknown = GrocyHouseholdRepository(null,7,cache) { operations(db) }
            assertTrue(unknown.snapshot().chores.isEmpty())
            assertEquals(5,server.requestCount)
        } finally { db.close();AccountDatabase.delete(context,id,"house_cache");server.shutdown() }
    }

    @Test fun liveLocalChoreChildLogTasksAndParentCrud() = runBlocking(Dispatchers.IO) {
        val path=System.getenv("STILLROOM_STAGE8_FIXTURES")
        Assume.assumeTrue("Existing local fixture supplied by stage:8",path!=null)
        val fixture=Json.parseToJsonElement(File(path!!).readText()).jsonObject["fixtures"]!!.jsonArray.first().jsonObject
        val address=ServerAddress.parse(fixture.houseText("base_url"),true)
        val childId=fixture.houseId("child_user_id")!!
        val transport=MutationTransport()
        suspend fun http(method:String,path:String,body:String="{}"): JsonElement {
            val (status,payload)=transport.request(address,fixture.houseText("parent_key"),method,path,body)
            assertTrue("Local Grocy $method $path HTTP $status",status in 200..299)
            return if(payload.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(payload)
        }
        val parentId=AccountId.of(address,fixture.houseId("parent_user_id")!!)
        val childAccountId=AccountId.of(address,childId)
        AccountDatabase.delete(context,parentId,"stage8_live"); AccountDatabase.delete(context,childAccountId,"stage8_live")
        val parentDb=AccountDatabase(context,parentId,"stage8_live"); val childDb=AccountDatabase(context,childAccountId,"stage8_live")
        val parent=ManageHousehold(GrocyHouseholdRepository(setOf("ADMIN"),fixture.houseId("parent_user_id")!!,CachedGrocyRepository(parentDb,address,fixture.houseText("parent_key"))) { operations(parentDb) })
        val child=ManageHousehold(GrocyHouseholdRepository(setOf("CHORES","CHORE_TRACK_EXECUTION"),childId,CachedGrocyRepository(childDb,address,fixture.houseText("child_key"))) { operations(childDb) })
        val name="Stage8 synthetic ${java.util.UUID.randomUUID()}"
        var choreId:Long?=null; var categoryId:Long?=null; var taskId:Long?=null
        fun draft(title:String,assignee:Long)=buildJsonObject {
            put("name",title);put("description","Wash with warm water.");put("period_type","daily");put("period_interval",2);put("period_days",0);put("start_date","2026-10-01 09:00:00");put("assignment_type","in-alphabetical-order");put("assignment_config",assignee.toString());put("track_date_only",0);put("rollover",0)
        }
        suspend fun confirm(operation:String,db:AccountDatabase,repo:ManageHousehold) { assertEquals("pending",db.operation(operation)!!.state);repo.sync();assertEquals("confirmed",db.operation(operation)!!.state) }
        try {
            confirm(parent.save(null,draft(name,childId)),parentDb,parent)
            choreId=http("GET","/objects/chores").jsonArray.first { it.jsonObject.houseText("name")==name }.jsonObject.houseId("id")!!
            val initial=child.snapshot().chores.single { it.houseId("chore_id")==choreId }
            assertEquals(childId,initial.houseId("next_execution_assigned_to_user_id")); assertEquals("Wash with warm water.",initial.houseText("description"))
            confirm(child.completeChore(choreId!!),childDb,child)
            val history=parent.history(choreId!!);assertEquals(1,history.size);assertEquals(childId,history.single().houseId("done_by_user_id"))
            val details=http("GET","/chores/$choreId").jsonObject
            val after=child.snapshot().chores.single { it.houseId("chore_id")==choreId }
            assertEquals(details.houseText("next_estimated_execution_time"),after.houseText("next_estimated_execution_time"))
            assertNotEquals(initial.houseText("next_estimated_execution_time"),after.houseText("next_estimated_execution_time"))
            val update=JsonObject(draft(name+" edited",childId)+mapOf("rescheduled_next_execution_assigned_to_user_id" to JsonPrimitive(fixture.houseId("parent_user_id")!!)))
            confirm(parent.save(choreId,update),parentDb,parent)
            assertTrue(child.snapshot().chores.none { it.houseId("chore_id")==choreId })
            assertEquals(name+" edited",http("GET","/objects/chores/$choreId").jsonObject.houseText("name"))
            categoryId=http("POST","/objects/task_categories",buildJsonObject { put("name",name) }.toString()).jsonObject.houseId("created_object_id")!!
            taskId=http("POST","/objects/tasks",buildJsonObject { put("name",name);put("category_id",categoryId!!) }.toString()).jsonObject.houseId("created_object_id")!!
            assertEquals(categoryId,parent.snapshot().tasks.single { it.houseId("id")==taskId }.houseId("category_id"))
            assertTrue(parent.snapshot().categories.any { it.houseId("id")==categoryId })
            confirm(parent.completeTask(taskId!!),parentDb,parent)
            assertEquals("1",http("GET","/objects/tasks/$taskId").jsonObject.houseText("done"))
            val report=buildJsonObject { put("version",fixture.houseText("version"));put("child_user_id",childId);put("log_done_by",history.single().houseId("done_by_user_id")!!);put("next_due",after.houseText("next_estimated_execution_time"));put("parent_crud_reassignment_tasks",true) }
            File("build/reports/stage8").mkdirs();File("build/reports/stage8/local-parity.json").writeText(report.toString()+"\n")
            confirm(parent.delete(choreId!!),parentDb,parent)
            assertTrue(http("GET","/objects/chores").jsonArray.none { it.jsonObject.houseId("id")==choreId });choreId=null
        } finally {
            choreId?.let { http("DELETE","/objects/chores/$it") }
            // Grocy retains execution history when master records are deleted; remove only this test's log.
            http("GET","/objects/chores_log").jsonArray.filter { it.jsonObject.houseId("done_by_user_id")==childId }.forEach { http("DELETE","/objects/chores_log/${it.jsonObject.houseId("id")}") }
            taskId?.let { http("DELETE","/objects/tasks/$it") };categoryId?.let { http("DELETE","/objects/task_categories/$it") }
            parentDb.close();childDb.close();AccountDatabase.delete(context,parentId,"stage8_live");AccountDatabase.delete(context,childAccountId,"stage8_live")
        }
    }
}
