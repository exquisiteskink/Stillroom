package app.stillroom

import app.stillroom.domain.*
import kotlinx.serialization.json.*
import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class ShoppingTest {
    @Test fun quantitiesAreNumericStockUnitsAndNotesRoundTrip() {
        val draft = ShoppingDraft(2, 3, "Keep refrigerated", BigDecimal("2.5"), 4)
        assertEquals("2.5", draft.payload()["amount"]!!.jsonPrimitive.content)
        assertFalse(draft.payload()["amount"]!!.jsonPrimitive.isString)
        assertEquals(2, draft.payload()["shopping_list_id"]!!.jsonPrimitive.int)
        assertEquals("Keep refrigerated", draft.payload()["note"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, ShoppingDraft(1, null, "Apples", BigDecimal.ONE).payload()["product_id"])
        assertThrows(IllegalArgumentException::class.java) { ShoppingDraft(0, 1, "", BigDecimal.ONE).payload() }
        assertThrows(IllegalArgumentException::class.java) { ShoppingDraft(1, null, "", BigDecimal.ONE).payload() }
        assertThrows(IllegalArgumentException::class.java) { ShoppingDraft(1, 1, "", BigDecimal("-1")).payload() }
    }
    @Test fun unknownPricesAreExcludedInsteadOfPretendingZero() {
        val snapshot = ShoppingSnapshot(mapOf(
            "shopping_list" to Json.parseToJsonElement("""[{"id":1,"shopping_list_id":1,"product_id":2,"amount":2.5,"done":0},{"id":2,"shopping_list_id":1,"product_id":3,"amount":1,"done":0},{"id":3,"shopping_list_id":1,"product_id":2,"amount":3,"done":1}]""").jsonArray,
            "products_last_purchased" to Json.parseToJsonElement("""[{"product_id":2,"price":4}]""").jsonArray,
        ), false)
        val estimate = snapshot.estimate(1)
        assertEquals(0, estimate.knownTotal.compareTo(BigDecimal("10")))
        assertEquals(1, estimate.unknownRows)
    }
    @Test fun claimsAreStableAcrossClientsButSeparateRowInstancesAndEntities() {
        val row = Json.parseToJsonElement("""{"id":5,"row_created_timestamp":"2026-10-07 13:00:00","note":"first"}""").jsonObject
        val edited = JsonObject(row + ("note" to JsonPrimitive("second")))
        assertEquals(ShoppingClaims.id("shopping_list", row), ShoppingClaims.id("shopping_list", edited))
        assertTrue(ShoppingClaims.id("shopping_list", row) < 0)
        assertNotEquals(ShoppingClaims.id("shopping_list", row), ShoppingClaims.id("shopping_lists", row))
        assertNotEquals(ShoppingClaims.id("shopping_list", row), ShoppingClaims.id("shopping_list", JsonObject(row + ("row_created_timestamp" to JsonPrimitive("later")))))
    }
    @Test fun groupingConversionsAndQueuedProjectionKeepServerValuesIntact() {
        val snapshot = ShoppingSnapshot(mapOf(
            "shopping_list" to Json.parseToJsonElement("""[{"id":1,"shopping_list_id":1,"product_id":2,"amount":2,"done":0,"note":"server"}]""").jsonArray,
            "products" to Json.parseToJsonElement("""[{"id":2,"qu_id_stock":1,"product_group_id":5,"shopping_location_id":null}]""").jsonArray,
            "products_last_purchased" to Json.parseToJsonElement("""[{"product_id":2,"price":3.25,"shopping_location_id":3}]""").jsonArray,
            "shopping_locations" to Json.parseToJsonElement("""[{"id":3,"name":"Market"}]""").jsonArray,
            "product_groups" to Json.parseToJsonElement("""[{"id":5,"name":"Produce"}]""").jsonArray,
            "quantity_unit_conversions_resolved" to Json.parseToJsonElement("""[{"product_id":2,"from_qu_id":4,"to_qu_id":1,"factor":12}]""").jsonArray,
        ), true)
        val row = snapshot.rows("shopping_list").single()
        assertEquals("Market", snapshot.group(row, true)); assertEquals("Produce", snapshot.group(row, false))
        assertEquals(BigDecimal.ONE, snapshot.factor("2", "1")); assertEquals(BigDecimal("12"), snapshot.factor("2", "4"))
        assertNull(snapshot.factor("2", "9")); assertNull(snapshot.factor("99", "1"))
        assertEquals("Uncategorized", snapshot.group(JsonObject(emptyMap()), false)); assertEquals("No known store", snapshot.group(JsonObject(emptyMap()), true))
        fun change(id: String, kind: String, rowId: Long?, desired: String) = ShoppingChange(id, kind, "shopping_list", rowId, row.toString(), desired, "pending", null, null, false)
        val edited = snapshot.projected(listOf(change("edit", "edit", 1, """{"amount":3,"note":"local"}"""), change("add", "add", null, """{"shopping_list_id":1,"product_id":2,"amount":1,"done":0}""")))
        assertEquals("server", snapshot.rows("shopping_list").single().shoppingText("note"))
        assertEquals(2, edited.rows("shopping_list").size); assertTrue(edited.stale)
        assertEquals(0, edited.estimate(1).knownTotal.compareTo(BigDecimal("13.00")))
        assertTrue(snapshot.projected(listOf(change("delete", "delete", 1, "{}"))).rows("shopping_list").isEmpty())
    }

}
