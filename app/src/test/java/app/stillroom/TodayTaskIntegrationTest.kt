package app.stillroom

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.stillroom.data.*
import app.stillroom.domain.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], application=android.app.Application::class)
class TodayTaskIntegrationTest {
    @Test fun homepageUsesHouseholdCacheAndCompletionQueueWithoutDuplicateWrites()=runBlocking(Dispatchers.IO) {
        val context:Context=ApplicationProvider.getApplicationContext()
        val server=MockWebServer();server.start(java.net.InetAddress.getByName("127.0.0.1"),0)
        val address=ServerAddress.parse("http://127.0.0.1:${server.port}",true)
        val account=Account(AccountId.of(address,4),address,4,"Member","4.7.1",setOf("TASKS","TASKS_MARK_COMPLETED"))
        AccountDatabase.delete(context,account.id,"today_task_test")
        val db=AccountDatabase(context,account.id,"today_task_test")
        try {
            val cache=CachedGrocyRepository(db,address,"synthetic",MutationTransport(300))
            val today=CachedTodayRepository(account,db)
            val household=GrocyHouseholdRepository(account.permissions,4,cache) {
                db.operations().map { PendingChange(it.clientOperationId,it.method,it.path,it.state,it.detail,false) }
            }
            val open="""[{"id":1,"name":"Mine","assigned_to_user_id":4,"done":0},{"id":2,"name":"Everyone","assigned_to_user_id":null,"done":0},{"id":3,"name":"Other","assigned_to_user_id":5,"done":0}]"""
            server.enqueue(MockResponse().setBody(open));today.refresh(cache)
            assertEquals("/api/tasks",server.takeRequest(1,TimeUnit.SECONDS)!!.path)
            assertEquals(setOf("Mine","Everyone"),today.snapshot().tasks.map { it.houseText("name") }.toSet())
            val operation=household.completeTask(1)
            assertEquals("pending",today.snapshot().taskOperations.single().state)
            assertTrue(runCatching { household.completeTask(1) }.isFailure)
            assertEquals(1,server.requestCount)
            server.enqueue(MockResponse().setBody("{}"));household.sync()
            val request=server.takeRequest(1,TimeUnit.SECONDS)!!
            assertEquals("POST",request.method);assertEquals("/api/tasks/1/complete",request.path)
            assertEquals("confirmed",db.operation(operation)!!.state)
            server.enqueue(MockResponse().setBody(open.replace("\"id\":1,\"name\":\"Mine\",\"assigned_to_user_id\":4,\"done\":0","\"id\":1,\"name\":\"Mine\",\"assigned_to_user_id\":4,\"done\":1")))
            today.refresh(cache);server.takeRequest(1,TimeUnit.SECONDS)
            assertEquals(listOf("Everyone"),today.snapshot().tasks.map { it.houseText("name") })
            server.enqueue(MockResponse().setResponseCode(403))
            assertTrue(runCatching { today.refresh(cache) }.isFailure)
            assertTrue(today.snapshot().accessDenied);assertTrue(today.snapshot().tasks.isEmpty())
        } finally { db.close();AccountDatabase.delete(context,account.id,"today_task_test");server.shutdown() }
    }
    @Test fun reopeningUsesOfficialUndoAndBlocksDuplicateAndMissingGrant()=runBlocking(Dispatchers.IO) {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val server=MockWebServer();server.start(java.net.InetAddress.getByName("127.0.0.1"),0)
        val address=ServerAddress.parse("http://127.0.0.1:${server.port}",true)
        val id=AccountId.of(address,4);AccountDatabase.delete(context,id,"task_undo_test")
        val db=AccountDatabase(context,id,"task_undo_test")
        try {
            val cache=CachedGrocyRepository(db,address,"synthetic",MutationTransport(300))
            fun repository(grants:Set<String>)=GrocyHouseholdRepository(grants,4,cache) { db.operations().map { PendingChange(it.clientOperationId,it.method,it.path,it.state,it.detail,false) } }
            val household=repository(setOf("TASKS","TASKS_UNDO_EXECUTION"))
            server.enqueue(MockResponse().setBody("[{\"id\":1,\"name\":\"Finished\",\"done\":1}]"))
            assertEquals(1,household.completedTasks().size)
            assertEquals("/api/objects/tasks",server.takeRequest().path)
            val operation=household.reopenTask(1)
            assertTrue(runCatching { household.reopenTask(1) }.isFailure)
            server.enqueue(MockResponse().setBody("{}"));household.sync()
            val request=server.takeRequest();assertEquals("POST",request.method);assertEquals("/api/tasks/1/undo",request.path)
            assertEquals("confirmed",db.operation(operation)!!.state)
            assertTrue(runCatching { repository(setOf("TASKS")).reopenTask(2) }.isFailure)
            assertEquals(2,server.requestCount)
        }finally { db.close();server.shutdown();AccountDatabase.delete(context,id,"task_undo_test") }
    }

}
