package app.stillroom.domain

import kotlinx.serialization.json.*

/** Elapsed time is authoritative within a boot; the saved wall deadline restores across reboot. */
data class CookingTimer(val id:String,val account:String,val recipe:Long,val label:String,
    val wallDeadline:Long,val elapsedDeadline:Long,val boot:Int,val notified:Boolean=false) {
    fun remaining(wall:Long,elapsed:Long,currentBoot:Int):Long =
        ((if(boot==currentBoot)elapsedDeadline-elapsed else wallDeadline-wall)).coerceAtLeast(0)
    fun json()=buildJsonObject {
        put("id",id);put("account",account);put("recipe",recipe);put("label",label)
        put("wall",wallDeadline);put("elapsed",elapsedDeadline);put("boot",boot);put("notified",notified)
    }
    companion object {
        fun parse(row:JsonObject)=CookingTimer(row.getValue("id").jsonPrimitive.content,row.getValue("account").jsonPrimitive.content,
            row.getValue("recipe").jsonPrimitive.long,row.getValue("label").jsonPrimitive.content,
            row.getValue("wall").jsonPrimitive.long,row.getValue("elapsed").jsonPrimitive.long,row.getValue("boot").jsonPrimitive.int,
            row["notified"]?.jsonPrimitive?.booleanOrNull ?: false)
    }
}

data class CookingSession(val recipe:Long,val step:Int=0,val checked:List<Long> = emptyList()) {
    fun json()=buildJsonObject { put("recipe",recipe);put("step",step);put("checked",JsonArray(checked.map(::JsonPrimitive))) }
    companion object {
        fun parse(row:JsonObject)=CookingSession(row.getValue("recipe").jsonPrimitive.long,
            row["step"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(0) ?: 0,
            (row["checked"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.longOrNull }.orEmpty())
    }
}
