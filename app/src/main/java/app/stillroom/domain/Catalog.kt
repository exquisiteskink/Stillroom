package app.stillroom.domain

import kotlinx.serialization.json.*
import java.math.BigDecimal

fun JsonObject.catalogText(key:String)=(get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
fun JsonObject.catalogId(key:String)=catalogText(key).toLongOrNull()
enum class CatalogEntity(val entity:String,val label:String,val permission:String) {
    Batteries("batteries","Batteries","BATTERIES"),Equipment("equipment","Equipment","EQUIPMENT"),
    Products("products","Products","STOCK"),Locations("locations","Locations","STOCK"),
    Stores("shopping_locations","Stores","SHOPPINGLIST"),Units("quantity_units","Units","STOCK"),
    Conversions("quantity_unit_conversions","Unit conversions","STOCK"),Categories("task_categories","Task categories","TASKS");
    fun readable(grants:Set<String>?)=HouseholdAccess.has(grants,permission) || HouseholdAccess.has(grants,"MASTER_DATA_EDIT")
    fun writable(grants:Set<String>?)=readable(grants) && HouseholdAccess.has(grants,"MASTER_DATA_EDIT")
}
enum class CatalogKind { Text,Whole,Decimal,Toggle,Reference }
data class CatalogField(val name:String,val label:String,val kind:CatalogKind=CatalogKind.Text,val reference:String?=null,val required:Boolean=false)
object CatalogFields {
    private val common=listOf(CatalogField("name","Name",required=true),CatalogField("description","Description"))
    fun fields(entity:CatalogEntity):List<CatalogField> = when(entity) {
        CatalogEntity.Batteries->common+listOf(CatalogField("used_in","Used in"),CatalogField("charge_interval_days","Charge interval (days; 0 means unscheduled)",CatalogKind.Whole),CatalogField("active","Active",CatalogKind.Toggle))
        CatalogEntity.Equipment->common
        CatalogEntity.Locations->common+listOf(CatalogField("is_freezer","Freezer",CatalogKind.Toggle),CatalogField("active","Active",CatalogKind.Toggle))
        CatalogEntity.Stores,CatalogEntity.Categories->common
        CatalogEntity.Units->common+listOf(CatalogField("name_plural","Plural name"),CatalogField("active","Active",CatalogKind.Toggle))
        CatalogEntity.Conversions->listOf(CatalogField("product_id","Product (empty means global)",CatalogKind.Reference,"products"),CatalogField("from_qu_id","From unit",CatalogKind.Reference,"quantity_units",true),CatalogField("to_qu_id","To unit",CatalogKind.Reference,"quantity_units",true),CatalogField("factor","Conversion factor",CatalogKind.Decimal,required=true))
        CatalogEntity.Products->common+listOf(
            CatalogField("location_id","Default location",CatalogKind.Reference,"locations",true),CatalogField("shopping_location_id","Default store",CatalogKind.Reference,"shopping_locations"),
            CatalogField("qu_id_stock","Stock unit",CatalogKind.Reference,"quantity_units",true),CatalogField("qu_id_purchase","Purchase unit",CatalogKind.Reference,"quantity_units",true),
            CatalogField("qu_id_consume","Consume unit",CatalogKind.Reference,"quantity_units",true),CatalogField("qu_id_price","Price unit",CatalogKind.Reference,"quantity_units",true),
            CatalogField("min_stock_amount","Minimum stock",CatalogKind.Decimal),CatalogField("default_best_before_days","Default best-before days",CatalogKind.Whole),
            CatalogField("default_best_before_days_after_open","Best-before days after opening",CatalogKind.Whole),CatalogField("quick_consume_amount","Quick consume amount",CatalogKind.Decimal),
            CatalogField("calories","Calories per stock unit",CatalogKind.Decimal),CatalogField("active","Active",CatalogKind.Toggle),CatalogField("disable_open","Disable opening",CatalogKind.Toggle),
            CatalogField("hide_on_stock_overview","Hide from stock overview",CatalogKind.Toggle),CatalogField("not_check_stock_fulfillment_for_recipes","Skip recipe stock checks",CatalogKind.Toggle))
    }
    fun validate(entity:CatalogEntity,fields:JsonObject) {
        val definitions=fields(entity).associateBy { it.name }
        require(fields.isNotEmpty() && fields.keys.all { it in definitions }) { "Unsupported master-data field." }
        for((name,value)in fields) {
            val f=definitions.getValue(name);val text=(value as? JsonPrimitive)?.contentOrNull.orEmpty()
            when(f.kind) {
                CatalogKind.Text->require(text.length<=50000 && (!f.required || text.isNotBlank()))
                CatalogKind.Whole->require(text.toLongOrNull()?.let { it>=0 || (name.startsWith("default_best_before_days") && it==-1L) }==true)
                CatalogKind.Decimal->require(text.toBigDecimalOrNull()?.let { if(name=="factor")it.signum()>0 else it.signum()>=0 }==true)
                CatalogKind.Toggle->require(text in setOf("0","1"))
                CatalogKind.Reference->require(if(value==JsonNull)!f.required else text.toLongOrNull()?.let { it>0 }==true)
            }
        }
        if(entity==CatalogEntity.Conversions && fields.containsKey("from_qu_id") && fields.containsKey("to_qu_id"))require(fields.catalogId("from_qu_id")!=fields.catalogId("to_qu_id")) { "Choose two different units." }
    }
}
object CustomFields {
    private val types=setOf("text","numeric","checkbox","date","date-time")
    fun supported(field:JsonObject)=field.catalogText("type") in types
    fun reason(field:JsonObject):String?=if(supported(field))null else "Skipped ${field.catalogText("caption").ifBlank { field.catalogText("name") }}: type '${field.catalogText("type")}' has no supported editor; its value is preserved."
    fun validate(field:JsonObject,value:String) {
        require(supported(field) && value.length<=50000)
        require(field.catalogText("input_required")!="1" || value.isNotBlank()) { "${field.catalogText("caption")} is required." }
        if(value.isBlank())return
        when(field.catalogText("type")) {
            "numeric"->require(value.toBigDecimalOrNull()!=null)
            "checkbox"->require(value in setOf("0","1"))
            "date"->java.time.LocalDate.parse(value)
            "date-time"->java.time.LocalDateTime.parse(value.replace(' ','T'))
        }
    }
}
data class CatalogSnapshot(val resources:Map<String,List<JsonObject>> = emptyMap(),val stale:Boolean=false,val unavailable:List<String> = emptyList()) {
    fun rows(entity:String)=resources[entity].orEmpty()
    fun definitions(entity:String)=rows("userfields").filter { it.catalogText("entity")==entity }
}
interface CatalogRepository {
    suspend fun snapshot():CatalogSnapshot
    suspend fun save(entity:CatalogEntity,id:Long?,fields:JsonObject,userfields:JsonObject=JsonObject(emptyMap())):String
    suspend fun delete(entity:CatalogEntity,id:Long):String
    suspend fun charge(id:Long):String
    suspend fun undoCycle(id:Long):String
    suspend fun history(id:Long):List<JsonObject>
    suspend fun sync()
    suspend fun operations():List<PendingChange>
}
class ManageCatalog(private val repository:CatalogRepository) {
    suspend fun snapshot()=repository.snapshot()
    suspend fun save(entity:CatalogEntity,id:Long?,fields:JsonObject,userfields:JsonObject=JsonObject(emptyMap()))=repository.save(entity,id,fields,userfields)
    suspend fun delete(entity:CatalogEntity,id:Long)=repository.delete(entity,id)
    suspend fun charge(id:Long)=repository.charge(id)
    suspend fun undoCycle(id:Long)=repository.undoCycle(id)
    suspend fun history(id:Long)=repository.history(id)
    suspend fun sync()=repository.sync()
    suspend fun operations()=repository.operations()
}
