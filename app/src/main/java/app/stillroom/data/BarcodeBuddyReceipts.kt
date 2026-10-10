package app.stillroom.data

import app.stillroom.domain.ScanCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.UUID

/** Shared by the account session. A receipt is durable before the single outbound attempt. */
class BarcodeBuddyReceipts(private val db:AccountDatabase) {
    private val lock=Mutex()
    suspend fun submit(code:ScanCode,scan:suspend(ScanCode)->String,refresh:suspend()->Unit={}):String=lock.withLock {
        code.validated()
        check(pending().none { it.second==code.raw }) { "This barcode has an uncertain BarcodeBuddy scan. Review it in Add-on settings before scanning it again." }
        val id=UUID.randomUUID().toString()
        fun payload(state:String)=buildJsonObject { put("state",state);put("barcode",code.raw) }.toString()
        db.put("barcodebuddy-receipts",id,payload("needs-review"))
        val message=scan(code)
        db.put("barcodebuddy-receipts",id,payload("confirmed"))
        try { refresh() }catch(e:CancellationException){throw e}catch(_:Exception){}
        message
    }
    fun pending():List<Pair<String,String>> = db.resourceRows("barcodebuddy-receipts").mapNotNull { row->
        val value=Json.parseToJsonElement(row.payload).jsonObject
        if(value["state"]?.jsonPrimitive?.content=="needs-review")row.rowId to value["barcode"]?.jsonPrimitive?.content.orEmpty() else null
    }
    suspend fun reviewed(id:String)=lock.withLock {
        val value=db.get("barcodebuddy-receipts",id)?.let { Json.parseToJsonElement(it).jsonObject } ?: error("Receipt unavailable.")
        db.put("barcodebuddy-receipts",id,JsonObject(value+("state" to JsonPrimitive("reviewed"))).toString())
    }
}
