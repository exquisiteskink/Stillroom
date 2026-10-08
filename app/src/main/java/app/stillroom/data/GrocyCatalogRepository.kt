package app.stillroom.data

import app.stillroom.domain.*
import kotlinx.serialization.json.*
import java.util.UUID

/** Native master-data records and battery operations share the account cache/outbox lease. */
class GrocyCatalogRepository(private val grants:Set<String>?,private val db:AccountDatabase,private val cache:CachedGrocyRepository):CatalogRepository {
    private suspend fun exposed():Set<String> {
        val read=cache.read("/openapi/specification")
        val schema=Json.parseToJsonElement(read.payload).jsonObject["components"]!!.jsonObject["schemas"]!!.jsonObject
        return schema["ExposedEntity"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
    }
    override suspend fun snapshot():CatalogSnapshot {
        val available=CatalogEntity.entries.filter { it.readable(grants) }
        if(available.isEmpty())return CatalogSnapshot()
        val exposed=exposed();var stale=false;val resources=linkedMapOf<String,List<JsonObject>>();val unavailable=mutableListOf<String>()
        suspend fun read(path:String,entity:String) {
            val read=cache.read(path);stale=stale||read.stale;resources[entity]=Json.parseToJsonElement(read.payload).jsonArray.map { it.jsonObject }
        }
        for(entity in available) {
            if(entity.entity !in exposed)unavailable+="${entity.label}: this server does not expose these records." else read("/objects/${entity.entity}",entity.entity)
        }
        if("userfields" in exposed)read("/objects/userfields","userfields")
        if(HouseholdAccess.has(grants,"BATTERIES") && "batteries" in exposed)read("/batteries","battery_status")
        return CatalogSnapshot(resources,stale,unavailable)
    }
    private suspend fun queue(method:String,path:String,payload:JsonObject,read:String,operationId:String?=null):String {
        check(db.operations().none { it.path==path && it.state in setOf("pending","in-flight","needs-review","guarded") }) { "An operation for this record is awaiting confirmation. Inspect Pending changes." }
        return cache.enqueue(method,path,payload.toString(),read,operationId=operationId)
    }
    override suspend fun save(entity:CatalogEntity,id:Long?,fields:JsonObject,userfields:JsonObject):String {
        check(entity.writable(grants)) { "Master-data editing denied." };require(id==null || id>0)
        require(entity.entity in exposed()) { "This server does not expose these records." }
        CatalogFields.validate(entity,fields)
        if(id==null)require(CatalogFields.fields(entity).filter { it.required }.all { it.name in fields }) { "Fill all required fields." }
        // Reference and custom-field validation uses confirmed Grocy metadata, not invented values.
        val refs=CatalogFields.fields(entity).filter { it.kind==CatalogKind.Reference && it.name in fields && fields[it.name]!=JsonNull }
        for(ref in refs) {
            val rows=cache.readFresh("/objects/${ref.reference}")!!.jsonArray
            require(rows.any { it.jsonObject.catalogId("id")==fields.catalogId(ref.name) }) { "Choose an existing ${ref.label.lowercase()}." }
        }
        if(userfields.isNotEmpty() || id==null) {
            val definitions=cache.readFresh("/objects/userfields")!!.jsonArray.map { it.jsonObject }.filter { it.catalogText("entity")==entity.entity }
            for((name,value)in userfields) {
                val field=definitions.singleOrNull { it.catalogText("name")==name } ?: error("Custom-field definition changed. Refresh first.")
                CustomFields.validate(field,value.jsonPrimitive.content)
            }
            if(id==null)for(field in definitions.filter { it.catalogText("input_required")=="1" }) {
                require(CustomFields.supported(field)) { "Cannot create: required custom field '${field.catalogText("caption")}' has unsupported type '${field.catalogText("type")}'." }
                CustomFields.validate(field,userfields[field.catalogText("name")]?.jsonPrimitive?.content ?: field.catalogText("default_value"))
            }
        }
        val operation=UUID.randomUUID().toString()
        if(userfields.isNotEmpty())db.put("catalog-custom",operation,buildJsonObject { put("entity",entity.entity);id?.let { put("id",it) };put("values",userfields) }.toString())
        return queue(if(id==null)"POST" else "PUT","/objects/${entity.entity}"+(id?.let { "/$it" } ?: ""),fields,"/objects/${entity.entity}",operation)
    }
    override suspend fun delete(entity:CatalogEntity,id:Long):String {
        check(entity.writable(grants)) { "Master-data editing denied." };require(id>0 && entity.entity in exposed())
        return queue("DELETE","/objects/${entity.entity}/$id",JsonObject(emptyMap()),"/objects/${entity.entity}")
    }
    override suspend fun charge(id:Long):String {
        check(HouseholdAccess.has(grants,"BATTERIES") && HouseholdAccess.has(grants,"BATTERIES_TRACK_CHARGE_CYCLE")) { "Battery charge access denied." };require(id>0)
        return queue("POST","/batteries/$id/charge",JsonObject(emptyMap()),"/batteries/$id")
    }
    override suspend fun undoCycle(id:Long):String {
        check(HouseholdAccess.has(grants,"BATTERIES") && HouseholdAccess.has(grants,"BATTERIES_UNDO_CHARGE_CYCLE")) { "Battery undo access denied." };require(id>0)
        return queue("POST","/batteries/charge-cycles/$id/undo",JsonObject(emptyMap()),"/objects/battery_charge_cycles")
    }
    override suspend fun history(id:Long):List<JsonObject> {
        check(HouseholdAccess.has(grants,"BATTERIES"));require(id>0)
        return Json.parseToJsonElement(cache.read("/objects/battery_charge_cycles").payload).jsonArray.map { it.jsonObject }.filter { it.catalogId("battery_id")==id }.sortedByDescending { it.catalogText("tracked_time") }
    }
    override suspend fun sync() {
        if(CatalogEntity.entries.none { it.readable(grants) })return
        cache.drain()
        for(op in db.operations().filter { it.state=="confirmed" && it.method in setOf("POST","PUT") && it.path.startsWith("/objects/") }) {
            val draft=db.get("catalog-custom",op.clientOperationId)?.let { Json.parseToJsonElement(it).jsonObject } ?: continue
            val id=draft.catalogId("id") ?: op.responsePayload?.let { Json.parseToJsonElement(it).jsonObject.catalogId("created_object_id") } ?: continue
            val followUp=UUID.nameUUIDFromBytes((op.clientOperationId+":custom").toByteArray()).toString()
            cache.enqueue("PUT","/userfields/${draft.catalogText("entity")}/$id",draft["values"]!!.toString(),"/objects/${draft.catalogText("entity")}/$id",operationId=followUp)
        }
        cache.drain()
    }
    override suspend fun operations():List<PendingChange> = db.operations().filter { row->
        row.path.startsWith("/batteries/") || row.path.startsWith("/userfields/") || CatalogEntity.entries.any { row.path=="/objects/${it.entity}" || row.path.startsWith("/objects/${it.entity}/") }
    }.map { PendingChange(it.clientOperationId,it.method,it.path,it.state,it.detail,false) }
}
