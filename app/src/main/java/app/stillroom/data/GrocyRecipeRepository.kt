package app.stillroom.data

import app.stillroom.domain.*
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.util.UUID

class GrocyRecipeRepository(private val grants:Set<String>?,private val db:AccountDatabase,
    private val cache:CachedGrocyRepository,private val stock:ManageStock,private val shopping:ManageShopping,
    private val publicImport:suspend(String)->RecipeImport=RecipeUrlReader()::read,
    private val files:suspend(String,ByteArray?)->ByteArray={_,_->error("Image transport unavailable.")}) : RecipeRepository {
    private fun access()=check(RecipeAccess.allowed(grants)) { "Recipes access denied." }
    private suspend fun snapshot(fresh:Boolean):RecipeSnapshot {
        access();var stale=false;val resources=linkedMapOf<String,List<JsonObject>>()
        val entities=mutableListOf("recipes","recipes_pos","products","quantity_units","quantity_unit_conversions_resolved")
        if(StockAccess.canRead(grants)) entities.add("recipes_pos_resolved")
        if(RecipeAccess.mealPlan(grants)) entities.addAll(listOf("meal_plan","meal_plan_sections"))
        if(ShoppingAccess.allowed(grants)) entities.addAll(listOf("shopping_lists","shopping_list"))
        for(e in entities) {
            val path="/objects/$e"
            val value=if(fresh)cache.readFresh(path)!! else cache.read(path).let { stale=stale||it.stale;Json.parseToJsonElement(it.payload) }
            resources[e]=value.jsonArray.map { it.jsonObject }
        }
        if(StockAccess.canRead(grants)) {
            val value=if(fresh)cache.readFresh("/stock")!! else cache.read("/stock").let { stale=stale||it.stale;Json.parseToJsonElement(it.payload) }
            resources["stock"]=value.jsonArray.map { it.jsonObject }
        }
        return RecipeSnapshot(resources,stale)
    }
    override suspend fun snapshot()=snapshot(false)
    private suspend fun queue(method:String,path:String,fields:JsonObject,read:String):String {
        check(db.operations().none { it.path==path && it.state in setOf("pending","in-flight","guarded","needs-review") }) { "This record has an operation awaiting confirmation. Review Pending changes." }
        return cache.enqueue(method,path,fields.toString(),read)
    }
    override suspend fun save(entity:String,id:Long?,fields:JsonObject):String {
        access();require(id==null || id>0)
        val keys=when(entity) {
            "recipes"->setOf("name","description","base_servings","desired_servings","picture_file_name","not_check_shoppinglist")
            "recipes_pos"->setOf("recipe_id","product_id","amount","qu_id","note","ingredient_group","only_check_single_unit_in_stock","not_check_stock_fulfillment","variable_amount","price_factor","round_up")
            "meal_plan"->{ check(RecipeAccess.mealPlan(grants));setOf("day","type","recipe_id","recipe_servings","note","section_id","product_id","product_amount","product_qu_id","done") }
            else->error("Unsupported recipe record.")
        }
        require(fields.isNotEmpty() && fields.keys.all { it in keys })
        when(entity) {
            "recipes"->{ fields["name"]?.let { require(it.jsonPrimitive.content.isNotBlank() && it.jsonPrimitive.content.length<=200) };for(k in listOf("base_servings","desired_servings"))fields[k]?.let { require(it.jsonPrimitive.content.toBigDecimal().signum()>0) } }
            "recipes_pos"->{
                val s=snapshot(true);val product=fields.recipeId("product_id") ?: error("Choose an existing product.")
                require(s.normal().any { it.recipeId("id")==fields.recipeId("recipe_id") })
                require(s.factor(product,fields.recipeId("qu_id") ?: error("Choose an existing unit."))!=null)
                require(fields.recipeDecimal("amount")?.signum()?.let { it>=0 }==true)
            }
            "meal_plan"->{
                java.time.LocalDate.parse(fields.recipeText("day"));val s=snapshot(true)
                require(s.rows("meal_plan_sections").any { it.recipeId("id")==fields.recipeId("section_id") })
                require(fields.recipeText("type") in setOf("recipe","note","product"))
                if(fields.recipeText("type")=="recipe") {
                    require(s.normal().any { it.recipeId("id")==fields.recipeId("recipe_id") })
                    require(fields.recipeDecimal("recipe_servings")?.signum()==1)
                }
            }
        }
        return queue(if(id==null)"POST" else "PUT","/objects/$entity"+(id?.let { "/$it" } ?: ""),fields,"/objects/$entity")
    }
    override suspend fun delete(entity:String,id:Long):String {
        access();require(id>0 && entity in setOf("recipes","recipes_pos","meal_plan"))
        if(entity=="meal_plan")check(RecipeAccess.mealPlan(grants))
        return queue("DELETE","/objects/$entity/$id",JsonObject(emptyMap()),"/objects/$entity")
    }
    override suspend fun sync() {
        access();cache.drain()
        for(op in db.operations().filter { it.path=="/objects/recipes" && it.method=="POST" && it.state=="confirmed" }) {
            val draft=db.get("recipe-import",op.clientOperationId) ?: continue
            val recipe=op.responsePayload?.let { Json.parseToJsonElement(it).jsonObject.recipeId("created_object_id") } ?: continue
            Json.parseToJsonElement(draft).jsonArray.forEachIndexed { index,item->
                val fields=JsonObject(item.jsonObject+("recipe_id" to JsonPrimitive(recipe)))
                val id=UUID.nameUUIDFromBytes((op.clientOperationId+":ingredient:"+index).toByteArray()).toString()
                cache.enqueue("POST","/objects/recipes_pos",fields.toString(),"/objects/recipes_pos",operationId=id)
            }
        }
        cache.drain();if(ShoppingAccess.allowed(grants))shopping.sync()
    }
    override suspend fun createImport(fields:JsonObject,ingredients:List<JsonObject>):String {
        access();require(ingredients.size<=200)
        require(fields.keys.all { it in setOf("name","description","base_servings") })
        require(fields.recipeText("name").isNotBlank() && fields.recipeDecimal("base_servings")?.signum()==1)
        val s=snapshot(true)
        ingredients.forEach { row->
            require(row.keys.all { it in setOf("product_id","qu_id","amount","note") })
            val p=row.recipeId("product_id") ?: error("Map every ingredient to an existing product.")
            require(s.factor(p,row.recipeId("qu_id") ?: error("Select a unit."))!=null)
            require(row.recipeDecimal("amount")?.signum()==1)
        }
        val id=UUID.randomUUID().toString()
        db.put("recipe-import",id,JsonArray(ingredients).toString())
        return cache.enqueue("POST","/objects/recipes",fields.toString(),"/objects/recipes",operationId=id)
    }
    override suspend fun operations():List<PendingChange> { access();return db.operations().filter { it.path.startsWith("/objects/recipes") || it.path.startsWith("/objects/meal_plan") || db.get("recipe-consume",it.clientOperationId)!=null }.map { PendingChange(it.clientOperationId,it.method,it.path,it.state,it.detail,false) } }
    private fun signature(s:RecipeSnapshot,recipe:Long):String=buildJsonObject {
        put("recipe",s.normal().single { it.recipeId("id")==recipe })
        put("resolved",JsonArray(s.rows("recipes_pos_resolved").filter { it.recipeId("recipe_id")==recipe }))
        put("ingredients",JsonArray(s.rows("recipes_pos").filter { it.recipeId("recipe_id")==recipe }))
    }.toString()
    override suspend fun review(recipe:Long):RecipeConsumeReview {
        access();check(StockAccess.canRead(grants));val s=snapshot(true)
        val row=s.normal().single { it.recipeId("id")==recipe }
        val review=RecipeConsumeReview(UUID.randomUUID().toString(),recipe,row.recipeDecimal("desired_servings")!!,s.requirements(recipe),signature(s,recipe))
        require(review.lines.isNotEmpty()) { "Add ingredients first." }
        db.put("recipe-review",review.id,review.signature)
        return review
    }
    override suspend fun consume(review:RecipeConsumeReview):List<String> {
        access();check(StockAccess.canWrite(grants,StockAction.Consume));require(db.get("recipe-review",review.id)==review.signature)
        val ids=review.lines.map { UUID.nameUUIDFromBytes((review.id+":"+it.product).toByteArray()).toString() }
        if(ids.any { db.operation(it)!=null }) {
            check(ids.all { db.operation(it)!=null }) { "Part of this consumption was queued. Inspect pending changes before continuing." }
            return ids
        }
        val s=snapshot(true)
        check(signature(s,review.recipe)==review.signature) { "The recipe changed. Open a new consumption review." }
        val current=s.requirements(review.recipe)
        check(current.map { it.product to it.required }==review.lines.map { it.product to it.required } && current.all { it.canConsume }) { "Stock changed or ingredients are missing. Refresh the review." }
        current.forEachIndexed { index,line ->
            db.put("recipe-consume",ids[index],review.id)
            stock.book(StockBooking(StockAction.Consume,line.product,line.required,recipeId=review.recipe),ids[index])
        }
        return ids
    }
    override suspend fun missing(recipe:Long,list:Long):List<String> {
        access();check(ShoppingAccess.allowed(grants) && StockAccess.canRead(grants));val s=snapshot(true)
        require(s.rows("shopping_lists").any { it.recipeId("id")==list })
        check(shopping.changes().none { it.state in setOf("pending","preparing","dispatching","conflict","needs-review") }) { "Confirm or reconcile pending shopping changes first." }
        return s.missingAmounts(recipe).mapNotNull { (product,missing) ->
            val unit=s.rows("products").single { it.recipeId("id")==product }.recipeId("qu_id_stock")!!
            val already=s.rows("shopping_list").filter { it.recipeId("shopping_list_id")==list && it.recipeId("product_id")==product && it.recipeText("done")!="1" }.sumOf { row->
                // Grocy shopping amounts are already stock units; qu_id only controls display.
                row.recipeDecimal("amount") ?: BigDecimal.ZERO
            }
            val amount=missing.subtract(already)
            if(amount.signum()<=0)null else shopping.save(ShoppingDraft(list,product,"",amount,unit))
        }
    }
    override suspend fun importUrl(source:String):RecipeImport { access();return publicImport(source) }
    override suspend fun image(name:String,bytes:ByteArray?):ByteArray {
        access();require(name.matches(Regex("[A-Za-z0-9_. -]{1,180}")) && !name.contains(".."));require(bytes==null || bytes.size in 1..5_242_880)
        if(bytes!=null)return files(name,bytes)
        return try { files(name,null).also { db.put("recipe-images",name,java.util.Base64.getEncoder().encodeToString(it)) } }
        catch(e:kotlinx.coroutines.CancellationException) { throw e }
        catch(e:GrocyFailure) {
            cache.recordDenial(e.status)
            if(e.status in setOf(401,403))throw e
            cache.requireCacheAccess()
            db.get("recipe-images",name)?.let { java.util.Base64.getDecoder().decode(it) } ?: throw e
        }
        catch(e:Exception) { cache.requireCacheAccess(); db.get("recipe-images",name)?.let { java.util.Base64.getDecoder().decode(it) } ?: throw e }
    }
}
