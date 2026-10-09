package app.stillroom

import app.stillroom.domain.*
import app.stillroom.fractions.QuantityFormatter
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.Locale
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Product form validation, payload mapping, userfield round-trips and save outcomes. Pure JVM. */
class ProductEditorTest {
    private val us = Locale.US
    private fun form(original: JsonObject? = null) = CatalogForm(CatalogEntity.Products, original, QuantityFormatter(), us)

    @Test fun createNeedsNameLocationAndStockAndPurchaseUnits() {
        val f = form()
        val errors = f.errors(f.initial())
        assertEquals(setOf("name", "location_id", "qu_id_stock", "qu_id_purchase"), errors.keys)
        // Consume and price units may stay empty: Grocy's insert triggers default them, and so does the payload.
        assertTrue(f.optional(f.fields.first { it.name == "qu_id_consume" }))
        assertTrue(f.optional(f.fields.first { it.name == "qu_id_price" }))
    }

    @Test fun createPayloadMapsTypesAndDefaultsUnitsLikeGrocy() {
        val f = form()
        val texts = f.initial() + mapOf("name" to "Oat milk", "location_id" to "3", "qu_id_stock" to "4", "qu_id_purchase" to "5",
            "min_stock_amount" to "1½", "default_best_before_days" to "-1", "product_group_id" to "7", "description" to "")
        val payload = f.payload(texts)
        assertEquals("Oat milk", payload["name"]!!.jsonPrimitive.content)
        assertEquals(3L, payload["location_id"]!!.jsonPrimitive.long)
        assertEquals(4L, payload["qu_id_consume"]!!.jsonPrimitive.long) // = stock unit
        assertEquals(5L, payload["qu_id_price"]!!.jsonPrimitive.long)   // = purchase unit
        assertEquals(0, BigDecimal("1.5").compareTo(payload["min_stock_amount"]!!.jsonPrimitive.content.toBigDecimal()))
        assertEquals(-1L, payload["default_best_before_days"]!!.jsonPrimitive.long)
        assertEquals(7L, payload["product_group_id"]!!.jsonPrimitive.long)
        assertEquals(1, payload["active"]!!.jsonPrimitive.int)
        assertFalse(payload.containsKey("shopping_location_id"))
        CatalogFields.validate(CatalogEntity.Products, payload)
    }

    @Test fun invalidValuesGiveFieldMessages() {
        val f = form()
        val errors = f.errors(f.initial() + mapOf("name" to "X", "location_id" to "3", "qu_id_stock" to "4", "qu_id_purchase" to "4",
            "min_stock_amount" to "lots", "default_best_before_days" to "-3", "quick_consume_amount" to "-1"))
        assertEquals("Enter a number like 1.5 or 1½.", errors["min_stock_amount"])
        assertTrue(errors["default_best_before_days"]!!.contains("-1 for never"))
        assertNotNull(errors["quick_consume_amount"])
        assertTrue(runCatching { f.payload(f.initial()) }.isFailure)
    }

    @Test fun editSendsOnlyChangedFieldsAndKeepsApproximateDecimals() {
        val original = Json.parseToJsonElement("""{"id":9,"name":"Flour","description":null,"location_id":3,"product_group_id":7,"qu_id_stock":4,"qu_id_purchase":4,"qu_id_consume":4,"qu_id_price":4,"min_stock_amount":0.3333,"quick_consume_amount":1,"active":1,"calories":null}""").jsonObject
        val f = form(original)
        val initial = f.initial()
        assertEquals("⅓", initial["min_stock_amount"])
        assertEquals(JsonObject(emptyMap()), f.payload(initial))
        val changed = f.payload(initial + mapOf("name" to "Bread flour", "product_group_id" to "", "calories" to ""))
        assertEquals(setOf("name", "product_group_id"), changed.keys)
        assertEquals(JsonNull, changed["product_group_id"])
        // Required units cannot be cleared on edit.
        assertFalse(f.optional(f.fields.first { it.name == "qu_id_consume" }))
        assertEquals("Choose one.", f.errors(initial + ("qu_id_consume" to ""))["qu_id_consume"])
    }

