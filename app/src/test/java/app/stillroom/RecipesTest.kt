package app.stillroom

import app.stillroom.domain.*
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.util.Locale
import org.junit.Test
import org.junit.Assert.*

class RecipesTest {
    @Test fun unchangedFractionInputRetainsStoredDecimal() {
        val exact=BigDecimal("0.3333333333333333")
        val edit=RecipeAmountInput(exact,Locale.US)
        assertEquals(exact,edit.saved())
        edit.change("1 1/2")
        assertEquals(BigDecimal("1.5"),edit.saved().stripTrailingZeros())
        assertEquals(exact,edit.original)
    }
    @Test fun resolvedUnitsAggregateWithoutChangingIngredientAmounts() {
        fun row(s:String)=Json.parseToJsonElement(s).jsonObject
        val s=RecipeSnapshot(mapOf(
            "products" to listOf(row("""{"id":2,"qu_id_stock":1}""")),
            "quantity_unit_conversions_resolved" to listOf(row("""{"product_id":2,"from_qu_id":3,"to_qu_id":1,"factor":2}""")),
            "stock" to listOf(row("""{"product_id":2,"amount":3}""")),
            "recipes_pos_resolved" to listOf(row("""{"recipe_id":4,"product_id_effective":2,"qu_id":3,"recipe_amount":2.5}"""),row("""{"recipe_id":4,"product_id_effective":2,"qu_id":1,"recipe_amount":1}"""))))
        val line=s.requirements(4).single()
        assertEquals(BigDecimal("6.0"),line.required)
        assertEquals(BigDecimal("3.0"),line.missing)
        assertEquals(BigDecimal("3"),line.available)
        assertFalse(line.canConsume)
    }
    @Test fun unknownConversionAndVariableAmountsRequireManualReview() {
        fun row(s:String)=Json.parseToJsonElement(s).jsonObject
        val s=RecipeSnapshot(mapOf("products" to listOf(row("""{"id":2,"qu_id_stock":1}""")),"recipes_pos_resolved" to listOf(row("""{"recipe_id":4,"product_id_effective":2,"qu_id":3,"recipe_amount":2}"""))))
        assertTrue(runCatching { s.requirements(4) }.isFailure)
        assertFalse(RecipeAccess.allowed(setOf("CHORES")))
        assertTrue(RecipeAccess.allowed(setOf("RECIPES")))
    }
    @Test fun publicMetadataRequiresManualMappingAndKeepsSource() {
        val reader=app.stillroom.data.RecipeUrlReader()
        val html="""<html><script type="application/ld+json">{"@graph":[{"@type":"Recipe","name":"Soup","recipeYield":"4 servings","recipeIngredient":["1 cup rice","some salt"],"recipeInstructions":[{"@type":"HowToSection","itemListElement":[{"@type":"HowToStep","text":"Mix."},{"@type":"HowToStep","text":"Cook."}]}]}]}</script></html>"""
        val draft=reader.parse("https://example.org/soup",html)
        assertEquals("Soup",draft.name);assertEquals(BigDecimal("4"),draft.servings)
        assertEquals(listOf("1 cup rice","some salt"),draft.ingredients)
        assertEquals("Mix.\nCook.",draft.instructions)
        val description=app.stillroom.data.recipeDescription(draft.instructions,draft.source)
        assertEquals(draft.source,app.stillroom.data.recipeSource(description))
        assertEquals(listOf("Mix.","Cook."),app.stillroom.data.recipeSteps(description))
        assertTrue(app.stillroom.data.recipeDescription("<script>x</script>",draft.source).contains("&lt;script&gt;"))
        assertNull(reader.parse(draft.source,html.replace("4 servings","1 loaf")).servings)
        assertEquals("",reader.parse(draft.source,html.replace("</html>",html+"</html>")).name)
        assertEquals(emptyList<String>(),reader.parse(draft.source,"no metadata").ingredients)
        assertTrue(runCatching { app.stillroom.data.recipeDescription("x","javascript:alert(1)") }.isFailure)
        assertEquals(emptyList<String>(),app.stillroom.data.recipeSteps(""))
    }
    @Test fun recipeStockPayloadKeepsNativeAttribution() {
        val booking=StockBooking(StockAction.Consume,2,BigDecimal("1.5"),recipeId=4)
        assertEquals(4L,booking.payload().recipeId("recipe_id"))
        assertTrue(runCatching { StockBooking(StockAction.Purchase,2,BigDecimal.ONE,recipeId=4).payload() }.isFailure)
        assertFalse(RecipeAccess.mealPlan(setOf("RECIPES")))
        assertTrue(RecipeAccess.mealPlan(setOf("ADMIN")))
        assertTrue(runCatching { RecipeAmountInput(BigDecimal.ONE,Locale.US).apply { change("invalid") }.saved() }.isFailure)
    }
    @Test fun shoppingUsesNativeFulfillmentFlagsAndUnitConversions() {
        fun row(s:String)=Json.parseToJsonElement(s).jsonObject
        val s=RecipeSnapshot(mapOf("products" to listOf(row("""{"id":2,"qu_id_stock":1}""")),"quantity_unit_conversions_resolved" to listOf(row("""{"product_id":2,"from_qu_id":3,"to_qu_id":1,"factor":2}""")),"recipes_pos" to listOf(row("""{"id":7,"not_check_stock_fulfillment":1}""")),"recipes_pos_resolved" to listOf(row("""{"recipe_id":4,"product_id_effective":2,"qu_id":3,"recipe_amount":1.5,"missing_amount":1.5}"""),row("""{"recipe_id":4,"product_id_effective":2,"qu_id":1,"recipe_pos_id":7,"recipe_amount":20,"missing_amount":0,"need_fulfilled":1}"""))))
        assertEquals(BigDecimal("3.0"),s.missingAmounts(4)[2L])
        assertTrue(s.missingAmounts(8).isEmpty())
    }
}
