package app.stillroom.data

import app.stillroom.domain.ServerAddress
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.util.Base64

/** Only the authenticated Grocy file API. Never follows an add-on's arbitrary image URL. */
class GrocyFiles(private val address:ServerAddress,private val key:String) {
    private val client=HttpClients.base
    suspend fun read(group:String,name:String):ByteArray = recipeBytes(client,request(group,if(group=="userfiles")app.stillroom.domain.UserfileReference.parse(name).storageName else name).get().build(),5_242_880)
    suspend fun upload(name:String,bytes:ByteArray) {
        require(bytes.isNotEmpty() && bytes.size<=5_242_880) { "Choose a file smaller than 5 MB." }
        recipeBytes(client,request("userfiles",name).put(bytes.toRequestBody("application/octet-stream".toMediaType())).build(),1_048_576)
    }
    private fun request(group:String,name:String):Request.Builder {
        require(group in setOf("userfiles","productpictures","recipepictures"))
        require(name.isNotBlank() && name.length<=512 && name.none { it.isISOControl() } && '/' !in name && '\\' !in name && ".." !in name)
        val encoded=Base64.getEncoder().encodeToString(name.toByteArray(Charsets.UTF_8))
        val url=address.apiBase.toHttpUrl().newBuilder().addPathSegment("files").addPathSegment(group).addPathSegment(encoded).build()
        return Request.Builder().url(url).header("GROCY-API-KEY",key)
    }
}
