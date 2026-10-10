package app.stillroom.domain

import java.net.URI
import kotlinx.serialization.json.*

data class AddonSettings(val barcodeBuddyUrl:String="",val barcodeBuddyInsecure:Boolean=false,val barcodeBuddyConfigured:Boolean=false,val publicLookup:Boolean=true,val webAddonUrl:String="",val webAddonInsecure:Boolean=false,val shoppingPurchaseOwner:String="stillroom") {
    fun validated():AddonSettings {
        if(barcodeBuddyUrl.isNotBlank())ServerAddress.parse(barcodeBuddyUrl,barcodeBuddyInsecure)
        if(webAddonUrl.isNotBlank())validateAddonWebUrl(webAddonUrl,webAddonInsecure)
        require(shoppingPurchaseOwner in setOf("stillroom","external"))
        return this
    }
    fun json()=buildJsonObject { put("bb_url",barcodeBuddyUrl);put("bb_http",barcodeBuddyInsecure);put("bb_configured",barcodeBuddyConfigured);put("public_lookup",publicLookup);put("web_url",webAddonUrl);put("web_http",webAddonInsecure);put("purchase_owner",shoppingPurchaseOwner) }.toString()
    companion object {
        fun parse(text:String?):AddonSettings {
            val o=text?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return AddonSettings()
            fun str(k:String)=(o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
            fun bool(k:String,default:Boolean=false)=(o[k] as? JsonPrimitive)?.booleanOrNull ?: default
            return AddonSettings(str("bb_url"),bool("bb_http"),bool("bb_configured"),bool("public_lookup",true),str("web_url"),bool("web_http"),str("purchase_owner").ifBlank { "stillroom" })
        }
    }
}
fun validateAddonWebUrl(value:String,allowInsecure:Boolean):String {
    val uri=URI(value)
    require(value.length<=4096 && uri.scheme in setOf("https","http") && uri.host!=null && uri.userInfo==null && uri.fragment==null && (uri.scheme=="https" || allowInsecure)) { "Enter an HTTPS add-on URL, or explicitly allow HTTP." }
    require(uri.query?.split('&')?.none { it.substringBefore('=').lowercase() in setOf("api_key","grocy-api-key","bbuddy-api-key","token","key","password") }!=false) { "Keep credentials out of browser links." }
    return value
}