    @Test fun factorAndBarcodeExtras() {
        val conversions = Json.parseToJsonElement("""[{"product_id":null,"from_qu_id":5,"to_qu_id":4,"factor":6},{"product_id":9,"from_qu_id":5,"to_qu_id":4,"factor":12}]""").jsonArray.map { it.jsonObject }
        assertEquals(0, BigDecimal(12).compareTo(ProductExtrasForm.currentFactor(conversions, 9, 5, 4)))
        assertEquals(0, BigDecimal(6).compareTo(ProductExtrasForm.currentFactor(conversions, 10, 5, 4)))
        assertEquals(BigDecimal.ONE, ProductExtrasForm.currentFactor(conversions, null, 4, 4))
        assertNull(ProductExtrasForm.build("12", BigDecimal(12), 5, 4, emptyList(), us).purchaseToStockFactor)
        assertEquals(0, BigDecimal(24).compareTo(ProductExtrasForm.build("24", BigDecimal(12), 5, 4, emptyList(), us).purchaseToStockFactor))
        assertNull(ProductExtrasForm.build("24", BigDecimal.ONE, 4, 4, emptyList(), us).purchaseToStockFactor)
        assertNotNull(ProductExtrasForm.errors("0", 5, 4, emptyList(), emptyList(), us)["factor"])
        assertEquals(listOf("123", "456"), ProductExtrasForm.parseBarcodes(" 123 \n\n456,"))
        assertTrue(ProductExtrasForm.errors("", 4, 4, listOf("1", "1"), emptyList(), us)["barcodes"]!!.contains("twice"))
        assertTrue(ProductExtrasForm.errors("", 4, 4, listOf("9"), listOf("9"), us)["barcodes"]!!.contains("already"))
    }

    private fun uf(type: String, required: Boolean = false, config: String = "", default: String = "") =
        UserfieldDefinition("f", "Field", type, inputRequired = required, config = config, defaultValue = default)

    @Test fun everyUserfieldTypeRoundTripsItsStoredValue() {
        val stored = mapOf(
            "text-single-line" to "Top shelf", "text-multi-line" to "Line one\nLine two", "number-integral" to "3",
            "number-decimal" to "1.5", "number-currency" to "4.5", "date" to "2026-10-08", "datetime" to "2026-10-08 14:30:00",
            "checkbox" to "1", "preset-list" to "Red", "preset-checklist" to "Red,Blue",
            "link" to "https://example.org/a", "link-with-title" to """{"title":"Recipe","link":"https://example.org"}""",
        )
        for ((type, value) in stored) {
            val field = uf(type, config = "Red\nBlue\nGreen")
            val start = UserfieldValues.initial(field, value, creating = false)
            assertEquals(type, value, UserfieldValues.normalize(field, start, us))
            assertEquals(type, JsonObject(emptyMap()), UserfieldValues.changes(listOf(field), buildJsonObject { put("f", value) }, mapOf("f" to start), us))
        }
        assertEquals(14, stored.size + 2) // plus file and image, which are read-only
        val file = uf("file"); val image = uf("image")
        assertFalse(UserfieldTypes.editable(file.type)); assertFalse(UserfieldTypes.editable(image.type))
        assertEquals(JsonObject(emptyMap()), UserfieldValues.changes(listOf(file, image.copy(name = "g")), buildJsonObject { put("f", "a_b"); put("g", "c_d") }, mapOf("f" to "", "g" to ""), us))
        assertEquals(UserfieldTypes.ALL.toSet(), (stored.keys + "file" + "image"))
    }

    @Test fun userfieldInputsNormalizeAndValidate() {
        assertEquals("1.5", UserfieldValues.normalize(uf("number-decimal"), "1½", us))
        assertEquals("2026-10-08 14:30:00", UserfieldValues.normalize(uf("datetime"), "2026-10-08 14:30", us))
        assertEquals("Red,Green", UserfieldValues.checklistValue(listOf("Green", "Red"), listOf("Red", "Blue", "Green")))
        assertEquals("Red,Old", UserfieldValues.checklistValue(listOf("Old", "Red"), listOf("Red", "Blue")))
        assertEquals("""{"title":"T","link":"https://x.org"}""", UserfieldValues.linkValue("T", "https://x.org"))
        assertEquals("", UserfieldValues.linkValue(" ", ""))
        assertEquals(UserfieldValues.Link("", "https://plain.org"), UserfieldValues.link("https://plain.org"))
        assertNotNull(UserfieldValues.error(uf("number-integral"), "1.5", us))
        assertNotNull(UserfieldValues.error(uf("date"), "08/10/2026", us))
        assertNotNull(UserfieldValues.error(uf("link"), "example.org", us))
        assertNotNull(UserfieldValues.error(uf("preset-list", config = "A\nB"), "C", us))
        assertNotNull(UserfieldValues.error(uf("text-single-line"), "two\nlines", us))
        assertNotNull(UserfieldValues.error(uf("link-with-title"), UserfieldValues.linkValue("Title only", ""), us))
        assertEquals("Field is required.", UserfieldValues.error(uf("text-single-line", required = true), " ", us))
        assertNull(UserfieldValues.error(uf("checkbox", required = true), "0", us))
        val now = LocalDateTime.of(2026, 10, 8, 9, 15, 30)
        assertEquals("2026-10-08", UserfieldValues.initial(uf("date", default = "now"), null, true, now))
        assertEquals("2026-10-08 09:15:30", UserfieldValues.initial(uf("datetime", default = "now"), null, true, now))
        assertEquals("", UserfieldValues.initial(uf("date", default = "now"), null, false, now))
        assertEquals(listOf("A", "B"), UserfieldValues.options(uf("preset-list", config = "A\r\n\nB\n")))
    }

