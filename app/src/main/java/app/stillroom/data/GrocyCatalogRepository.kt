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
        val available=CatalogEntity.entries.filter { it.readable(grants) && cache.capabilities().allows("/objects/${it.entity}") }
        if(available.isEmpty())return CatalogSnapshot()
        val exposed=exposed();var stale=false;val resources=linkedMapOf<String,List<JsonObject>>();val unavailable=mutableListOf<String>()
        suspend fun read(path:String,entity:String) {
            val read=cache.read(path);stale=stale||read.stale;resources[entity]=Json.parseToJsonElement(read.payload).jsonArray.map { it.jsonObject }
        }
        for(entity in available) {
            if(entity.entity !in exposed)unavailable+="${entity.label}: this server does not expose these records." else read("/objects/${entity.entity}",entity.entity)
        }
        if("userfields" in exposed)read("/objects/userfields","userfields")
        // Product editor choices: groups for the product form, barcodes to catch duplicates before sending.
        if(CatalogEntity.Products.readable(grants) && cache.capabilities().enabled("STOCK")) {
            if("product_groups" in exposed)read("/objects/product_groups","product_groups")
            if("product_barcodes" in exposed)read("/objects/product_barcodes","product_barcodes")
        }
        if(cache.capabilities().enabled("BATTERIES") && HouseholdAccess.has(grants,"BATTERIES") && "batteries" in exposed)read("/batteries","battery_status")
        return CatalogSnapshot(resources,stale,unavailable)
    }
    private suspend fun queue(method:String,path:String,payload:JsonObject,read:String,operationId:String?=null):String {
        check(db.operations().none { it.path==path && it.state in setOf("pending","in-flight","needs-review","guarded") }) { "An operation for this record is awaiting confirmation. Inspect Pending changes." }
        return cache.enqueue(method,path,payload.toString(),read,operationId=operationId)
    }
    override suspend fun save(entity:CatalogEntity,id:Long?,fields:JsonObject,userfields:JsonObject,extras:ProductExtras):String {
        check(entity.writable(grants)) { "Master-data editing denied." };require(id==null || id>0)
        require(entity.entity in exposed()) { "This server does not expose these records." }
        require(extras.isEmpty || entity==CatalogEntity.Products) { "Conversion factors and barcodes belong to products." }
        // A fresh read proves Grocy is reachable now. Offline this throws and nothing is queued, so an
        // edit is never left as an unknown-outcome request; the caller keeps the form open.
        val current=cache.readFresh("/objects/${entity.entity}"+(id?.let { "/$it" } ?: "")) ?: error("This record no longer exists in Grocy.")
        require(fields.isNotEmpty() || userfields.isNotEmpty() || !extras.isEmpty) { "Nothing changed." }
        require(id!=null || fields.isNotEmpty()) { "Fill all required fields." }
        if(fields.isNotEmpty())CatalogFields.validate(entity,fields)
        if(id==null)require(CatalogFields.fields(entity).filter { it.required }.all { it.name in fields }) { "Fill all required fields." }
        // Reference and custom-field validation uses confirmed Grocy metadata, not invented values.
        val refs=CatalogFields.fields(entity).filter { it.kind==CatalogKind.Reference && it.name in fields && fields[it.name]!=JsonNull }
        for(ref in refs) {
            val rows=cache.readFresh("/objects/${ref.reference}")!!.jsonArray
            require(rows.any { it.jsonObject.catalogId("id")==fields.catalogId(ref.name) }) { "Choose an existing ${ref.label.lowercase()}." }
        }
        if(userfields.isNotEmpty() || id==null) {
            val definitions=UserfieldDefinition.parse(cache.readFresh("/objects/userfields")!!.jsonArray.map { it.jsonObject },entity.entity)
            for((name,value)in userfields) {
                val field=definitions.singleOrNull { it.name==name } ?: error("Custom-field definition changed. Refresh first.")
                require(UserfieldTypes.editable(field.type)) { UserfieldTypes.reason(field)!! }
                UserfieldValues.normalize(field,value.jsonPrimitive.content)
            }
            if(id==null)for(field in definitions.filter { it.inputRequired }) {
                require(UserfieldTypes.editable(field.type)) { "Cannot create: required custom field '${field.label}' must be filled in Grocy's web app (${UserfieldText.typeLabel(field.type)})." }
                UserfieldValues.normalize(field,userfields[field.name]?.jsonPrimitive?.content ?: field.defaultValue)
            }
        }
        extras.purchaseToStockFactor?.let { require(it.signum()>0) { "Enter a conversion factor above 0." } }
        require(extras.newBarcodes.all { it.isNotBlank() && it.length<=200 }) { "Enter valid barcodes." }
        val row=if(id!=null)(current as? JsonObject) else null
        val purchase=fields.catalogId("qu_id_purchase") ?: row?.catalogId("qu_id_purchase")
        val stock=fields.catalogId("qu_id_stock") ?: row?.catalogId("qu_id_stock")
        val operation=UUID.randomUUID().toString()
        val draft=buildJsonObject {
            put("entity",entity.entity);id?.let { put("id",it) };put("values",userfields)
            if(extras.purchaseToStockFactor!=null && purchase!=null && stock!=null && purchase!=stock)put("conversion",buildJsonObject { put("from_qu_id",purchase);put("to_qu_id",stock);put("factor",Json.parseToJsonElement(extras.purchaseToStockFactor.toPlainString())) })
            if(extras.newBarcodes.isNotEmpty())put("barcodes",JsonArray(extras.newBarcodes.map(::JsonPrimitive)))
        }
        val hasFollowUps=userfields.isNotEmpty() || draft.containsKey("conversion") || draft.containsKey("barcodes")
        if(hasFollowUps)db.put("catalog-custom",operation,draft.toString())
        if(fields.isEmpty()) {
            // Only follow-up writes changed (e.g. custom fields): send them directly for the known record.
            followUps(draft,id!!,operation)
            return operation
        }
        return queue(if(id==null)"POST" else "PUT","/objects/${entity.entity}"+(id?.let { "/$it" } ?: ""),fields,"/objects/${entity.entity}",operation)
    }

    /**
     * Writes that need the record id: userfields, then a product's purchase → stock conversion,
     * then new barcodes. Each has a deterministic id and is queued once, so a later sync never
     * repeats one; a failed follow-up stays failed and visible instead of being retried silently.
     */
    private suspend fun followUps(draft:JsonObject,id:Long,base:String) {
        val entity=draft.catalogText("entity")
        fun once(suffix:String)=CatalogFollowUps.id(base,suffix).takeIf { db.operation(it)==null }
        val values=draft["values"] as? JsonObject
        if(values!=null && values.isNotEmpty())once(CatalogFollowUps.CUSTOM)?.let { cache.enqueue("PUT","/userfields/$entity/$id",values.toString(),"/objects/$entity/$id",operationId=it) }
        (draft["conversion"] as? JsonObject)?.let { conversion->
            once(CatalogFollowUps.CONVERSION)?.let { opId->
                // Grocy's insert trigger may have created a 1:1 product conversion; update it rather than duplicate it.
                val rows=runCatching { cache.readFresh("/objects/quantity_unit_conversions")?.jsonArray?.map { it.jsonObject } }.getOrNull()
                if(rows!=null) {
                    val existing=rows.firstOrNull { it.catalogId("product_id")==id && it.catalogId("from_qu_id")==conversion.catalogId("from_qu_id") && it.catalogId("to_qu_id")==conversion.catalogId("to_qu_id") }
                    val factor=conversion["factor"]!!
                    if(existing==null)cache.enqueue("POST","/objects/quantity_unit_conversions",buildJsonObject { put("product_id",id);put("from_qu_id",conversion["from_qu_id"]!!);put("to_qu_id",conversion["to_qu_id"]!!);put("factor",factor) }.toString(),"/objects/quantity_unit_conversions",operationId=opId)
                    else if(existing.catalogText("factor").toBigDecimalOrNull()?.compareTo(factor.jsonPrimitive.content.toBigDecimal())!=0)
                        cache.enqueue("PUT","/objects/quantity_unit_conversions/${existing.catalogId("id")}",buildJsonObject { put("factor",factor) }.toString(),"/objects/quantity_unit_conversions",operationId=opId)
                    else db.put("catalog-followup-skip",opId,"same")
                }
            }
        }
        (draft["barcodes"] as? JsonArray)?.forEachIndexed { index,barcode->
            once(CatalogFollowUps.barcode(index))?.let { cache.enqueue("POST","/objects/product_barcodes",buildJsonObject { put("product_id",id);put("barcode",barcode) }.toString(),"/objects/product_barcodes",operationId=it) }
        }
    }

    override suspend fun outcome(operationId:String):CatalogSaveOutcome {
        val rows=db.operations().associateBy { it.clientOperationId }
        fun view(id:String)=rows[id]?.let { OutboxView(it.state,it.detail,it.responsePayload) }
        val draft=db.get("catalog-custom",operationId)?.let { Json.parseToJsonElement(it).jsonObject }
        val followUps=buildList {
            if((draft?.get("values") as? JsonObject)?.isNotEmpty()==true)add("Custom fields" to view(CatalogFollowUps.id(operationId,CatalogFollowUps.CUSTOM)))
            if(draft?.containsKey("conversion")==true) {
                val id=CatalogFollowUps.id(operationId,CatalogFollowUps.CONVERSION)
                add("Conversion factor" to (view(id) ?: if(db.get("catalog-followup-skip",id)!=null)OutboxView("confirmed") else null))
            }
            (draft?.get("barcodes") as? JsonArray)?.forEachIndexed { index,barcode->add("Barcode ${barcode.jsonPrimitive.content}" to view(CatalogFollowUps.id(operationId,CatalogFollowUps.barcode(index)))) }
        }
        return CatalogOutcomes.evaluate(view(operationId),draft?.catalogId("id"),followUps)
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
            followUps(draft,id,op.clientOperationId)
        }
        cache.drain()
    }
    override suspend fun operations():List<PendingChange> = db.operations().filter { row->
        row.path.startsWith("/batteries/") || row.path.startsWith("/userfields/") || row.path.startsWith("/objects/product_barcodes") || CatalogEntity.entries.any { row.path=="/objects/${it.entity}" || row.path.startsWith("/objects/${it.entity}/") }
    }.map { PendingChange(it.clientOperationId,it.method,it.path,it.state,it.detail,false) }
}
