package app.stillroom

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.stillroom.data.*
import app.stillroom.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import okhttp3.mockwebserver.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=android.app.Application::class)
class CatalogIntegrationTest {
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    @Test fun nativeBatteryCycleAndConversionMatchLocalGrocy()=runBlocking(Dispatchers.IO) {
        val path=System.getenv("STILLROOM_STAGE11_FIXTURES");Assume.assumeTrue("Existing local Grocy credentials supplied by stage:11",path!=null)
        val f=Json.parseToJsonElement(File(path!!).readText()).jsonObject["fixtures"]!!.jsonArray.first().jsonObject
        val address=ServerAddress.parse(f.catalogText("base_url"),true);val key=f.catalogText("parent_key");val transport=MutationTransport()
        suspend fun http(method:String,path:String,body:String="{}"):JsonElement {
            val (status,payload)=transport.request(address,key,method,path,body);assertTrue("Grocy $method $path HTTP $status",status in 200..299)
            return if(payload.isBlank())JsonObject(emptyMap())else Json.parseToJsonElement(payload)
        }
        val id=AccountId.of(address,1);AccountDatabase.delete(context,id,"catalog_live");val db=AccountDatabase(context,id,"catalog_live")
        val created=mutableListOf<Pair<String,Long>>();val token=java.util.UUID.randomUUID().toString().replace("-","").take(8)
        try {
            val cache=CachedGrocyRepository(db,address,key);val catalog=ManageCatalog(GrocyCatalogRepository(setOf("ADMIN"),db,cache))
            suspend fun confirmed(op:String) { catalog.sync();assertEquals("confirmed",db.operation(op)!!.state) }
            suspend fun create(entity:CatalogEntity,body:JsonObject,custom:JsonObject=JsonObject(emptyMap())):Long {
                val op=catalog.save(entity,null,body,custom);assertEquals("pending",db.operation(op)!!.state);confirmed(op)
                val row=Json.parseToJsonElement(db.operation(op)!!.responsePayload!!).jsonObject.catalogId("created_object_id")!!
                created+=entity.entity to row;return row
            }
            val battery=create(CatalogEntity.Batteries,buildJsonObject { put("name","Stage11 battery $token");put("charge_interval_days",7);put("used_in","Synthetic flashlight") })
            val charge=catalog.charge(battery)
            try { catalog.charge(battery);fail("Unconfirmed duplicate charge") }catch(_:IllegalStateException){}
            confirmed(charge);catalog.sync()
            val cycle=Json.parseToJsonElement(db.operation(charge)!!.responsePayload!!).jsonObject
            val details=http("GET","/batteries/$battery").jsonObject
            assertEquals(battery,cycle.catalogId("battery_id"));assertEquals(1L,details.catalogId("charge_cycles_count"))
            assertEquals(cycle.catalogText("tracked_time"),details.catalogText("last_charged"))
            val history=catalog.history(battery);assertEquals(1,history.size);assertEquals(cycle.catalogId("id"),history.single().catalogId("id"))
            assertEquals(details.catalogText("next_estimated_charge_time"),catalog.snapshot().rows("battery_status").single { it.catalogId("battery_id")==battery }.catalogText("next_estimated_charge_time"))
            val small=create(CatalogEntity.Units,buildJsonObject { put("name","Stage11 small $token");put("name_plural","Small units") })
            val large=create(CatalogEntity.Units,buildJsonObject { put("name","Stage11 large $token") })
            val location=create(CatalogEntity.Locations,buildJsonObject { put("name","Stage11 shelf $token");put("is_freezer",0) })
            val store=create(CatalogEntity.Stores,buildJsonObject { put("name","Stage11 store $token") })
            val category=create(CatalogEntity.Categories,buildJsonObject { put("name","Stage11 category $token") })
            val product=create(CatalogEntity.Products,buildJsonObject { put("name","Stage11 product $token");put("location_id",location);put("shopping_location_id",store);for(k in listOf("qu_id_stock","qu_id_purchase","qu_id_consume","qu_id_price"))put(k,small);put("min_stock_amount",0) })
            val conversion=create(CatalogEntity.Conversions,buildJsonObject { put("product_id",product);put("from_qu_id",large);put("to_qu_id",small);put("factor",Json.parseToJsonElement("2.5")) })
            assertEquals("2.5",http("GET","/objects/quantity_unit_conversions/$conversion").jsonObject.catalogText("factor"))
            val resolved=http("GET","/objects/quantity_unit_conversions_resolved").jsonArray.map { it.jsonObject }.single { it.catalogId("product_id")==product && it.catalogId("from_qu_id")==large && it.catalogId("to_qu_id")==small }
            assertEquals(0,"2.5".toBigDecimal().compareTo(resolved.catalogText("factor").toBigDecimal()))
            confirmed(catalog.save(CatalogEntity.Conversions,conversion,buildJsonObject { put("factor",3) }))
            assertEquals("3",http("GET","/objects/quantity_unit_conversions/$conversion").jsonObject.catalogText("factor"))
            val name="stage11_note_$token"
            val field=http("POST","/objects/userfields",buildJsonObject { put("entity","equipment");put("name",name);put("caption","Stage11 note $token");put("type","text-single-line") }.toString()).jsonObject.catalogId("created_object_id")!!
            created+="userfields" to field
            val equipment=create(CatalogEntity.Equipment,buildJsonObject { put("name","Stage11 equipment $token");put("description","Synthetic manual instructions") },buildJsonObject { put(name,"Reviewed equipment note") })
            catalog.sync()
            assertEquals("Reviewed equipment note",http("GET","/objects/equipment/$equipment").jsonObject["userfields"]!!.jsonObject.catalogText(name))
            confirmed(catalog.save(CatalogEntity.Batteries,battery,buildJsonObject { put("name","Stage11 edited battery $token");put("charge_interval_days",14) }))
            confirmed(catalog.undoCycle(cycle.catalogId("id")!!));assertEquals(0L,http("GET","/batteries/$battery").jsonObject.catalogId("charge_cycles_count"))
            assertEquals("1",catalog.history(battery).single().catalogText("undone"))
            confirmed(catalog.delete(CatalogEntity.Categories,category));created.remove("task_categories" to category)
            File("build/reports/stage11").mkdirs();File("build/reports/stage11/local-parity.json").writeText("{\"grocy_version\":\"${f.catalogText("version")}\",\"native_cycle_count\":1,\"cycle_matches_history\":true,\"server_next_charge_date\":true,\"conversion_factor\":2.5,\"edited_factor\":3,\"equipment_custom_field\":true,\"undo\":true}\n")
        }finally {
            for((entity,row)in created.asReversed())http("DELETE","/objects/$entity/$row")
            db.close();AccountDatabase.delete(context,id,"catalog_live")
        }
    }
    @Test fun cacheOnlyTodayDoesNotRecoverWritersAndCatalogDenialIsLocal()=runBlocking(Dispatchers.IO) {
        val server=MockWebServer();server.start(java.net.InetAddress.getByName("127.0.0.1"),0)
        val address=ServerAddress.parse("http://127.0.0.1:${server.port}",true);val id=AccountId.of(address,4)
        AccountDatabase.delete(context,id,"catalog_cache");val db=AccountDatabase(context,id,"catalog_cache")
        try {
            val cache=CachedGrocyRepository(db,address,"private",MutationTransport(300))
            val denied=GrocyCatalogRepository(setOf("CHORES"),db,cache)
            assertTrue(denied.snapshot().resources.isEmpty())
            try { denied.charge(1);fail("Denied battery") }catch(_:IllegalStateException){}
            try { denied.undoCycle(1);fail("Denied undo") }catch(_:IllegalStateException){}
            try { denied.save(CatalogEntity.Units,null,buildJsonObject { put("name","No") },JsonObject(emptyMap()));fail("Denied master edit") }catch(_:IllegalStateException){}
            assertEquals(0,server.requestCount)
            val account=Account(id,address,4,"child","4.7.1",setOf("CHORES"))
            db.put("/chores","current","""[{"chore_id":1,"chore_name":"Cached mine","next_execution_assigned_to_user_id":4,"next_estimated_execution_time":"2026-01-01 00:00:00"}]""")
            db.put("/objects/chores","current","[]")
            assertEquals(1,CachedTodayRepository(account,db).snapshot().chores.size)
            assertEquals(0,server.requestCount)
            server.enqueue(MockResponse().setResponseCode(403))
            try { cache.read("/chores");fail("Used denied cache") }catch(_:GrocyFailure){}
            assertTrue(CachedTodayRepository(account,db).snapshot().accessDenied)
            assertTrue(CachedTodayRepository(account,db).snapshot().chores.isEmpty())
            db.put("background","access-denied","false")
            val op=cache.enqueue("PUT","/objects/quantity_units/1","{\"name\":\"Pending\"}","/objects/quantity_units/1")
            assertTrue(db.claim(op))
            val reader=AccountDatabase(context,id,"catalog_cache",recoverOperations=false)
            try { assertEquals(1,CachedTodayRepository(account,reader).snapshot().chores.size);assertEquals("in-flight",db.operation(op)!!.state) }finally{reader.close()}
        }finally { db.close();AccountDatabase.delete(context,id,"catalog_cache");server.shutdown() }
    }
    @Test fun exposedRecordsUnknownFieldsAndUncertainCycle()=runBlocking(Dispatchers.IO) {
        val server=MockWebServer();server.start(java.net.InetAddress.getByName("127.0.0.1"),0)
        val address=ServerAddress.parse("http://127.0.0.1:${server.port}",true);val id=AccountId.of(address,1)
        AccountDatabase.delete(context,id,"catalog_unknown");val db=AccountDatabase(context,id,"catalog_unknown")
        try {
            val cache=CachedGrocyRepository(db,address,"private",MutationTransport(300));val repo=GrocyCatalogRepository(setOf("BATTERIES","BATTERIES_TRACK_CHARGE_CYCLE"),db,cache)
            val spec="""{"components":{"schemas":{"ExposedEntity":{"enum":["batteries","userfields"]}}}}"""
            server.enqueue(MockResponse().setBody(spec));server.enqueue(MockResponse().setBody("[]"));server.enqueue(MockResponse().setBody("""[{"entity":"batteries","name":"mystery","caption":"Mystery","type":"future-widget"}]"""));server.enqueue(MockResponse().setBody("[]"))
            assertTrue(UserfieldTypes.reason(repo.snapshot().userfields("batteries").single())!!.contains("future-widget"));repeat(4){server.takeRequest()}
            repeat(4){server.enqueue(MockResponse().setResponseCode(503))};assertTrue(repo.snapshot().stale);repeat(4){server.takeRequest()}
            server.enqueue(MockResponse().setResponseCode(403));try { repo.snapshot();fail("Used denied cache") }catch(e:GrocyFailure){assertEquals(403,e.status)};server.takeRequest()
            val cycle=repo.charge(2);assertEquals("pending",db.operation(cycle)!!.state)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));repo.sync();assertEquals("needs-review",db.operation(cycle)!!.state)
            val count=server.requestCount;repo.sync();assertEquals(count,server.requestCount);assertEquals("needs-review",repo.operations().single().state)
            val empty=GrocyCatalogRepository(setOf("EQUIPMENT"),db,cache)
            server.enqueue(MockResponse().setBody("""{"components":{"schemas":{"ExposedEntity":{"enum":[]}}}}"""))
            assertTrue(empty.snapshot().unavailable.single().contains("does not expose"))
        }finally { db.close();AccountDatabase.delete(context,id,"catalog_unknown");server.shutdown() }
    }
}
