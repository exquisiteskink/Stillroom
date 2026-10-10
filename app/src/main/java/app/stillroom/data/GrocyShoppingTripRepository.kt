package app.stillroom.data

import app.stillroom.domain.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.util.UUID

/** One persisted trip per account. All reviewed lines are durably queued before any send. */
class GrocyShoppingTripRepository(private val grants:Set<String>?,private val db:AccountDatabase,private val cache:CachedGrocyRepository):ShoppingTripRepository {
    private val lock=Mutex()
    private fun access() {
        check(StockAccess.canRead(grants) && StockAccess.canWrite(grants,StockAction.Purchase)) { "Stock purchase access denied." }
        cache.requireCacheAccess()
    }
    private fun current()=db.get("shopping-trip","current")?.let(ShoppingTrip::parse) ?: ShoppingTrip(UUID.randomUUID().toString())
    private fun save(trip:ShoppingTrip):ShoppingTrip { db.put("shopping-trip","current",trip.json());return statuses(trip) }
    private fun statuses(trip:ShoppingTrip)=trip.copy(lines=trip.lines.map { line -> line.copy(state=db.operation(line.id)?.state ?: if(trip.submitted)"not queued" else "draft") })
    override suspend fun snapshot():ShoppingTrip=lock.withLock { access();statuses(current()) }
    override suspend fun addScan(suggestion:ScanSuggestion):ShoppingTrip {
        access()
        val id=suggestion.productIds.singleOrNull() ?: error("Choose a product before adding this scan.")
        require(id>0)
        val product=Json.parseToJsonElement(cache.read("/stock/products/$id").payload).jsonObject["product"]!!.jsonObject
        val stockUnit=product.houseId("qu_id_stock") ?: error("This product has no stock unit.")
        val barcode=suggestion.barcodes.singleOrNull { it.productId==id }
        val amount=barcode?.amount?.toBigDecimal() ?: BigDecimal.ONE
        val unit=barcode?.unit ?: stockUnit
        val factor=if(unit==stockUnit)BigDecimal.ONE else {
            Json.parseToJsonElement(cache.read("/objects/quantity_unit_conversions_resolved").payload).jsonArray
                .map { it.jsonObject }.firstOrNull { it.houseId("product_id")==id && it.houseId("from_qu_id")==unit && it.houseId("to_qu_id")==stockUnit }
                ?.get("factor")?.jsonPrimitive?.content?.toBigDecimal() ?: error("Review this barcode's quantity unit before adding it.")
        }
        val tare=tare(product)
        val price=if(cache.capabilities().enabled("STOCK_PRICE_TRACKING"))barcode?.price?.toBigDecimal()?.let { reviewedStockPrice(it,amount,factor,true,tare ?: BigDecimal.ZERO) } else null
        return addDraft(StockBooking(StockAction.Purchase,id,amount,factor,price=price,note=barcode?.note?.takeIf { it.isNotBlank() },store=barcode?.store,reviewedStockUnit=stockUnit),tare,true)
    }
    private fun tare(product:JsonObject):BigDecimal?=if(product.houseText("enable_tare_weight_handling")=="1")product.houseText("tare_weight").toBigDecimalOrNull() ?: BigDecimal.ZERO else null
    private fun sameTare(a:BigDecimal?,b:BigDecimal?)=(a==null && b==null) || (a!=null && b!=null && a.compareTo(b)==0)
    override suspend fun add(booking:StockBooking):ShoppingTrip=addDraft(booking)
    private suspend fun addDraft(booking:StockBooking,expectedTare:BigDecimal?=null,compareTare:Boolean=false):ShoppingTrip=lock.withLock {
        access();val trip=current();check(!trip.submitted) { "Finish the reviewed trip before scanning another." }
        check(trip.lines.size<100) { "Review this trip before adding more items." }
        require(booking.action==StockAction.Purchase && booking.stockEntryId==null)
        val normalized=booking.copy(amount=booking.amount.multiply(booking.factor),factor=BigDecimal.ONE)
        normalized.payload()
        val product=Json.parseToJsonElement(cache.read("/stock/products/${booking.productId}").payload).jsonObject["product"]!!.jsonObject
        val unit=product.houseId("qu_id_stock") ?: error("This product has no stock unit.")
        check(booking.reviewedStockUnit==null || booking.reviewedStockUnit==unit) { "This product's stock unit changed. Scan it again." }
        val currentTare=tare(product)
        check(!compareTare || sameTare(expectedTare,currentTare)) { "This product's tare settings changed. Scan it again." }
        require(currentTare==null || (currentTare.signum()>=0 && normalized.amount>currentTare)) { "Gross quantity must exceed tare weight." }
        save(trip.copy(lines=trip.lines+TripLine(UUID.randomUUID().toString(),product.houseText("name"),unit,normalized,tare=currentTare)))
    }
    override suspend fun edit(id:String,amount:BigDecimal,date:String?,price:BigDecimal?):ShoppingTrip=lock.withLock {
        access();val trip=current();check(!trip.submitted) { "A submitted trip is locked." }
        val line=trip.lines.firstOrNull { it.id==id } ?: error("This line no longer exists.")
        require(line.tare==null || amount>line.tare) { "Gross quantity must exceed tare weight." }
        save(trip.copy(lines=trip.lines.map { if(it.id==id)it.copy(booking=it.booking.copy(amount=amount,date=date,price=price).also { b->b.payload() }) else it }))
    }
    override suspend fun remove(id:String):ShoppingTrip=lock.withLock {
        access();val trip=current();check(!trip.submitted) { "A submitted trip is locked." }
        save(trip.copy(lines=trip.lines.filterNot { it.id==id }))
    }
    override suspend fun newTrip():ShoppingTrip=lock.withLock {
        access();val previous=statuses(current())
        check(!previous.submitted || previous.confirmed) { "Inspect the unfinished trip before starting another." }
        save(ShoppingTrip(UUID.randomUUID().toString()))
    }
    override suspend fun submit():ShoppingTrip=lock.withLock {
        access();var trip=current();check(trip.lines.isNotEmpty()) { "Scan an item first." }
        check(AddonSettings.parse(db.get("addon-settings","current")).shoppingPurchaseOwner=="stillroom") { "Another scanner owns purchases. Change purchase ownership in Add-on settings before booking this trip." }
        check(cache.capabilities().allows("/stock/products/1/add") && cache.capabilities().requestSupported("POST","/stock/products/1/add")!=false) { "Stock purchasing is disabled or unsupported by this server." }
        if(statuses(trip).confirmed)return@withLock statuses(trip)
        check(trip.lines.none { db.operation(it.id)?.state in setOf("needs-review","in-flight","failed") }) { "Inspect this trip's unconfirmed purchases in Pending changes before continuing." }
        // Fresh product metadata validates the reviewed stock unit before any line reaches Grocy.
        for(line in trip.lines.filter { db.operation(it.id)?.state!="confirmed" }) {
            val product=cache.readFresh("/stock/products/${line.booking.productId}")!!.jsonObject["product"]!!.jsonObject
            check(sameTare(line.tare,tare(product))) { "${line.name}'s tare settings changed. Review it again before booking." }
            check(product.houseId("qu_id_stock")==line.unit) { "${line.name}'s stock unit changed. Review it in Grocy before booking." }
            check(line.booking.date==null || cache.capabilities().enabled("STOCK_BEST_BEFORE_DATE_TRACKING")) { "Due-date tracking was disabled. Clear this draft's due dates before confirming." }
            check(line.booking.price==null || cache.capabilities().enabled("STOCK_PRICE_TRACKING")) { "Price tracking was disabled. Clear this draft's prices before confirming." }
            line.booking.payload()
        }
        if(!trip.submitted) { trip=trip.copy(submitted=true);save(trip) }
        for(line in trip.lines)cache.enqueue("POST",line.booking.path(),line.booking.payload().toString(),"/stock/products/${line.booking.productId}",operationId=line.id,guarded=true)
        for(line in trip.lines) {
            if(db.operation(line.id)?.state=="confirmed")continue
            cache.drain(line.id,onlyGuarded=true)
            if(db.operation(line.id)?.state!="confirmed")break
        }
        statuses(trip)
    }
}
