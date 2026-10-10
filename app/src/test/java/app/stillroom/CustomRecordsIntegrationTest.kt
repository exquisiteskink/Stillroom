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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=android.app.Application::class)
class CustomRecordsIntegrationTest {
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    private val entity=Json.parseToJsonElement("""{"id":1,"name":"Marques","caption":"Brands"}""").jsonObject
    private val definitions="""[{"entity":"userentity-Marques","name":"Marque","caption":"Brand","type":"text-single-line","input_required":1},{"entity":"userentity-Marques","name":"Logo","type":"image"},{"entity":"userentity-Marques","name":"Unknown","type":"future-type"}]"""
    private fun response(body:String)=MockResponse().setBody(body)
    private suspend fun fixture(block:suspend(MockWebServer,AccountDatabase,GrocyCustomRecordsRepository)->Unit) {
        val server=MockWebServer();server.start(java.net.InetAddress.getByName("127.0.0.1"),0)
        val address=ServerAddress.parse("http://127.0.0.1:${server.port}",true);val id=AccountId.of(address,1);val ns="custom-${java.util.UUID.randomUUID()}"
        val db=AccountDatabase(context,id,ns)
        val cache=CachedGrocyRepository(db,address,"grocy-fixture",MutationTransport(500))
        try { block(server,db,GrocyCustomRecordsRepository(setOf("ADMIN"),db,cache,GrocyFiles(address,"grocy-fixture"))) }
        finally { db.close();AccountDatabase.delete(context,id,ns);server.shutdown() }
    }
    @Test fun discoversDefinitionsAndHidesInternalShoppingClaims()=runBlocking(Dispatchers.IO) {
        fixture { server,_,repo->
            server.enqueue(response("""[{"id":1,"name":"Marques"},{"id":-1700000007,"name":"stillroom_shopping_sync_v1"}]"""))
            server.enqueue(response("""[{"id":3,"userentity_id":1},{"id":-10,"userentity_id":-1700000007}]"""));server.enqueue(response(definitions));server.enqueue(response("{\"Marque\":\"Readable brand\"}"))
            val snapshot=repo.snapshot();assertEquals("Readable brand",snapshot.objects.single()["userfields"]!!.jsonObject.catalogText("Marque"));assertEquals(1,snapshot.entities.size);assertEquals(listOf(3L),snapshot.objects.map { it.catalogId("id") });assertFalse(snapshot.stale)
        }
    }
    @Test fun editsOnlyChangedFieldsAndPreservesAddonFieldsAndMedia()=runBlocking(Dispatchers.IO) {
        fixture { server,db,repo->
            val baseline=Json.parseToJsonElement("""{"Marque":"Old","Logo":"existing-image","Unknown":"keep-me"}""").jsonObject
            server.enqueue(response(entity.toString()));server.enqueue(response(definitions));server.enqueue(response(entity.toString()));server.enqueue(response("{\"id\":3,\"userentity_id\":1}"));server.enqueue(response(baseline.toString()))
            val op=repo.save(entity,3,buildJsonObject { put("Marque","New") },baseline)
            assertEquals("{\"Marque\":\"New\"}",db.operation(op)?.payload)
            repeat(5){server.takeRequest()}
            server.enqueue(MockResponse().setResponseCode(204));repo.sync();assertTrue(repo.confirmed(op))
            val request=server.takeRequest();assertEquals("PUT",request.method);assertEquals("/api/userfields/userentity-Marques/3",request.path);assertFalse(request.body.readUtf8().contains("Logo"))
            repo.sync();assertEquals(6,server.requestCount)
        }
    }
    @Test fun detectsConcurrentChangesBeforeQueueingSave()=runBlocking(Dispatchers.IO) {
        fixture { server,db,repo->
            server.enqueue(response(entity.toString()));server.enqueue(response(definitions));server.enqueue(response(entity.toString()));server.enqueue(response("{\"id\":3,\"userentity_id\":1}"));server.enqueue(response("{\"Marque\":\"External edit\"}"))
            try { repo.save(entity,3,buildJsonObject { put("Marque","Mine") },buildJsonObject { put("Marque","Old") });fail("External change overwritten") }catch(_:IllegalArgumentException){}
            assertTrue(db.operations().isEmpty())
        }
    }
    @Test fun createsWithConfirmedIdAndDoesNotRepeatFollowup()=runBlocking(Dispatchers.IO) {
        fixture { server,db,repo->
            server.enqueue(response(entity.toString()));server.enqueue(response(definitions))
            val op=repo.save(entity,null,buildJsonObject { put("Marque","New brand") },JsonObject(emptyMap()))
            assertFalse(repo.confirmed(op));repeat(2){server.takeRequest()}
            server.enqueue(response("{\"created_object_id\":7}"));server.enqueue(MockResponse().setResponseCode(204));repo.sync();assertTrue(repo.confirmed(op))
            assertEquals("/api/objects/userobjects",server.takeRequest().path);assertEquals("/api/userfields/userentity-Marques/7",server.takeRequest().path)
            repo.sync();assertEquals(4,server.requestCount);assertEquals(2,db.operations().size)
        }
    }
    @Test fun uncertainCreateIsNeverRecreatedOrFollowedByUserfieldWrite()=runBlocking(Dispatchers.IO) {
        fixture { server,db,repo->
            server.enqueue(response(entity.toString()));server.enqueue(response(definitions))
            val op=repo.save(entity,null,buildJsonObject { put("Marque","New brand") },JsonObject(emptyMap()))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));repo.sync();repo.sync()
            assertFalse(repo.confirmed(op));assertEquals("needs-review",db.operation(op)?.state);assertEquals(3,server.requestCount);assertEquals(1,db.operations().size)
        }
    }
    @Test fun uploadedMediaUsesGrocysEncodedReferenceFormat()=runBlocking(Dispatchers.IO) {
        fixture { server,db,repo->
            server.enqueue(MockResponse().setResponseCode(204))
            val value=repo.upload("test".toByteArray(),"txt","Label.txt")
            val reference=UserfileReference.parse(value);assertTrue(reference.storageName.endsWith(".txt"));assertEquals("Label.txt",reference.displayName);assertEquals("confirmed",db.get("uploaded-userfiles",value))
            val request=server.takeRequest();assertEquals("PUT",request.method);assertFalse(request.path!!.contains("Label.txt"))
            server.enqueue(response("test"));assertEquals("test",repo.file("userfiles",value).toString(Charsets.UTF_8));assertEquals("GET",server.takeRequest().method)
            server.enqueue(response(entity.toString()));server.enqueue(response(definitions));val operation=repo.save(entity,null,buildJsonObject { put("Marque","Brand");put("Logo",value) },JsonObject(emptyMap()))
            assertNotNull(operation)
        }
    }
    @Test fun missingRequiredFieldsAndUnknownTypesNeverQueueCreate()=runBlocking(Dispatchers.IO) {
        fixture { server,db,repo->
            server.enqueue(response(entity.toString()));server.enqueue(response(definitions))
            try { repo.save(entity,null,JsonObject(emptyMap()),JsonObject(emptyMap()));fail("Required field missing") }catch(_:IllegalArgumentException){}
            server.enqueue(response(entity.toString()));server.enqueue(response(definitions))
            try { repo.save(entity,null,buildJsonObject { put("Marque","Name");put("Unknown","x") },JsonObject(emptyMap()));fail("Unknown edited") }catch(_:IllegalArgumentException){}
            assertTrue(db.operations().isEmpty())
        }
    }
}
