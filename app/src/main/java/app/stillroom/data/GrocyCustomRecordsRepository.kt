package app.stillroom.data

import app.stillroom.domain.*
import kotlinx.serialization.json.*
import java.util.UUID

class GrocyCustomRecordsRepository(private val grants:Set<String>?,private val db:AccountDatabase,private val cache:CachedGrocyRepository,private val files:GrocyFiles):CustomRecordsRepository {
    private fun access() {
        check(HouseholdAccess.has(grants,"MASTER_DATA_EDIT")) { "Custom record access requires master-data editing permission." }
        val entities=cache.capabilities().entities
        check(entities.isEmpty() || entities.containsAll(listOf("userentities","userobjects","userfields"))) { "This Grocy server does not expose custom records." }
    }
    override suspend fun snapshot(entityId:Long?,offset:Int):CustomRecordsSnapshot {
        access()
        var stale=false
        suspend fun rows(entity:String):List<JsonObject> {
            val read=cache.read("/objects/$entity");stale=stale || read.stale
            return Json.parseToJsonElement(read.payload).jsonArray.map { it.jsonObject }
        }
        val entities=rows("userentities").filter { (it.catalogId("id") ?: 0)>0 }
        val ids=entities.mapNotNull { it.catalogId("id") }.toSet()
        val objects=rows("userobjects").filter { it.catalogId("userentity_id") in ids && (it.catalogId("id") ?: 0)>0 }
        val fields=rows("userfields")
        require(offset>=0)
        val selected=entities.firstOrNull { it.catalogId("id")==entityId } ?: entities.firstOrNull()
        val selectedId=selected?.catalogId("id")
        val all=objects.filter { it.catalogId("userentity_id")==selectedId }
        val page=all.drop(offset).take(50).map { row->
            val name=selected?.catalogText("name").orEmpty()
            if(!name.matches(Regex("[\\p{L}\\p{N}_ -]{1,200}")))row else {
                val read=cache.read("/userfields/userentity-$name/${row.catalogId("id")}");stale=stale || read.stale
                JsonObject(row+("userfields" to Json.parseToJsonElement(read.payload)))
            }
        }
        return CustomRecordsSnapshot(entities,page,fields,stale,selectedId,offset,all.size>offset+page.size)
    }
    private suspend fun entity(current:JsonObject):JsonObject {
        access()
        val id=current.catalogId("id") ?: error("Choose a custom entity.")
        require(id>0)
        val fresh=cache.readFresh("/objects/userentities/$id")?.jsonObject ?: error("This custom entity no longer exists.")
        require(fresh.catalogText("name")==current.catalogText("name")) { "The custom entity changed. Refresh first." }
        require(fresh.catalogText("name").matches(Regex("[\\p{L}\\p{N}_ -]{1,200}"))) { "Open this entity in Grocy's web app." }
        return fresh
    }
    private fun fieldEntity(row:JsonObject)="userentity-"+row.catalogText("name")
    override suspend fun values(entity:JsonObject,id:Long):JsonObject {
        val fresh=entity(entity);require(id>0)
        val row=cache.readFresh("/objects/userobjects/$id")?.jsonObject ?: error("This record no longer exists.")
        require(row.catalogId("userentity_id")==fresh.catalogId("id")) { "Record belongs to a different custom entity." }
        return cache.readFresh("/userfields/${fieldEntity(fresh)}/$id")!!.jsonObject
    }
    override suspend fun save(entity:JsonObject,id:Long?,values:JsonObject,baseline:JsonObject):String {
        val fresh=entity(entity)
        val name=fieldEntity(fresh)
        val definitions=UserfieldDefinition.parse(cache.readFresh("/objects/userfields")!!.jsonArray.map { it.jsonObject },name)
        val normalized=buildJsonObject {
            for((key,value) in values) {
                val field=definitions.singleOrNull { it.name==key } ?: error("Custom fields changed. Refresh first.")
                val text=(value as? JsonPrimitive)?.contentOrNull ?: error("Enter a field value.")
                if(field.canonicalType in setOf(UserfieldTypes.FILE,UserfieldTypes.IMAGE)) {
                    require(!field.inputRequired || text.isNotBlank()) { "Fill ${field.label}." }
                    require(text.isBlank() || db.get("uploaded-userfiles",text)=="confirmed") { "Upload the file before saving this field." }
                    put(key,text)
                } else {
                    require(UserfieldTypes.editable(field.type)) { "This field must be edited in Grocy." }
                    put(key,UserfieldValues.normalize(field,text))
                }
            }
        }
        if(id==null)for(field in definitions.filter { it.inputRequired }) {
            val value=normalized[field.name]?.jsonPrimitive?.contentOrNull ?: field.defaultValue
            require(value.isNotBlank()) { "Fill ${field.label}." }
            if(field.canonicalType !in setOf(UserfieldTypes.FILE,UserfieldTypes.IMAGE)) {
                require(UserfieldTypes.editable(field.type)) { "Fill the required field '${field.label}' in Grocy's web app." }
                UserfieldValues.normalize(field,value)
            }
        }
        if(id!=null) {
            val latest=values(fresh,id)
            require(normalized.keys.all { latest[it]==baseline[it] }) { "Someone changed this record. Reload it before saving." }
        }
        val path=if(id==null)"/objects/userobjects" else "/userfields/$name/$id"
        check(db.operations().none { it.path==path && it.state in setOf("pending","guarded","in-flight","needs-review") }) { "A save is awaiting confirmation. Check Pending changes." }
        val operation=UUID.randomUUID().toString()
        if(id==null) {
            db.put("custom-record-drafts",operation,buildJsonObject { put("entity",name);put("values",normalized) }.toString())
            return cache.enqueue("POST",path,buildJsonObject { put("userentity_id",fresh.catalogId("id")!!) }.toString(),"/objects/userobjects",operationId=operation)
        }
        require(normalized.isNotEmpty()) { "Nothing changed." }
        return cache.enqueue("PUT",path,normalized.toString(),path,operationId=operation)
    }
    private fun followup(id:String)=UUID.nameUUIDFromBytes((id+":custom-fields").toByteArray()).toString()
    override suspend fun sync() {
        access();cache.drain()
        for(op in db.operations().filter { it.state=="confirmed" && it.path=="/objects/userobjects" && it.method=="POST" }) {
            val draft=db.get("custom-record-drafts",op.clientOperationId)?.let { Json.parseToJsonElement(it).jsonObject } ?: continue
            val id=op.responsePayload?.let { Json.parseToJsonElement(it).jsonObject.catalogId("created_object_id") } ?: continue
            val path="/userfields/${draft.catalogText("entity")}/$id"
            cache.enqueue("PUT",path,draft["values"]!!.toString(),path,operationId=followup(op.clientOperationId))
        }
        cache.drain()
    }
    override suspend fun confirmed(operation:String):Boolean = db.operation(operation)?.state=="confirmed" &&
        (db.get("custom-record-drafts",operation)==null || db.operation(followup(operation))?.state=="confirmed")
    override suspend fun delete(entity:JsonObject,id:Long):String {
        values(entity,id)
        check(db.operations().none { it.path=="/objects/userobjects/$id" && it.state in setOf("pending","guarded","in-flight","needs-review") }) { "A change for this record is awaiting confirmation." }
        return cache.enqueue("DELETE","/objects/userobjects/$id","{}","/objects/userobjects")
    }
    override suspend fun file(group:String,name:String):ByteArray { access();return files.read(group,name) }
    override suspend fun upload(bytes:ByteArray,extension:String,displayName:String?):String {
        access();require(extension.matches(Regex("[a-zA-Z0-9]{1,10}"))) { "Choose a file with a valid extension." }
        val name="stillroom-${UUID.randomUUID()}.$extension"
        db.put("uploaded-userfiles",name,"needs-review")
        files.upload(name,bytes)
        val reference=UserfileReference(name,displayName?.takeIf { it.isNotBlank() && it.length<=200 && it.none { c->c.isISOControl() || c=='/' || c=='\\' } } ?: name).stored()
        db.put("uploaded-userfiles",reference,"confirmed")
        return reference
    }
}
