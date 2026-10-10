package app.stillroom.domain

import kotlinx.serialization.json.*

data class CustomRecordsSnapshot(val entities:List<JsonObject> = emptyList(),val objects:List<JsonObject> = emptyList(),val fields:List<JsonObject> = emptyList(),val stale:Boolean=false,val selectedEntity:Long?=null,val offset:Int=0,val hasMore:Boolean=false)
interface CustomRecordsRepository {
    suspend fun snapshot(entityId:Long?=null,offset:Int=0):CustomRecordsSnapshot
    suspend fun values(entity:JsonObject,id:Long):JsonObject
    suspend fun save(entity:JsonObject,id:Long?,values:JsonObject,baseline:JsonObject):String
    suspend fun delete(entity:JsonObject,id:Long):String
    suspend fun sync()
    suspend fun confirmed(operation:String):Boolean
    suspend fun file(group:String,name:String):ByteArray
    suspend fun upload(bytes:ByteArray,extension:String,displayName:String?=null):String
}
class ManageCustomRecords(private val repository:CustomRecordsRepository) {
    suspend fun snapshot(entityId:Long?=null,offset:Int=0)=repository.snapshot(entityId,offset)
    suspend fun values(entity:JsonObject,id:Long)=repository.values(entity,id)
    suspend fun save(entity:JsonObject,id:Long?,values:JsonObject,baseline:JsonObject)=repository.save(entity,id,values,baseline)
    suspend fun delete(entity:JsonObject,id:Long)=repository.delete(entity,id)
    suspend fun sync()=repository.sync()
    suspend fun confirmed(operation:String)=repository.confirmed(operation)
    suspend fun file(group:String,name:String)=repository.file(group,name)
    suspend fun upload(bytes:ByteArray,extension:String,displayName:String?=null)=repository.upload(bytes,extension,displayName)
}

/** Grocy userfields store base64(storage name)_base64(display name), unlike product pictures. */
data class UserfileReference(val storageName:String,val displayName:String) {
    fun stored():String {
        val encoder=java.util.Base64.getEncoder()
        return encoder.encodeToString(storageName.toByteArray(Charsets.UTF_8))+"_"+encoder.encodeToString(displayName.toByteArray(Charsets.UTF_8))
    }
    companion object {
        fun parse(value:String):UserfileReference {
            val parts=value.split('_',limit=2)
            if(parts.size!=2)return UserfileReference(value,value)
            fun decode(value:String)=String(java.util.Base64.getDecoder().decode(value),Charsets.UTF_8)
            return runCatching { UserfileReference(decode(parts[0]),decode(parts[1])) }.getOrElse { UserfileReference(value,value) }
        }
    }
}
