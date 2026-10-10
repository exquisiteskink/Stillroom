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
class AddonCompatibilityIntegrationTest {
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    private fun response(body:String)=MockResponse().setBody(body)
    private suspend fun fixture(block:suspend(MockWebServer,AccountDatabase,CachedGrocyRepository,ServerAddress)->Unit) {
        val server=MockWebServer();server.start(java.net.InetAddress.getByName("127.0.0.1"),0)
        val address=ServerAddress.parse("http://127.0.0.1:${server.port}",true)
        val id=AccountId.of(address,1);val ns="addon-test-${java.util.UUID.randomUUID()}"
        val db=AccountDatabase(context,id,ns)
        try { block(server,db,CachedGrocyRepository(db,address,"grocy-fixture",MutationTransport(500)),address) }
        finally { db.close();AccountDatabase.delete(context,id,ns);server.shutdown() }
    }
    @Test fun changedTokenRefreshesCachedRowsWithoutReplayingWritesOrPrints()=runBlocking(Dispatchers.IO) {
        fixture { server,db,cache,_->
            db.put("/objects/products","current","[]")
            db.put("/stock/products/2/printlabel","current","{}")
            db.put("/objects/api_keys","current","[]")
            val pending=cache.enqueue("POST","/stock/products/2/add","{\"amount\":1}","/stock/products/2")
            val repo=GrocyCompatibilityRepository(db,cache)
            server.enqueue(response("{}"));server.enqueue(response("""{"paths":{"/system/db-changed-time":{"get":{}}}}"""));server.enqueue(response("{\"changed_time\":\"A\"}"));server.enqueue(response("[{\"id\":2,\"name\":\"External purchase\"}]"))
            val first=repo.poll();assertTrue(first.changed);assertFalse(first.stale)
            assertEquals("pending",db.operation(pending)?.state)
            assertEquals("External purchase",Json.parseToJsonElement(db.get("/objects/products","current")!!).jsonArray.first().jsonObject.catalogText("name"))
            repeat(4) { assertEquals("GET",server.takeRequest().method) }
            server.enqueue(response("{\"changed_time\":\"A\"}"));assertFalse(repo.poll().changed)
            assertEquals("/api/system/db-changed-time",server.takeRequest().path)
            server.enqueue(response("{\"changed_time\":\"B\"}"));server.enqueue(response("[]"));assertTrue(repo.poll().changed)
            assertEquals(7,server.requestCount)
        }
    }
    @Test fun failedRefreshKeepsTokenForRetryAndDenialNeverUsesCache()=runBlocking(Dispatchers.IO) {
        fixture { server,db,cache,_->
            db.put("/objects/products","current","[]")
            val repo=GrocyCompatibilityRepository(db,cache)
            server.enqueue(response("{}"));server.enqueue(response("""{"paths":{"/system/db-changed-time":{"get":{}}}}"""));server.enqueue(response("{\"changed_time\":\"A\"}"));server.enqueue(MockResponse().setResponseCode(500))
            assertTrue(repo.poll().stale);assertNull(db.get("compatibility","change-token"))
            server.enqueue(response("{\"changed_time\":\"A\"}"));server.enqueue(response("[]"));assertTrue(repo.poll().changed);assertEquals("A",db.get("compatibility","change-token"))
            server.enqueue(MockResponse().setResponseCode(403))
            try { repo.poll();fail("Denied polling used cache") }catch(e:GrocyFailure){assertEquals(403,e.status)}
            assertEquals("true",db.get("background","access-denied"))
        }
    }
    @Test fun disabledFeatureBlocksReadAndQueueAndRetainsPendingWrites()=runBlocking(Dispatchers.IO) {
        fixture { server,db,cache,_->
            val operation=cache.enqueue("POST","/tasks/4/complete","{}","/tasks")
            db.put("/system/config","current","{\"FEATURE_FLAG_TASKS\":false}")
            try { cache.read("/tasks");fail("Disabled read") }catch(_:IllegalStateException){}
            try { cache.enqueue("POST","/tasks/5/complete","{}","/tasks");fail("Disabled write") }catch(_:IllegalStateException){}
            cache.drain();assertEquals("pending",db.operation(operation)?.state);assertEquals(0,server.requestCount)
        }
    }
    @Test fun configuredLookupRetainsDefaultsAndPublicFallbackCanBeDisabled()=runBlocking(Dispatchers.IO) {
        fixture { server,db,cache,_->
            val repo=GrocyScanRepository(setOf("ADMIN"),db,cache,{ _->200 to """{"name":"Milk","description":"Brand","location_id":2,"qu_id_stock":3,"qu_id_purchase":4,"__qu_factor_purchase_to_stock":"6","__barcode":"00123457","__image_url":"https://images.example/milk.png"}""" },{error("Public lookup must stay off")},false)
            server.enqueue(response("[]"));server.enqueue(response("{\"STOCK_BARCODE_LOOKUP_PLUGIN\":\"plugin\"}"));server.enqueue(response("""{"paths":{"/stock/barcodes/external-lookup/{barcode}":{}}}"""))
            val result=repo.lookup(ScanCode("00123457",ScanFormat.Ean8))
            assertEquals(2L,result.defaults.location);assertEquals(3L,result.defaults.stockUnit);assertEquals(4L,result.defaults.purchaseUnit);assertEquals("6",result.defaults.factor);assertEquals("Brand",result.description)
            server.enqueue(response("[]"));server.enqueue(response("{}"));assertEquals("Manual review",repo.lookup(ScanCode("00123457",ScanFormat.Ean8)).source)
            assertTrue(db.operations().isEmpty())
        }
    }
    @Test fun barcodeBuddyUsesOnlyItsKeyAndSingleMultipartSubmission()=runBlocking(Dispatchers.IO) {
        fixture { server,_,_,address->
            val client=BarcodeBuddyClient(address,"buddy-fixture")
            server.enqueue(response("""{"data":{"mode":"purchase"},"result":{"result":"OK","http_code":200}}"""))
            assertEquals("purchase",client.mode())
            val mode=server.takeRequest();assertNull(mode.getHeader("GROCY-API-KEY"));assertEquals("buddy-fixture",mode.getHeader("BBUDDY-API-KEY"))
            server.enqueue(response("""{"data":{"result":"Added"},"result":{"result":"OK","http_code":200}}"""))
            assertEquals("Added",client.scan(ScanCode("00123457",ScanFormat.Ean8)))
            val scan=server.takeRequest();assertEquals("POST",scan.method);assertEquals("/api/action/scan",scan.path);assertTrue(scan.body.readUtf8().contains("00123457"));assertNull(scan.getHeader("GROCY-API-KEY"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",server.url("/leak")))
            try { client.scan(ScanCode("household-code",ScanFormat.Manual));fail("Redirect followed") }catch(_:IllegalStateException){}
            assertEquals(3,server.requestCount)
        }
    }
    @Test fun barcodeBuddyUnknownOutcomeIsNotAutomaticallyRetried()=runBlocking(Dispatchers.IO) {
        fixture { server,_,_,address->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            try { BarcodeBuddyClient(address,"buddy-fixture").scan(ScanCode("00123457",ScanFormat.Ean8));fail("Unknown outcome accepted") }catch(_:Exception){}
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun mediaUsesAuthenticatedGrocyOnlyAndEncodedFileName()=runBlocking(Dispatchers.IO) {
        fixture { server,_,_,address->
            val files=GrocyFiles(address,"grocy-fixture")
            server.enqueue(response("image-bytes"));assertEquals("image-bytes",files.read("productpictures","milk.png").toString(Charsets.UTF_8))
            val request=server.takeRequest();assertEquals("/api/files/productpictures/bWlsay5wbmc=",request.path);assertEquals("grocy-fixture",request.getHeader("GROCY-API-KEY"));assertNull(request.getHeader("BBUDDY-API-KEY"))
            try { files.read("userfiles","../../private");fail("Traversal accepted") }catch(_:IllegalArgumentException){}
            server.enqueue(MockResponse().setResponseCode(204));files.upload("new.txt","test".toByteArray());assertEquals("PUT",server.takeRequest().method)
        }
    }
    @Test fun externalPurchaseOwnerBlocksNativeShoppingBooking()=runBlocking(Dispatchers.IO) {
        fixture { server,db,cache,_->
            db.put("addon-settings","current",AddonSettings(shoppingPurchaseOwner="external").json())
            val repo=GrocyShoppingRepository(db,cache,GrocyStockRepository(setOf("ADMIN"),cache){emptyList()},setOf("ADMIN"))
            val row=buildJsonObject { put("id",1);put("product_id",2);put("shopping_list_id",1);put("done",0) }
            try { repo.purchase(row,StockBooking(StockAction.Purchase,2,java.math.BigDecimal.ONE));fail("Double purchase") }catch(_:IllegalStateException){}
            assertTrue(db.operations().isEmpty());assertEquals(0,server.requestCount)
        }
    }
    @Test fun uncertainBuddyReceiptSurvivesRestartAndBlocksDuplicateUntilReviewed()=runBlocking(Dispatchers.IO) {
        fixture { _,db,_,_->
            val receipts=BarcodeBuddyReceipts(db)
            val code=ScanCode("00123457",ScanFormat.Ean8)
            var attempts=0
            try { receipts.submit(code,{attempts++;throw java.io.IOException("Lost response")});fail("Unknown outcome accepted") }catch(_:java.io.IOException){}
            assertEquals(1,receipts.pending().size)
            val reopened=BarcodeBuddyReceipts(db)
            try { reopened.submit(code,{attempts++;"Added"});fail("Duplicate replay") }catch(_:IllegalStateException){}
            assertEquals(1,attempts)
            reopened.reviewed(reopened.pending().single().first)
            assertEquals("Added",reopened.submit(code,{attempts++;"Added"}) { throw java.io.IOException("Refresh failed") })
            assertTrue(reopened.pending().isEmpty());assertEquals(2,attempts)
        }
    }
    @Test fun cancellingBuddySubmissionLeavesReceiptForReview()=runBlocking(Dispatchers.IO) {
        fixture { _,db,_,_->
            val receipts=BarcodeBuddyReceipts(db)
            try { receipts.submit(ScanCode("household-code",ScanFormat.Manual),{throw CancellationException("Interrupted")});fail("Cancelled submit accepted") }catch(_:CancellationException){}
            assertEquals(1,receipts.pending().size)
        }
    }
    @Test fun privateHouseholdCodeGoesOnlyToConfiguredGrocysLookup()=runBlocking(Dispatchers.IO) {
        fixture { server,db,cache,address->
            val transport=MutationTransport(500)
            val repo=GrocyScanRepository(setOf("ADMIN"),db,cache,{transport.request(address,"grocy-fixture","GET",it)},{error("Private code sent publicly")})
            server.enqueue(response("[]"));server.enqueue(response("{\"STOCK_BARCODE_LOOKUP_PLUGIN\":\"plugin\"}"));server.enqueue(response("""{"paths":{"/stock/barcodes/external-lookup/{barcode}":{"get":{}}}}"""));server.enqueue(response("{\"name\":\"Private shelf item\"}"))
            assertEquals("Private shelf item",repo.lookup(ScanCode("household://private-shelf",ScanFormat.Qr)).name)
            repeat(3){server.takeRequest()}
            val request=server.takeRequest();assertEquals("/api/stock/barcodes/external-lookup/household%3A%2F%2Fprivate-shelf?add=false",request.path);assertEquals("grocy-fixture",request.getHeader("GROCY-API-KEY"))
        }
    }
    @Test fun coarseGrocyTimestampStillRefreshesPeriodically()=runBlocking(Dispatchers.IO) {
        fixture { server,db,cache,_->
            var time=0L
            db.put("/objects/products","current","[]")
            val repo=GrocyCompatibilityRepository(db,cache) { time }
            val spec="""{"paths":{"/system/db-changed-time":{"get":{}}}}"""
            server.enqueue(response("{}"));server.enqueue(response(spec));server.enqueue(response("{\"changed_time\":\"Same second\"}"));server.enqueue(response("[]"));assertTrue(repo.poll().changed)
            time=30_000;server.enqueue(response("{\"changed_time\":\"Same second\"}"));assertFalse(repo.poll().changed)
            time=120_000;server.enqueue(response("{}"));server.enqueue(response(spec));server.enqueue(response("{\"changed_time\":\"Same second\"}"));server.enqueue(response("[{\"id\":3}]"));assertTrue(repo.poll().changed)
            assertEquals(3L,Json.parseToJsonElement(db.get("/objects/products","current")!!).jsonArray.first().jsonObject.catalogId("id"))
        }
    }
    @Test fun reviewedPluginUnitsCreateAndConfirmConversionExactlyOnce()=runBlocking(Dispatchers.IO) {
        fixture { server,db,cache,_->
            val mutations=mutableListOf<RecordedRequest>()
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse {
                    if(request.method!="GET")mutations.add(request)
                    return when(request.method to request.path) {
                        "GET" to "/api/objects/quantity_units"->response("[{\"id\":1},{\"id\":2}]")
                        "GET" to "/api/objects/locations"->response("[{\"id\":3}]")
                        "GET" to "/api/objects/product_barcodes"->response("[]")
                        "GET" to "/api/objects/quantity_unit_conversions"->response("""[{"id":9,"product_id":7,"from_qu_id":2,"to_qu_id":1,"factor":1}]""")
                        "POST" to "/api/objects/products"->response("{\"created_object_id\":7}")
                        "PUT" to "/api/objects/quantity_unit_conversions/9"->MockResponse().setResponseCode(204)
                        "POST" to "/api/objects/product_barcodes"->response("{\"created_object_id\":10}")
                        else->MockResponse().setResponseCode(404)
                    }
                }
            }
            val repo=GrocyScanRepository(setOf("ADMIN"),db,cache,{error("No lookup")},{null})
            try { repo.create(ScanReview(ScanCode("00123457",ScanFormat.Ean8),"Case of milk","Brand",1,3,2));fail("Unreviewed conversion") }catch(_:IllegalArgumentException){}
            assertEquals(0,server.requestCount)
            val op=repo.create(ScanReview(ScanCode("00123457",ScanFormat.Ean8),"Case of milk","Brand",1,3,2,"6"))
            repo.sync();assertEquals(7L,repo.createdProduct(op));repo.sync()
            assertEquals(3,mutations.size)
            val create=Json.parseToJsonElement(mutations.single { it.path=="/api/objects/products" }.body.readUtf8()).jsonObject
            assertEquals(1L,create.catalogId("qu_id_stock"));assertEquals(2L,create.catalogId("qu_id_purchase"))
            assertEquals("{\"factor\":6}",mutations.single { it.path=="/api/objects/quantity_unit_conversions/9" }.body.readUtf8())
            assertTrue(db.operations().all { it.state=="confirmed" })
        }
    }
}
