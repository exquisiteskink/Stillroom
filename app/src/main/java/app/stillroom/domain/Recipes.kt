package app.stillroom.domain

import app.stillroom.fractions.QuantityFormatter
import app.stillroom.fractions.QuantityFractions
import java.math.BigDecimal
import java.util.Locale
import kotlinx.serialization.json.*

object RecipeAccess {
    fun allowed(grants:Set<String>?)=HouseholdAccess.has(grants,"RECIPES")
    fun mealPlan(grants:Set<String>?)=allowed(grants) && HouseholdAccess.has(grants,"RECIPES_MEALPLAN")
}
fun JsonObject.recipeText(key:String)=(get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
fun JsonObject.recipeId(key:String)=recipeText(key).toLongOrNull()
fun JsonObject.recipeDecimal(key:String)=recipeText(key).toBigDecimalOrNull()

/** Formatting may approximate. A clean editor always saves the original server decimal. */
class RecipeAmountInput(val original:BigDecimal,private val locale:Locale,formatter:QuantityFormatter=QuantityFormatter()) {
    var text=formatter.format(original,locale);private set
    private var dirty=false
    fun change(value:String) { text=value;dirty=true }
    fun saved():BigDecimal=if(!dirty) original else QuantityFractions().parse(text,locale)?.value ?: error("Enter a quantity.")
}
data class RecipeRequirement(val product:Long,val unit:Long,val required:BigDecimal,val available:BigDecimal) {
    val missing:BigDecimal get()=required.subtract(available).max(BigDecimal.ZERO)
    val canConsume:Boolean get()=required.signum()>0 && available>=required
}
data class RecipeSnapshot(val resources:Map<String,List<JsonObject>> = emptyMap(),val stale:Boolean=false) {
    fun rows(entity:String)=resources[entity].orEmpty()
    fun normal()=rows("recipes").filter { it.recipeId("id")?.let { id->id>0 }==true && it.recipeText("type")=="normal" }
    fun name(entity:String,id:Long?)=rows(entity).find { it.recipeId("id")==id }?.recipeText("name").orEmpty()
    fun factor(product:Long,unit:Long):BigDecimal? {
        val stock=rows("products").find { it.recipeId("id")==product }?.recipeId("qu_id_stock") ?: return null
        if(stock==unit)return BigDecimal.ONE
        return rows("quantity_unit_conversions_resolved").find { it.recipeId("product_id")==product && it.recipeId("from_qu_id")==unit && it.recipeId("to_qu_id")==stock }?.recipeDecimal("factor")?.takeIf { it.signum()>0 }
    }
    fun missingAmounts(recipe:Long):Map<Long,BigDecimal> {
        val amounts=linkedMapOf<Long,BigDecimal>()
        rows("recipes_pos_resolved").filter { it.recipeId("recipe_id")==recipe }.forEach { row->
            val original=rows("recipes_pos").find { it.recipeId("id")==row.recipeId("recipe_pos_id") }
            if(original?.recipeText("not_check_stock_fulfillment")!="1") {
                val p=row.recipeId("product_id_effective") ?: row.recipeId("product_id") ?: error("Unmapped ingredient.")
                val unit=row.recipeId("qu_id") ?: error("Missing unit.")
                val amount=row.recipeDecimal("recipe_amount") ?: error("Grocy fulfillment is incomplete.")
                val converted=if(row.recipeText("only_check_single_unit_in_stock")=="1")BigDecimal.ONE else amount.multiply(factor(p,unit) ?: error("Grocy has no stock-unit conversion."))
                amounts[p]=(amounts[p] ?: BigDecimal.ZERO)+converted
            }
        }
        return amounts.mapValues { (p,amount)->
            val available=rows("stock").filter { it.recipeId("product_id")==p }.sumOf { it.recipeDecimal("amount") ?: BigDecimal.ZERO }
            amount.subtract(available).max(BigDecimal.ZERO)
        }.filterValues { it.signum()>0 }
    }
    fun requirements(recipe:Long):List<RecipeRequirement> {
        val amounts=linkedMapOf<Long,BigDecimal>()
        rows("recipes_pos_resolved").filter { it.recipeId("recipe_id")==recipe }.forEach { row->
            check(row.recipeText("recipe_variable_amount").isBlank()) { "Variable ingredient amounts require review in Grocy before consumption." }
            val p=row.recipeId("product_id_effective") ?: row.recipeId("product_id") ?: error("Unmapped ingredient.")
            val q=row.recipeId("qu_id") ?: error("Ingredient unit is missing.")
            val amount=row.recipeDecimal("recipe_amount") ?: error("Ingredient quantity is missing.")
            val converted=amount.multiply(factor(p,q) ?: error("Grocy has no conversion to the stock unit."))
            if(converted.signum()>0) amounts[p]=(amounts[p] ?: BigDecimal.ZERO)+converted
        }
        return amounts.map { (p,amount)->
            val unit=rows("products").single { it.recipeId("id")==p }.recipeId("qu_id_stock")!!
            val available=rows("stock").filter { it.recipeId("product_id")==p }.sumOf { it.recipeDecimal("amount") ?: BigDecimal.ZERO }
            RecipeRequirement(p,unit,amount,available)
        }
    }
}
data class RecipeConsumeReview(val id:String,val recipe:Long,val servings:BigDecimal,val lines:List<RecipeRequirement>,val signature:String)
data class RecipeImport(val source:String,val name:String,val instructions:String,val ingredients:List<String>,val servings:BigDecimal?)
interface RecipeRepository {
    suspend fun snapshot():RecipeSnapshot
    suspend fun save(entity:String,id:Long?,fields:JsonObject):String
    suspend fun delete(entity:String,id:Long):String
    suspend fun sync()
    suspend fun operations():List<PendingChange>
    suspend fun review(recipe:Long):RecipeConsumeReview
    suspend fun consume(review:RecipeConsumeReview):List<String>
    suspend fun missing(recipe:Long,list:Long):List<String>
    suspend fun createImport(fields:JsonObject,ingredients:List<JsonObject>):String
    suspend fun importUrl(source:String):RecipeImport
    suspend fun image(name:String,bytes:ByteArray?=null):ByteArray
}
class ManageRecipes(private val repository:RecipeRepository) {
    suspend fun snapshot()=repository.snapshot()
    suspend fun save(entity:String,id:Long?,fields:JsonObject)=repository.save(entity,id,fields)
    suspend fun delete(entity:String,id:Long)=repository.delete(entity,id)
    suspend fun sync()=repository.sync()
    suspend fun operations()=repository.operations()
    suspend fun review(recipe:Long)=repository.review(recipe)
    suspend fun consume(review:RecipeConsumeReview)=repository.consume(review)
    suspend fun missing(recipe:Long,list:Long)=repository.missing(recipe,list)
    suspend fun createImport(fields:JsonObject,ingredients:List<JsonObject>)=repository.createImport(fields,ingredients)
    suspend fun importUrl(source:String)=repository.importUrl(source)
    suspend fun image(name:String,bytes:ByteArray?=null)=repository.image(name,bytes)
}
