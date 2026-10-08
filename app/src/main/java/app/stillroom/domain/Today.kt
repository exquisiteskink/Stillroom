package app.stillroom.domain

import kotlinx.serialization.json.*
import java.time.*

data class TodaySnapshot(val account:Account?=null,val chores:List<JsonObject> = emptyList(),val meals:List<JsonObject> = emptyList(),val lowStock:List<JsonObject> = emptyList(),val expiring:List<JsonObject> = emptyList(),val shopping:List<JsonObject> = emptyList(),val lists:List<JsonObject> = emptyList(),val scan:Boolean=false,val missing:List<String> = emptyList(),val accessDenied:Boolean=false)
object CachedToday {
    fun paths(account:Account):List<String> = buildList {
        val grants=account.permissions
        if(HouseholdAccess.has(grants,"CHORES"))addAll(listOf("/chores","/objects/chores"))
        if(RecipeAccess.mealPlan(grants))addAll(listOf("/objects/meal_plan","/objects/recipes","/objects/meal_plan_sections"))
        if(StockAccess.canRead(grants))add("/stock/volatile")
        if(ShoppingAccess.allowed(grants))addAll(listOf("/objects/shopping_list","/objects/shopping_lists"))
    }
    fun build(account:Account,data:Map<String,String>,today:LocalDate):TodaySnapshot {
        val paths=paths(account)
        fun rows(path:String):List<JsonObject> = if(path !in paths)emptyList() else data[path]?.let { runCatching { Json.parseToJsonElement(it).jsonArray.map { row->row.jsonObject } }.getOrNull() }.orEmpty()
        val masters=rows("/objects/chores").associateBy { it.catalogId("id") }
        val chores=rows("/chores").map { row->JsonObject(masters[row.catalogId("chore_id")].orEmpty()+row) }.filter { row->
            val date=runCatching { LocalDate.parse(row.catalogText("next_estimated_execution_time").take(10)) }.getOrNull()
            row.catalogText("active")!="0" && date!=null && date<=today && (HouseholdAccess.parent(account.permissions) || row.catalogId("next_execution_assigned_to_user_id")==account.userId)
        }.sortedBy { it.catalogText("next_estimated_execution_time") }
        val recipes=rows("/objects/recipes").associateBy { it.catalogId("id") };val sections=rows("/objects/meal_plan_sections").associateBy { it.catalogId("id") }
        val meals=rows("/objects/meal_plan").filter { it.catalogText("day")==today.toString() }.map { row->JsonObject(row+mapOf("recipe_name" to JsonPrimitive(recipes[row.catalogId("recipe_id")]?.catalogText("name").orEmpty()),"section_name" to JsonPrimitive(sections[row.catalogId("section_id")]?.catalogText("name").orEmpty()))) }
        val volatile=if("/stock/volatile" in paths)data["/stock/volatile"]?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } else null
        fun stock(key:String)=(volatile?.get(key) as? JsonArray)?.map { it.jsonObject }.orEmpty()
        return TodaySnapshot(account,chores,meals,stock("missing_products"),(stock("due_products")+stock("overdue_products")+stock("expired_products")).distinctBy { it.catalogText("product_id") },rows("/objects/shopping_list").filter { it.catalogText("done")!="1" },rows("/objects/shopping_lists"),StockAccess.canRead(account.permissions),paths.filter { it !in data })
    }
}
data class QuietHours(val start:LocalTime,val end:LocalTime) {
    fun contains(time:LocalTime)=when { start==end->true;start<end->time>=start && time<end;else->time>=start || time<end }
}

enum class KitchenMeal {
    Breakfast, Lunch, Dinner;
    companion object {
        fun at(time: LocalTime): KitchenMeal = when (time.hour) {
            in 5 until 11 -> Breakfast
            in 11 until 16 -> Lunch
            else -> Dinner
        }
    }
}
