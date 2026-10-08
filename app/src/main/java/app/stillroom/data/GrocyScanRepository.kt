package app.stillroom.data

import app.stillroom.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Separate unauthenticated, read-only public client. No Grocy headers, redirects, images or household records. */
class OpenFoodFactsLookup(private val origin: String = "https://world.openfoodfacts.org") {
    private val client=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(15,TimeUnit.SECONDS).build()
    suspend fun lookup(code: String): JsonObject? {
        require(validGtin(code))
        require(origin == "https://world.openfoodfacts.org" || origin.matches(Regex("http://127\\.0\\.0\\.1:[0-9]+")))
        val request=Request.Builder().url("$origin/api/v2/product/$code?fields=product_name,brands")
            .header("User-Agent","Stillroom/0.0.1 (Android; local Grocy companion)").get().build()
        return suspendCancellableCoroutine { continuation ->
            val call=client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object:Callback {
                override fun onFailure(call:Call,e:java.io.IOException) { if(continuation.isActive) continuation.resumeWithException(IllegalStateException("Public product lookup unavailable.")) }
                override fun onResponse(call:Call,response:Response) {
                    try {
                        val result=response.use {
                            if(it.code == 404) return@use null
                            check(it.isSuccessful) { "Public product lookup unavailable." }
                            val source=it.body!!.source()
                            check(!(source.request(1_048_577) && source.buffer.size>1_048_576))
                            val value=Json.parseToJsonElement(source.readUtf8()).jsonObject
                            if(value["status"]?.jsonPrimitive?.intOrNull==1) value["product"] as? JsonObject else null
                        }
                        if(continuation.isActive) continuation.resume(result)
                    } catch(_:Exception) { if(continuation.isActive) continuation.resumeWithException(IllegalStateException("Public product lookup unavailable.")) }
                }
            })
        }
    }
}

