package app.stillroom.data

import app.stillroom.domain.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private fun recipeClient()=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
    .retryOnConnectionFailure(false).callTimeout(15,TimeUnit.SECONDS)

internal suspend fun recipeBytes(client:OkHttpClient,request:Request,limit:Long):ByteArray=suspendCancellableCoroutine { c->
    val call=client.newCall(request);c.invokeOnCancellation { call.cancel() }
    call.enqueue(object:Callback {
        override fun onFailure(call:Call,e:java.io.IOException) { if(c.isActive)c.resumeWithException(IllegalStateException("Request outcome unavailable. Refresh before retrying a save.")) }
        override fun onResponse(call:Call,response:Response) {
            try {
                val bytes=response.use {
                    if(!it.isSuccessful)throw GrocyFailure(it.code)
                    val source=it.body!!.source();check(!(source.request(limit+1) && source.buffer.size>limit)) { "Response too large." };source.readByteArray()
                }
                if(c.isActive)c.resume(bytes)
            }catch(e:Exception) { if(c.isActive)c.resumeWithException(e) }
        }
    })
}
class RecipeFiles(private val address:ServerAddress,private val key:String) {
    private val client=recipeClient().build()
    suspend fun request(name:String,bytes:ByteArray?):ByteArray {
        require(name.isNotBlank() && !name.contains('/') && !name.contains(".."))
        val encoded=java.util.Base64.getEncoder().encodeToString(name.toByteArray(Charsets.UTF_8))
        val url=address.apiBase.toHttpUrl().newBuilder().addPathSegment("files").addPathSegment("recipepictures").addPathSegment(encoded).build()
        val builder=Request.Builder().url(url).header("GROCY-API-KEY",key)
        if(bytes==null)builder.get() else builder.put(bytes.toRequestBody("application/octet-stream".toMediaType()))
        return recipeBytes(client,builder.build(),5_242_880)
    }
}
/** Anonymous URL reader: only public HTTPS hosts, no redirects, no cookies, no household payload. */
class RecipeUrlReader {
    private val client=recipeClient().dns(object:Dns {
        override fun lookup(host:String):List<InetAddress> {
        val addresses=InetAddress.getAllByName(host).toList()
        check(addresses.isNotEmpty() && addresses.none { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || it.isMulticastAddress || (it.address.size==16 && (it.address[0].toInt() and 0xfe)==0xfc) }) { "Use a public recipe URL." }
        return addresses
        }
    }).build()
    suspend fun read(source:String):RecipeImport {
        val url=source.toHttpUrl();require(url.scheme=="https" && url.username.isEmpty() && url.password.isEmpty() && source.length<=4096 && url.port==443)
        val bytes=recipeBytes(client,Request.Builder().url(url).header("Accept","text/html").get().build(),2_097_152)
        return parse(source,bytes.toString(Charsets.UTF_8))
    }
    fun parse(source:String,html:String):RecipeImport {
        val document=Jsoup.parse(html)
        fun candidates(value:JsonElement):List<JsonObject> = when(value) {
            is JsonArray->value.flatMap(::candidates)
            is JsonObject->listOf(value)+value.values.filter { it is JsonObject || it is JsonArray }.flatMap(::candidates)
            else->emptyList()
        }
        val found=document.select("script[type=application/ld+json]").flatMap { script->runCatching { candidates(Json.parseToJsonElement(script.data())) }.getOrDefault(emptyList()) }.filter { row->
            val type=row["@type"]
            type?.let { (if(it is JsonArray)it else JsonArray(listOf(it))).any { t->(t as? JsonPrimitive)?.content?.substringAfterLast('/')=="Recipe" } }==true
        }
        // Multiple candidates are ambiguous; offer a blank manual review rather than silently picking one.
        val row=found.singleOrNull()
        fun text(value:JsonElement?):List<String> = when(value) {
            is JsonPrimitive->listOf(Jsoup.parse(value.content).text())
            is JsonArray->value.flatMap(::text)
            is JsonObject->text(value["itemListElement"] ?: value["text"] ?: value["name"])
            else->emptyList()
        }
        val yield=text(row?.get("recipeYield")).singleOrNull().orEmpty()
        val servings=Regex("^([0-9]+(?:\\.[0-9]+)?)(?:\\s+servings?)?$",RegexOption.IGNORE_CASE).matchEntire(yield)?.groupValues?.get(1)?.toBigDecimalOrNull()?.takeIf { it.signum()>0 }
        return RecipeImport(source,text(row?.get("name")).firstOrNull().orEmpty().take(200),text(row?.get("recipeInstructions")).joinToString("\n").take(50000),text(row?.get("recipeIngredient")).filter { it.isNotBlank() }.take(200).map { it.take(2000) },servings)
    }
}
fun recipeSteps(html:String):List<String> {
    val d=Jsoup.parse(html);d.select("[data-stillroom-source]").remove()
    d.select("p").filter { it.text().startsWith("Source: ") && it.selectFirst("a[href]")!=null }.forEach { it.remove() }
    val blocks=d.select("li").ifEmpty { d.select("p") }
    return if(blocks.isNotEmpty())blocks.map { it.text() }.filter { it.isNotBlank() } else d.body().wholeText().lines().filter { it.isNotBlank() }
}
fun recipeDescription(instructions:String,source:String):String {
    val d=Jsoup.parseBodyFragment("");instructions.lines().filter { it.isNotBlank() }.forEach { d.body().appendElement("p").text(it) }
    if(source.isNotBlank()) {
        val url=source.toHttpUrl();require(url.scheme in setOf("http","https") && url.username.isEmpty() && url.password.isEmpty())
        d.body().appendElement("p").appendText("Source: ").appendElement("a").attr("href",source).text(source)
    }
    return d.body().html()
}
fun recipeSource(html:String):String {
    val d=Jsoup.parse(html)
    return (d.selectFirst("[data-stillroom-source] a[href]") ?: d.select("p").firstOrNull { it.text().startsWith("Source: ") }?.selectFirst("a[href]"))?.attr("href").orEmpty()
}
