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
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=android.app.Application::class)
class ScannerIntegrationTest {
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    @Test fun lookupOrderPrivacyAndDisabledPlugin()=runBlocking(Dispatchers.IO) {
        val server=MockWebServer();server.start(java.net.InetAddress.getByName("127.0.0.1"),0)
        val off=MockWebServer();off.start(java.net.InetAddress.getByName("127.0.0.1"),0)
        val address=ServerAddress.parse("http://127.0.0.1:${server.port}",true)
        val id=AccountId.of(address,1);AccountDatabase.delete(context,id,"scan_order")
        val db=AccountDatabase(context,id,"scan_order")
        try {
            val cache=CachedGrocyRepository(db,address,"grocy-private-key",MutationTransport(300))
            val transport=MutationTransport(300)
            val repo=GrocyScanRepository(setOf("ADMIN"),db,cache,{ transport.request(address,"grocy-private-key","GET",it) },OpenFoodFactsLookup("http://127.0.0.1:${off.port}")::lookup)
            val code=ScanCode("00123457",ScanFormat.Ean8)
            server.enqueue(MockResponse().setBody("""[{"barcode":"00123457","product_id":12}]"""))
            assertEquals(listOf(12L),repo.lookup(code).productIds);assertEquals(0,off.requestCount)
            server.takeRequest()
            server.enqueue(MockResponse().setBody("[]"));server.enqueue(MockResponse().setBody("""{"STOCK_BARCODE_LOOKUP_PLUGIN":"enabled"}"""))
            server.enqueue(MockResponse().setBody("""{"paths":{"/stock/barcodes/external-lookup/{barcode}":{}}}"""))
            server.enqueue(MockResponse().setBody("""{"name":"Grocy suggestion","qu_id_stock":999,"location_id":999}"""))
            assertEquals("Grocy external lookup",repo.lookup(code).source);assertEquals(0,off.requestCount)
            repeat(3) { server.takeRequest() }
            assertEquals("/api/stock/barcodes/external-lookup/00123457?add=false",server.takeRequest().path)
            assertTrue(db.operations().isEmpty()) // Lookup never creates a product or stock.
            server.enqueue(MockResponse().setBody("[]"));server.enqueue(MockResponse().setBody("""{"STOCK_BARCODE_LOOKUP_PLUGIN":""}"""))
            off.enqueue(MockResponse().setBody("""{"status":1,"product":{"product_name":"Public name","brands":"Public brand"}}"""))
            val result=repo.lookup(code);assertEquals("Open Food Facts",result.source)
            val request=off.takeRequest();assertEquals("GET",request.method)
            assertEquals("/api/v2/product/00123457?fields=product_name,brands",request.path)
            assertNull(request.getHeader("GROCY-API-KEY"));assertNull(request.getHeader("Authorization"));assertEquals(0L,request.bodySize)
            assertTrue(request.getHeader("User-Agent")!!.startsWith("Stillroom/"));repeat(2) { server.takeRequest() }
            server.enqueue(MockResponse().setBody("[]"))
            assertEquals("Manual review",repo.lookup(ScanCode("household://private-shelf",ScanFormat.Qr)).source)
            assertEquals(1,off.requestCount);server.takeRequest()
            server.enqueue(MockResponse().setBody("[]"));server.enqueue(MockResponse().setBody("""{"STOCK_BARCODE_LOOKUP_PLUGIN":"enabled"}"""))
            server.enqueue(MockResponse().setBody("""{"paths":{"/stock/barcodes/external-lookup/{barcode}":{}}}"""));server.enqueue(MockResponse().setResponseCode(403))
            try { repo.lookup(code);fail("Denied lookup fell back to OFF") } catch(e:GrocyFailure) { assertEquals(403,e.status) }
            assertEquals("true", db.get("background", "access-denied"))
            assertEquals(1,off.requestCount)
            val child=GrocyScanRepository(setOf("CHORES"),db,cache,{error("Child network")},{error("Child public lookup")})
            try { child.lookup(code);fail("Child scanner allowed") } catch(_:IllegalStateException) {}
            try { child.create(ScanReview(code,"name","",1,1));fail("Child creation") } catch(_:IllegalStateException) {}
        } finally { db.close();AccountDatabase.delete(context,id,"scan_order");server.shutdown();off.shutdown() }
    }
    @Test fun unknownOutcomeAndExplicitUnitValidation()=runBlocking(Dispatchers.IO) {
        val server=MockWebServer();server.start(java.net.InetAddress.getByName("127.0.0.1"),0)
        val address=ServerAddress.parse("http://127.0.0.1:${server.port}",true)
        val id=AccountId.of(address,1);AccountDatabase.delete(context,id,"scan_create")
        val db=AccountDatabase(context,id,"scan_create")
        try {
            val cache=CachedGrocyRepository(db,address,"key",MutationTransport(300))
            val repo=GrocyScanRepository(setOf("ADMIN"),db,cache,{error("No external")},{null})
            val review=ScanReview(ScanCode("00123457",ScanFormat.Ean8),"Reviewed name","",1,2)
            server.enqueue(MockResponse().setBody("[]"))
            try { repo.create(review);fail("Invented unit") } catch(_:IllegalArgumentException) {}
            server.enqueue(MockResponse().setBody("""[{"id":1,"name":"Existing unit"}]"""));server.enqueue(MockResponse().setBody("""[{"id":2,"name":"Existing shelf"}]"""));server.enqueue(MockResponse().setBody("[]"))
            val op=repo.create(review);assertEquals("pending",db.operation(op)!!.state)
            val body=Json.parseToJsonElement(db.operation(op)!!.payload).jsonObject
            assertEquals(1,body["qu_id_stock"]!!.jsonPrimitive.int);assertFalse(body.containsKey("best_before_date"));assertFalse(body.containsKey("default_best_before_days"))
            repeat(4) { server.takeRequest() }
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            repo.sync();assertEquals("needs-review",db.operation(op)!!.state);assertNull(repo.createdProduct(op))
            assertEquals(5,server.requestCount);repo.sync();assertEquals(5,server.requestCount)
            assertEquals("needs-review",repo.operations().single().state)
        } finally { db.close();AccountDatabase.delete(context,id,"scan_create");server.shutdown() }
    }
    @Test fun localGrocyReviewedCreationLookupStockAndUndo()=runBlocking(Dispatchers.IO) {
        val path=System.getenv("STILLROOM_STAGE9_FIXTURES")
        Assume.assumeTrue("Existing local Grocy credentials supplied by stage:9",path!=null)
        val f=Json.parseToJsonElement(File(path!!).readText()).jsonObject["fixtures"]!!.jsonArray.first().jsonObject
        fun field(key:String)=f[key]!!.jsonPrimitive.content
        val address=ServerAddress.parse(field("base_url"),true);val key=field("parent_key");val transport=MutationTransport()
        suspend fun http(method:String,path:String,body:String="{}"):JsonElement {
            val (status,payload)=transport.request(address,key,method,path,body)
            assertTrue("Grocy $method $path HTTP $status",status in 200..299)
            return if(payload.isBlank())JsonObject(emptyMap()) else Json.parseToJsonElement(payload)
        }
        suspend fun create(entity:String,body:String)=http("POST","/objects/$entity",body).jsonObject["created_object_id"]!!.jsonPrimitive.long
        val token=java.util.UUID.randomUUID().toString().take(8)
        val unit=create("quantity_units","{\"name\":\"Stage9 unit $token\"}")
        val location=create("locations","{\"name\":\"Stage9 shelf $token\"}")
        val id=AccountId.of(address,1);AccountDatabase.delete(context,id,"scan_live")
        val db=AccountDatabase(context,id,"scan_live")
        var product:Long?=null;var barcode:Long?=null
        try {
            val cache=CachedGrocyRepository(db,address,key)
            val repo=ManageScanner(GrocyScanRepository(setOf("ADMIN"),db,cache,{transport.request(address,key,"GET",it)},{error("No public lookup in local parity test") }))
            val code=ScanCode("STILLROOM-STAGE9-$token",ScanFormat.Qr)
            assertEquals("Manual review",repo.lookup(code).source)
            assertTrue(repo.choices().first.any { it.first==unit })
            val op=repo.create(ScanReview(code,"Stage9 reviewed $token","Synthetic instructions",unit,location))
            assertEquals("pending",db.operation(op)!!.state);repo.sync();product=repo.createdProduct(op)!!
            val result=repo.lookup(code);assertEquals(listOf(product),result.productIds)
            val barcodeRow=http("GET","/objects/product_barcodes").jsonArray.single { it.jsonObject["barcode"]!!.jsonPrimitive.content==code.raw }.jsonObject
            barcode=barcodeRow["id"]!!.jsonPrimitive.long
            val stock=ManageStock(GrocyStockRepository(setOf("ADMIN"),cache) { emptyList() })
            suspend fun book(action:StockAction,amount:String):String {
                val op=stock.book(StockBooking(action,product!!,BigDecimal(amount),location=location,date=if(action==StockAction.Purchase)"2027-01-01" else null))
                cache.drain();assertEquals("confirmed",db.operation(op)!!.state);return op
            }
            book(StockAction.Purchase,"2");book(StockAction.Consume,"1")
            val logs=http("GET","/objects/stock_log").jsonArray.filter { it.jsonObject["product_id"]!!.jsonPrimitive.long==product }
            assertEquals(listOf("purchase","consume"),logs.map { it.jsonObject["transaction_type"]!!.jsonPrimitive.content })
            assertEquals("1",http("GET","/stock/products/$product").jsonObject["stock_amount"]!!.jsonPrimitive.content)
            val undo=stock.undo(logs.last().jsonObject["id"]!!.jsonPrimitive.long);cache.drain();assertEquals("confirmed",db.operation(undo)!!.state)
            assertEquals("2",http("GET","/stock/products/$product").jsonObject["stock_amount"]!!.jsonPrimitive.content)
            val undoPurchase=stock.undo(logs.first().jsonObject["id"]!!.jsonPrimitive.long);cache.drain();assertEquals("confirmed",db.operation(undoPurchase)!!.state)
            assertEquals("0",http("GET","/stock/products/$product").jsonObject["stock_amount"]!!.jsonPrimitive.content)
            File("build/reports/stage9").mkdirs();File("build/reports/stage9/local-parity.json").writeText("{\"grocy_version\":\"${field("version")}\",\"reviewed_creation_lookup_purchase_consume_undo\":true}\n")
        } finally {
            barcode?.let { http("DELETE","/objects/product_barcodes/$it") }
            product?.let { p -> http("DELETE","/objects/products/$p") }
            http("DELETE","/objects/locations/$location");http("DELETE","/objects/quantity_units/$unit")
            db.close();AccountDatabase.delete(context,id,"scan_live")
        }
    }
}
