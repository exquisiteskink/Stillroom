package app.stillroom.data

import app.stillroom.domain.*
import kotlinx.serialization.json.*
import okhttp3.*
import java.util.concurrent.TimeUnit

/** Separate credentials and a single explicit mutation attempt. Never changes the global scan mode. */
class BarcodeBuddyClient(private val address:ServerAddress,private val key:String) {
    private val client=HttpClients.base.newBuilder().callTimeout(15,TimeUnit.SECONDS).build()
    init { require(key.isNotBlank() && key.none { it.isISOControl() }) }
    suspend fun mode():String {
        val response=json(Request.Builder().url(address.apiBase+"/state/getmode").header("BBUDDY-API-KEY",key).get().build())
        val data=response["data"]
        check((response["result"] as? JsonObject)?.get("result")?.jsonPrimitive?.content=="OK") { "BarcodeBuddy mode is unavailable." }
        val mode=(data as? JsonObject)?.get("mode")?.jsonPrimitive?.contentOrNull ?: error("BarcodeBuddy mode is unavailable.")
        return mapOf("0" to "Consume","1" to "Record spoilage","2" to "Purchase","3" to "Open","4" to "Check stock","5" to "Add to shopping list","6" to "Consume all")[mode] ?: mode.take(80)
    }
    suspend fun scan(code:ScanCode):String {
        code.validated()
        val request=Request.Builder().url(address.apiBase+"/action/scan").header("BBUDDY-API-KEY",key)
            .post(MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("barcode",code.raw).build()).build()
        val response=json(request)
        val result=response["result"] as? JsonObject
        check(result?.get("result")?.jsonPrimitive?.content=="OK" && result["http_code"]?.jsonPrimitive?.intOrNull==200) { "BarcodeBuddy did not confirm the scan. Inspect it before scanning again." }
        return (response["data"] as? JsonObject)?.get("result")?.jsonPrimitive?.contentOrNull?.take(1000) ?: "BarcodeBuddy confirmed the scan."
    }
    private suspend fun json(request:Request):JsonObject {
        val bytes=try { recipeBytes(client,request,1_048_576) }catch(e:GrocyFailure) {
            throw IllegalStateException(if(e.status in setOf(401,403))"BarcodeBuddy rejected its API key. Update it in Add-on settings." else "BarcodeBuddy request failed (HTTP ${e.status}).")
        }
        return try { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }catch(_:Exception) { throw IllegalStateException("BarcodeBuddy returned an unreadable response.") }
    }
}
