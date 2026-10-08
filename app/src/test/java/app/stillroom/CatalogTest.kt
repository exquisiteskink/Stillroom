package app.stillroom

import app.stillroom.domain.*
import kotlinx.serialization.json.*
import java.time.*
import org.junit.Test
import org.junit.Assert.*

class CatalogTest {
    @Test fun childCannotReadMasterDataAndUnknownCustomTypesStayUntouched() {
        assertFalse(CatalogEntity.Products.readable(setOf("CHORES")))
        assertFalse(CatalogEntity.Batteries.writable(setOf("BATTERIES")))
        assertTrue(CatalogEntity.Units.writable(setOf("MASTER_DATA_EDIT")))
        assertTrue(CatalogEntity.Equipment.readable(setOf("EQUIPMENT")))
        val field=Json.parseToJsonElement("""{"name":"mystery","caption":"Mystery","type":"future-widget"}""").jsonObject
        assertTrue(CustomFields.reason(field)!!.contains("future-widget"))
        assertFalse(CustomFields.supported(field))
        assertTrue(CustomFields.supported(buildJsonObject { put("type","numeric") }))
    }
    @Test fun cachedTodayHonorsAssignmentPermissionsAndServerDueDate() {
        val address=ServerAddress.parse("example.org");val child=Account(AccountId.of(address,4),address,4,"child","4.7.1",setOf("CHORES"))
        val data=mapOf("/chores" to """[{"chore_id":1,"chore_name":"Mine","next_execution_assigned_to_user_id":4,"next_estimated_execution_time":"2026-10-07 12:00:00"},{"chore_id":2,"chore_name":"Other","next_execution_assigned_to_user_id":5,"next_estimated_execution_time":"2026-10-07 12:00:00"},{"chore_id":3,"chore_name":"Later","next_execution_assigned_to_user_id":4,"next_estimated_execution_time":"2026-10-08 00:00:00"}]""", "/stock/volatile" to """{"missing_products":[{"product_id":10}]}""")
        val today=CachedToday.build(child,data,LocalDate.parse("2026-10-07"))
        assertEquals(listOf("Mine"),today.chores.map { it.catalogText("chore_name") })
        assertTrue(today.lowStock.isEmpty());assertTrue(today.shopping.isEmpty());assertFalse(today.scan)
        assertTrue(CachedToday.build(child.copy(permissions=null),data,LocalDate.parse("2026-10-07")).chores.isEmpty())
    }
    @Test fun quietHoursIncludeOvernightAndEqualMeansAllDay() {
        val overnight=QuietHours(LocalTime.of(22,0),LocalTime.of(7,0))
        assertTrue(overnight.contains(LocalTime.of(23,0)));assertTrue(overnight.contains(LocalTime.of(6,59)))
        assertFalse(overnight.contains(LocalTime.of(7,0)));assertFalse(overnight.contains(LocalTime.NOON))
        assertTrue(QuietHours(LocalTime.of(9,0),LocalTime.of(17,0)).contains(LocalTime.NOON))
        assertFalse(QuietHours(LocalTime.of(9,0),LocalTime.of(17,0)).contains(LocalTime.of(18,0)))
        assertTrue(QuietHours(LocalTime.NOON,LocalTime.NOON).contains(LocalTime.MIDNIGHT))
    }
    @Test fun typedMasterFieldsAndCustomValuesRejectInvalidData() {
        CatalogEntity.entries.forEach { entity->assertTrue(CatalogFields.fields(entity).isNotEmpty()) }
        CatalogFields.validate(CatalogEntity.Conversions,buildJsonObject { put("from_qu_id",1);put("to_qu_id",2);put("factor",Json.parseToJsonElement("2.5"));put("product_id",JsonNull) })
        assertTrue(runCatching { CatalogFields.validate(CatalogEntity.Conversions,buildJsonObject { put("factor",0) }) }.isFailure)
        assertTrue(runCatching { CatalogFields.validate(CatalogEntity.Conversions,buildJsonObject { put("from_qu_id",1);put("to_qu_id",1) }) }.isFailure)
        assertTrue(runCatching { CatalogFields.validate(CatalogEntity.Products,buildJsonObject { put("name","") }) }.isFailure)
        assertTrue(runCatching { CatalogFields.validate(CatalogEntity.Units,buildJsonObject { put("unrecognized",1) }) }.isFailure)
        CatalogFields.validate(CatalogEntity.Products,buildJsonObject { put("default_best_before_days",-1);put("active",1);put("min_stock_amount",Json.parseToJsonElement("0.25"));put("shopping_location_id",JsonNull) })
        fun field(type:String)=buildJsonObject { put("type",type);put("input_required",1);put("caption","Synthetic") }
        CustomFields.validate(field("numeric"),"1.25");CustomFields.validate(field("date"),"2026-10-07");CustomFields.validate(field("date-time"),"2026-10-07 12:00:00");CustomFields.validate(field("checkbox"),"1")
        assertTrue(runCatching { CustomFields.validate(field("numeric"),"") }.isFailure)
        assertTrue(runCatching { CustomFields.validate(field("numeric"),"not numeric") }.isFailure)
        assertNull(CustomFields.reason(field("text")))
    }
    @Test fun parentTodayUsesServerMealSectionsAndVolatileStock() {
        val address=ServerAddress.parse("example.org");val account=Account(AccountId.of(address,1),address,1,"parent","4.7.1",setOf("ADMIN"))
        val data=mapOf("/chores" to "[]","/objects/chores" to "[]","/objects/meal_plan" to """[{"day":"2026-10-07","type":"recipe","recipe_id":2,"section_id":3,"recipe_servings":1.5}]""","/objects/recipes" to """[{"id":2,"name":"Soup"}]""","/objects/meal_plan_sections" to """[{"id":3,"name":"Dinner"}]""","/stock/volatile" to """{"missing_products":[{"product_id":4}],"due_products":[{"product_id":5}],"expired_products":[{"product_id":5},{"product_id":6}]}""","/objects/shopping_lists" to "[]","/objects/shopping_list" to """[{"id":1,"done":1},{"id":2,"done":0}]""")
        val today=CachedToday.build(account,data,LocalDate.parse("2026-10-07"))
        assertEquals("Soup",today.meals.single().catalogText("recipe_name"));assertEquals("Dinner",today.meals.single().catalogText("section_name"))
        assertEquals(1,today.lowStock.size);assertEquals(2,today.expiring.size);assertEquals(1,today.shopping.size);assertTrue(today.scan);assertTrue(today.missing.isEmpty())
        assertTrue(CachedToday.build(account,emptyMap(),LocalDate.now()).missing.isNotEmpty())
    }
}
