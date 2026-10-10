package app.stillroom.domain

import kotlinx.serialization.json.*

/** Missing metadata is unknown, never a permission grant. Grocy still authorizes every request. */
data class ServerCapabilities(val disabled:Set<String> = emptySet(),val paths:Set<String> = emptySet(),val entities:Set<String> = emptySet(),val known:Boolean=false,val methods:Map<String,Set<String>> = emptyMap()) {
    fun enabled(feature:String)=feature !in disabled
    fun supports(path:String,method:String="get",spec:JsonObject?=null)=path in paths && (spec==null || (spec["paths"] as? JsonObject)?.get(path)?.jsonObject?.containsKey(method)==true)
    fun requestSupported(method:String,path:String):Boolean? {
        if(paths.isEmpty())return null
        if(path=="/openapi/specification")return true
        val actual=path.substringBefore('?').split('/').filter { it.isNotBlank() }
        val route=paths.firstOrNull { template->
            val parts=template.split('/').filter { it.isNotBlank() }
            parts.size==actual.size && parts.zip(actual).all { (expected,value)->expected.startsWith("{") && expected.endsWith("}") || expected==value }
        } ?: return false
        if(methods[route].isNullOrEmpty())return null
        return method.lowercase() in methods[route].orEmpty()
    }
    fun allows(path:String):Boolean {
        if(path.startsWith("/stock/shoppinglist/") && !enabled("SHOPPINGLIST"))return false
        if(path.startsWith("/objects/meal_plan") && !enabled("RECIPES_MEALPLAN"))return false
        if(path.startsWith("/stock/") && path.endsWith("/open") && !enabled("STOCK_PRODUCT_OPENED_TRACKING"))return false
        return featureForPath(path)?.let(::enabled) ?: true
    }
    companion object {
        fun parse(config:JsonObject,spec:JsonObject):ServerCapabilities {
            val disabled=config.filter { (key,value)->key.startsWith("FEATURE_FLAG_") && (value as? JsonPrimitive)?.content in setOf("false","0") }.keys.map { it.removePrefix("FEATURE_FLAG_") }.toSet()
            val schemas=(spec["components"] as? JsonObject)?.get("schemas") as? JsonObject
            val entities=(schemas?.get("ExposedEntity") as? JsonObject)?.get("enum") as? JsonArray
            val routes=(spec["paths"] as? JsonObject).orEmpty()
            return ServerCapabilities(disabled,routes.keys,entities?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty(),true,routes.mapValues { (_,v)->(v as? JsonObject)?.keys.orEmpty().filter { it in setOf("get","post","put","patch","delete") }.toSet() })
        }
    }
}
fun featureForPath(path:String):String? {
    val parts=path.substringBefore('?').split('/').filter { it.isNotBlank() }
    val root=if(parts.firstOrNull()=="objects")parts.getOrNull(1) else parts.firstOrNull()
    return when(root) {
        "stock","products","product_barcodes","product_barcodes_view","product_groups","locations","quantity_units","quantity_unit_conversions","quantity_unit_conversions_resolved","stock_log","stock_current_locations","products_last_purchased","products_average_price" -> "STOCK"
        "shopping_list","shopping_lists","shopping_locations" -> "SHOPPINGLIST"
        "recipes","recipes_pos","recipes_nestings","recipes_pos_resolved" -> "RECIPES"
        "meal_plan","meal_plan_sections" -> "RECIPES"
        "tasks","task_categories" -> "TASKS"
        "chores","chores_log" -> "CHORES"
        "batteries","battery_charge_cycles" -> "BATTERIES"
        "equipment" -> "EQUIPMENT"
        else -> null
    }
}
/** Explicit read-only allowlist: GET does not necessarily mean safe to poll in Grocy. */
fun refreshableGrocyPath(path:String):Boolean {
    if(path.contains('?') || path.contains('%'))return false
    val entities=setOf("products","product_barcodes","product_groups","locations","quantity_units","quantity_unit_conversions","quantity_unit_conversions_resolved","shopping_list","shopping_lists","shopping_locations","recipes","recipes_pos","recipes_nestings","meal_plan","meal_plan_sections","tasks","task_categories","chores","chores_log","batteries","battery_charge_cycles","equipment","stock_log","stock","userfields","userentities","userobjects")
    if(path.startsWith("/objects/"))return path.removePrefix("/objects/").substringBefore('/') in entities && path.split('/').drop(3).all { it.toLongOrNull()?.let { id->id>0 }==true }
    if(path.startsWith("/userfields/"))return Regex("^/userfields/[\\p{L}\\p{N}_ -]+/[1-9][0-9]*$").matches(path)
    return path in setOf("/stock","/stock/volatile","/tasks","/chores","/batteries","/recipes/fulfillment") ||
        Regex("^/stock/products/[1-9][0-9]*(/(locations|entries|price-history))?$").matches(path) ||
        Regex("^/stock/locations/[1-9][0-9]*/entries$").matches(path)
}
data class Grocycode(val entity:String,val id:Long,val stockId:String?=null) {
    companion object {
        fun parse(raw:String):Grocycode? {
            val m=Regex("^grcy:([pbcr]):([1-9][0-9]*)(?::([A-Za-z0-9_-]{1,128}))?$").matchEntire(raw) ?: return null
            val id=m.groupValues[2].toLongOrNull() ?: return null
            val stock=m.groupValues[3].takeIf { it.isNotBlank() }
            if(stock!=null && m.groupValues[1]!="p")return null
            return Grocycode(m.groupValues[1],id,stock)
        }
    }
}
data class BarcodeMetadata(val productId:Long,val unit:Long?=null,val amount:String?=null,val store:Long?=null,val price:String?=null,val note:String="") {
    companion object {
        fun parse(row:JsonObject):BarcodeMetadata? {
            fun text(key:String)=(row[key] as? JsonPrimitive)?.contentOrNull
            fun id(key:String)=text(key)?.toLongOrNull()?.takeIf { it>0 }
            fun decimal(key:String,positive:Boolean)=text(key)?.takeIf { it.toBigDecimalOrNull()?.let { d->if(positive)d.signum()>0 else d.signum()>=0 }==true }
            return BarcodeMetadata(id("product_id") ?: return null,id("qu_id"),decimal("amount",true),id("shopping_location_id"),decimal("last_price",false),text("note").orEmpty().take(5000))
        }
    }
}
data class LookupProductDefaults(val location:Long?=null,val stockUnit:Long?=null,val purchaseUnit:Long?=null,val factor:String?=null,val barcode:String?=null,val imageUrl:String?=null)