class GrocyScanRepository(private val grants:Set<String>?,private val db:AccountDatabase,
    private val cache:CachedGrocyRepository,private val external:suspend(String)->Pair<Int,String>,
    private val publicLookup:suspend(String)->JsonObject? = OpenFoodFactsLookup()::lookup): ScanRepository {
    private fun access()=check(StockAccess.canRead(grants)) { "Stock access denied." }
    private suspend fun rows(path:String,fresh:Boolean=false): Pair<List<JsonObject>,Boolean> {
        if(fresh) return cache.readFresh(path)!!.jsonArray.map { it.jsonObject } to false
        val read=cache.read(path)
        return Json.parseToJsonElement(read.payload).jsonArray.map { it.jsonObject } to read.stale
    }
    private fun JsonObject.value(key:String)=(get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.id(key:String)=value(key).toLongOrNull()
    override suspend fun lookup(code:ScanCode):ScanSuggestion {
        access();code.validated()
        val (barcodes,stale)=rows("/objects/product_barcodes")
        val ids=barcodes.filter { it.value("barcode")==code.raw }.mapNotNull { it.id("product_id") }.distinct()
        if(ids.isNotEmpty()) return ScanSuggestion(code,"Grocy",productIds=ids,stale=stale)
        check(!stale) { "Refresh Grocy before looking up an unknown barcode." }
        if(!code.publicLookup) return ScanSuggestion(code,"Manual review")
        val config=cache.read("/system/config")
        check(!config.stale) { "Refresh Grocy's lookup settings before external lookup." }
        val enabled=Json.parseToJsonElement(config.payload).jsonObject.value("STOCK_BARCODE_LOOKUP_PLUGIN").isNotBlank()
        if(enabled) {
            val specification=cache.read("/openapi/specification")
            val paths=Json.parseToJsonElement(specification.payload).jsonObject["paths"]?.jsonObject
            if(!specification.stale && paths?.containsKey("/stock/barcodes/external-lookup/{barcode}")==true) {
                val response = try { external("/stock/barcodes/external-lookup/${code.publicCode}?add=false") }
                    catch(error:CancellationException) { throw error }
                    catch(_:Exception) { 0 to "" }
                val (status,body)=response
                if(status in setOf(401,403)) { cache.recordDenial(status); throw GrocyFailure(status) }
                if(status in 200..299) {
                    val value=runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                    if(value?.value("name")?.isNotBlank()==true) return suggestion(code,"Grocy external lookup",value.value("name"),"")
                }
            }
        }
        var stalePublic = false
        val product = try {
            publicLookup(code.publicCode).also { value -> value?.let { db.put("scanner-public",code.publicCode,it.toString()) } }
        } catch(error:CancellationException) { throw error }
        catch(_:Exception) {
            stalePublic = true
            db.get("scanner-public",code.publicCode)?.let { Json.parseToJsonElement(it).jsonObject }
        }
        return suggestion(code,if(product==null)"Manual review" else "Open Food Facts",product?.value("product_name").orEmpty(),product?.value("brands").orEmpty()).copy(stale=stalePublic)
    }
    private fun suggestion(code:ScanCode,source:String,name:String,description:String)=ScanSuggestion(code,source,name.take(200),description.take(5000))
    override suspend fun choices():Pair<List<Pair<Long,String>>,List<Pair<Long,String>>> {
        access()
        fun choices(rows:List<JsonObject>)=rows.mapNotNull { row -> row.id("id")?.let { it to row.value("name") } }
        return choices(rows("/objects/quantity_units").first) to choices(rows("/objects/locations").first)
    }
    override suspend fun create(review:ScanReview):String {
        access();check(HouseholdAccess.has(grants,"MASTER_DATA_EDIT")) { "Product creation access denied." }
        require(review.unit>0 && review.location>0)
        review.code.validated();require(review.name.isNotBlank() && review.name.length<=200 && review.description.length<=5000)
        require(rows("/objects/quantity_units",true).first.any { it.id("id")==review.unit }) { "Choose an existing Grocy stock unit." }
        require(rows("/objects/locations",true).first.any { it.id("id")==review.location }) { "Choose an existing Grocy location." }
        check(rows("/objects/product_barcodes",true).first.none { it.value("barcode")==review.code.raw }) { "This barcode now exists in Grocy. Look it up again." }
        check(db.operations().none { it.method=="POST" && it.path=="/objects/products" && it.state!="failed" && db.get("scanner-review",it.clientOperationId)==review.code.raw }) { "This barcode already has a reviewed creation. Inspect Pending changes." }
        val operation=UUID.randomUUID().toString()
        // Persist the reviewed barcode before queuing: a crash can leave an inert review, never an unreviewed creation.
        db.put("scanner-review",operation,review.code.raw)
        val fields=buildJsonObject {
            put("name",review.name.trim());put("description",review.description);put("location_id",review.location)
            put("qu_id_stock",review.unit);put("qu_id_purchase",review.unit);put("qu_id_consume",review.unit);put("qu_id_price",review.unit)
            put("min_stock_amount",0)
        }
        return cache.enqueue("POST","/objects/products",fields.toString(),"/objects/products",operationId=operation)
    }
    private fun followUp(operation:String)=UUID.nameUUIDFromBytes((operation+":barcode").toByteArray()).toString()
    override suspend fun sync() {
        access();cache.drain()
        for(operation in db.operations().filter { it.method=="POST" && it.path=="/objects/products" && it.state=="confirmed" }) {
            val code=db.get("scanner-review",operation.clientOperationId) ?: continue
            val id=operation.responsePayload?.let { Json.parseToJsonElement(it).jsonObject.id("created_object_id") } ?: continue
            cache.enqueue("POST","/objects/product_barcodes",buildJsonObject { put("product_id",id);put("barcode",code) }.toString(),"/objects/product_barcodes",operationId=followUp(operation.clientOperationId))
        }
        cache.drain()
    }
    override suspend fun createdProduct(operation:String):Long? {
        access()
        if(db.operation(followUp(operation))?.state!="confirmed") return null
        return db.operation(operation)?.responsePayload?.let { Json.parseToJsonElement(it).jsonObject.id("created_object_id") }
    }
    override suspend fun operations():List<PendingChange> { access();return db.operations().filter { db.get("scanner-review",it.clientOperationId)!=null || it.path=="/objects/product_barcodes" }.map { PendingChange(it.clientOperationId,it.method,it.path,it.state,it.detail,false) } }
}