    @Test fun onlyChangedUserfieldsAreSent() {
        val fields = listOf(uf("text-single-line").copy(name = "a"), uf("number-decimal").copy(name = "b"), uf("checkbox").copy(name = "c"))
        val original = buildJsonObject { put("a", "x"); put("b", "1.50"); put("c", JsonNull) }
        assertEquals(JsonObject(emptyMap()), UserfieldValues.changes(fields, original, mapOf("a" to "x", "b" to "1.5", "c" to "0"), us))
        assertEquals(buildJsonObject { put("a", "y") }, UserfieldValues.changes(fields, original, mapOf("a" to "y", "b" to "1.5"), us))
        // On create, empty values are not sent.
        assertEquals(buildJsonObject { put("b", "2") }, UserfieldValues.changes(fields.take(2), null, mapOf("a" to "", "b" to "2"), us))
    }

    @Test fun catalogEditorTypeNamesAreGrocysWithLegacyAliases() {
        assertEquals(14, UserfieldTypes.ALL.size)
        listOf("text-single-line", "number-decimal", "datetime", "preset-list").forEach { assertTrue(it, UserfieldTypes.editable(it)) }
        assertEquals("text-single-line", UserfieldTypes.canonical("text"))
        assertEquals("number-decimal", UserfieldTypes.canonical("numeric"))
        assertEquals("datetime", UserfieldTypes.canonical("date-time"))
        assertFalse(UserfieldTypes.editable("future-widget"))
        val parsed = UserfieldDefinition.parse(Json.parseToJsonElement("""[{"entity":"equipment","name":"n","caption":"N","type":"preset-list","config":"A\nB","input_required":1,"default_value":"A"}]""").jsonArray.map { it.jsonObject }, "equipment").single()
        assertTrue(parsed.inputRequired); assertEquals("A", parsed.defaultValue); assertEquals(listOf("A", "B"), UserfieldValues.options(parsed))
    }

    @Test fun saveOutcomesCoverPartialFailure() {
        val created = OutboxView("confirmed", response = """{"created_object_id":"41"}""")
        assertEquals(CatalogSaveOutcome.Saved(41), CatalogOutcomes.evaluate(created, null, listOf("Custom fields" to OutboxView("confirmed"))))
        val partial = CatalogOutcomes.evaluate(created, null, listOf("Custom fields" to OutboxView("failed", "HTTP 400: Field x is invalid")))
        assertTrue(partial is CatalogSaveOutcome.Partial && partial.id == 41L && partial.message.contains("Field x is invalid"))
        val failed = CatalogOutcomes.evaluate(OutboxView("failed", "HTTP 400: SQLSTATE[23000]: UNIQUE constraint failed: products.name"), null, emptyList())
        assertEquals(CatalogSaveOutcome.Failed("A product with this name already exists in Grocy."), failed)
        assertEquals(CatalogSaveOutcome.Unconfirmed(CatalogErrors.UNCONFIRMED), CatalogOutcomes.evaluate(OutboxView("needs-review", "Unknown HTTP outcome"), null, emptyList()))
        assertTrue(CatalogOutcomes.evaluate(null, 9, listOf("Custom fields" to null)) is CatalogSaveOutcome.Partial)
        assertEquals(CatalogSaveOutcome.Saved(9), CatalogOutcomes.evaluate(null, 9, listOf("Custom fields" to OutboxView("confirmed"))))
        assertTrue(CatalogErrors.friendly("HTTP 403").contains("MASTER_DATA_EDIT"))
        assertEquals("Grocy rejected the change: Something odd", CatalogErrors.friendly("HTTP 400: Something odd"))
    }
}
