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
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=android.app.Application::class)
class ShoppingTripIntegrationTest {
    private lateinit var db:AccountDatabase
    private lateinit var server:MockWebServer
    private lateinit var cache:CachedGrocyRepository
    private lateinit var address:ServerAddress
    private val posts=AtomicInteger()
    private val bodies=mutableListOf<String>()
    private var unit=2L
    private var failSecond=false
    private var tare:BigDecimal?=null
    private fun repo(grants:Set<String>?=setOf("ADMIN"))=GrocyShoppingTripRepository(grants,db,cache)
    @Before fun setup()=runBlocking(Dispatchers.IO) {
        server=MockWebServer();server.start(java.net.InetAddress.getByName("127.0.0.1"),0)
        address=ServerAddress.parse("http://127.0.0.1:${server.port}",true)
        val context=ApplicationProvider.getApplicationContext<Context>()
        AccountDatabase.delete(context,AccountId.of(address,1),"trip_test")
        db=AccountDatabase(context,AccountId.of(address,1),"trip_test")
        cache=CachedGrocyRepository(db,address,"synthetic",MutationTransport(500))
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                if(request.method=="POST") {
                    synchronized(bodies){bodies.add(request.body.readUtf8())}
                    val count=posts.incrementAndGet()
                    return if(failSecond && count==2)MockResponse().setResponseCode(500).setBody("{}") else MockResponse().setBody("{}")
                }
                if(request.path=="/api/objects/quantity_unit_conversions_resolved")return MockResponse().setBody("[{\"product_id\":1,\"from_qu_id\":3,\"to_qu_id\":2,\"factor\":4}]")
                val id=request.path.orEmpty().substringAfterLast('/').toLongOrNull() ?: 1
                return MockResponse().setBody("""{"product":{"id":$id,"name":"Product $id","qu_id_stock":$unit,"enable_tare_weight_handling":${if(tare!=null)1 else 0},"tare_weight":${tare ?: BigDecimal.ZERO}},"stock_amount":0}""")
            }
        }
    }
    @After fun cleanup() { db.close();server.shutdown();AccountDatabase.delete(ApplicationProvider.getApplicationContext(),AccountId.of(address,1),"trip_test") }
    private fun booking(id:Long)=StockBooking(StockAction.Purchase,id,BigDecimal("1.25001"),BigDecimal("2"),date="2027-01-01",price=BigDecimal("3.50"))
    @Test fun scanDraftAndQuantityEditsDoNotBookAndRetainExactConvertedAmounts()=runBlocking(Dispatchers.IO) {
        val repository=repo();val trip=repository.add(booking(1))
        assertEquals(BigDecimal("2.50002"),trip.lines.single().booking.amount)
        assertEquals(0,posts.get());assertTrue(db.operations().isEmpty())
        val restored=repo().snapshot();assertEquals(trip.id,restored.id)
        val edited=repository.edit(restored.lines.single().id,BigDecimal("0.333333"),"2027-02-01",null)
        assertEquals(BigDecimal("0.333333"),edited.lines.single().booking.amount)
        assertTrue(repository.remove(edited.lines.single().id).lines.isEmpty());assertEquals(0,posts.get())
    }
    @Test fun confirmedTripSurvivesRecreationAndRepeatedSubmitCannotDuplicatePurchases()=runBlocking(Dispatchers.IO) {
        val repository=repo();repository.add(booking(1));repository.add(booking(2))
        val confirmed=repository.submit();assertTrue(confirmed.confirmed);assertEquals(2,posts.get())
        assertTrue(repo().submit().confirmed);assertEquals(2,posts.get())
        assertEquals(BigDecimal("2.50002"),Json.parseToJsonElement(bodies.first()).jsonObject["amount"]!!.jsonPrimitive.content.toBigDecimal())
        assertTrue(runCatching { repository.edit(confirmed.lines.first().id,BigDecimal.ONE,null,null) }.isFailure)
        assertNotEquals(confirmed.id,repository.newTrip().id)
    }
    @Test fun unknownOutcomeStopsTripAndOrdinarySyncCannotSendRemainingGuardedLines()=runBlocking(Dispatchers.IO) {
        failSecond=true;val repository=repo()
        repository.add(booking(1));repository.add(booking(2));repository.add(booking(3))
        val partial=repository.submit()
        assertEquals(listOf("confirmed","needs-review","guarded"),partial.lines.map { it.state })
        assertEquals(2,posts.get());assertEquals(3,db.operations().size)
        cache.drain();assertEquals(2,posts.get())
        assertTrue(runCatching { repo().submit() }.isFailure);assertTrue(runCatching { repo().newTrip() }.isFailure)
        assertEquals(2,posts.get())
    }
    @Test fun changedStockUnitBlocksWholeTripBeforeAnyBooking()=runBlocking(Dispatchers.IO) {
        val repository=repo();repository.add(booking(1));repository.add(booking(2));unit=7
        assertTrue(runCatching { repository.submit() }.isFailure)
        assertEquals(0,posts.get());assertTrue(db.operations().isEmpty());assertFalse(repository.snapshot().submitted)
    }
    @Test fun externalOwnerAndRevokedOrDisabledAccessBlockBooking()=runBlocking(Dispatchers.IO) {
        val repository=repo();repository.add(booking(1))
        db.put("addon-settings","current",AddonSettings(shoppingPurchaseOwner="external").json())
        assertTrue(runCatching { repository.submit() }.isFailure)
        assertTrue(runCatching { repo(emptySet()).snapshot() }.isFailure)
        db.put("addon-settings","current",AddonSettings().json());db.put("/system/config","current","{\"FEATURE_FLAG_STOCK\":false}")
        assertTrue(runCatching { repository.submit() }.isFailure);assertEquals(0,posts.get())
    }
    @Test fun cancellationBeforeRequestRestoresGuardedStateAndOrdinarySyncCannotReplayIt()=runBlocking(Dispatchers.IO) {
        val guarded=CachedGrocyRepository(db,address,"synthetic",MutationTransport(500),beforeRequest={throw CancellationException("Before HTTP")})
        val repository=GrocyShoppingTripRepository(setOf("ADMIN"),db,guarded)
        repository.add(booking(1))
        try { repository.submit();fail("Expected cancellation") }catch(_:CancellationException){}
        assertEquals("guarded",db.operations().single().state)
        cache.drain();assertEquals(0,posts.get())
        assertTrue(repo().submit().confirmed);assertEquals(1,posts.get())
    }

    @Test fun knownScanAddsAnUnbookedDraftWithExactBarcodeQuantityAndConvertedTotalPrice()=runBlocking(Dispatchers.IO) {
        val suggestion=ScanSuggestion(ScanCode("household-milk",ScanFormat.Qr),"Grocy",productIds=listOf(1),
            barcodes=listOf(BarcodeMetadata(1,unit=3,amount="1.25001",store=9,price="10.00008",note="Case")))
        val draft=repo().addScan(suggestion)
        val booking=draft.lines.single().booking
        assertEquals(0,BigDecimal("5.00004").compareTo(booking.amount))
        assertEquals(0,BigDecimal("2").compareTo(booking.price))
        assertEquals(9L,booking.store);assertEquals("Case",booking.note);assertNull(booking.date)
        assertEquals(0,posts.get());assertTrue(db.operations().isEmpty())
    }

    @Test fun tarePriceUsesNetWeightAndChangedTareBlocksBooking()=runBlocking(Dispatchers.IO) {
        tare=BigDecimal.ONE
        val suggestion=ScanSuggestion(ScanCode("household-weight",ScanFormat.Qr),"Grocy",productIds=listOf(1),
            barcodes=listOf(BarcodeMetadata(1,amount="5.00004",price="8.00008")))
        val draft=repo().addScan(suggestion)
        assertEquals(BigDecimal.ONE,draft.lines.single().tare)
        assertEquals(0,BigDecimal("2").compareTo(draft.lines.single().booking.price))
        assertTrue(runCatching { repo().edit(draft.lines.single().id,BigDecimal.ONE,null,null) }.isFailure)
        tare=BigDecimal("2")
        assertTrue(runCatching { repo().submit() }.isFailure);assertEquals(0,posts.get())
    }

}
