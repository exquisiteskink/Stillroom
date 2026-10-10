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
class TaskCategoryIntegrationTest {
    @Test fun categoryIsSelectedOnlyAfterServerConfirmationAndPendingCreateIsNotDuplicated()=runBlocking(Dispatchers.IO) {
        val context:Context=ApplicationProvider.getApplicationContext()
        val server=MockWebServer();server.start(java.net.InetAddress.getByName("127.0.0.1"),0)
        val address=ServerAddress.parse("http://127.0.0.1:${server.port}",true)
        val accountId=AccountId.of(address,4)
        AccountDatabase.delete(context,accountId,"task_categories_test")
        val db=AccountDatabase(context,accountId,"task_categories_test")
        fun operations()=db.operations().map { PendingChange(it.clientOperationId,it.method,it.path,it.state,it.detail,false) }
        try {
            val cache=CachedGrocyRepository(db,address,"synthetic",MutationTransport(300))
            val readOnly=GrocyHouseholdRepository(setOf("TASKS"),4,cache,::operations)
            assertTrue(runCatching { readOnly.createTaskCategory("Cleaning") }.isFailure)
            assertEquals(0,server.requestCount)
            val household=GrocyHouseholdRepository(setOf("TASKS","MASTER_DATA_EDIT"),4,cache,::operations)
            server.enqueue(MockResponse().setBody("{\"created_object_id\":12}"))
            assertEquals(12L,household.createTaskCategory(" Cleaning "))
            val request=server.takeRequest(1,TimeUnit.SECONDS)!!
            assertEquals("POST",request.method);assertEquals("/api/objects/task_categories",request.path)
            assertEquals("{\"name\":\"Cleaning\"}",request.body.readUtf8())
            assertTrue(runCatching { household.createTaskCategory(" ") }.isFailure)
            server.enqueue(MockResponse().setResponseCode(503))
            assertTrue(runCatching { household.createTaskCategory("Errands") }.isFailure)
            server.takeRequest(1,TimeUnit.SECONDS)
            assertTrue(runCatching { household.createTaskCategory("Errands") }.isFailure)
            assertEquals(2,server.requestCount)
            assertEquals(2,db.operations().size)
        } finally { db.close();AccountDatabase.delete(context,accountId,"task_categories_test");server.shutdown() }
    }
}
