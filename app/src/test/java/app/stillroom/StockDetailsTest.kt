package app.stillroom

import app.stillroom.domain.*
import app.stillroom.fractions.QuantityFormatter
import app.stillroom.fractions.QuantityStyle
import java.util.Base64
import java.util.Locale
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Settings → Stock → Shown details: visibility, ordering, reconcile and per-type rendering. */
class StockDetailsTest {
    private val note = UserfieldDefinition("note", "Note", "text-single-line")
    private val shelf = UserfieldDefinition("shelf", "Shelf", "number-integral", showAsColumn = true)
    private val organic = UserfieldDefinition("organic", "Organic", "checkbox")
    private val fields = listOf(note, shelf, organic)
    private val fractions = QuantityFormatter(QuantityStyle.Fractions)
    private val decimals = QuantityFormatter(QuantityStyle.Decimals)

    @Test fun defaultsMatchTodaysRowAndHideEveryUserfield() {
        val details = StockDetails(null, fields)
        assertEquals(listOf(StockBuiltIn.Amount, StockBuiltIn.Unit, StockBuiltIn.DueDate), details.main.map { it.builtIn })
        assertTrue(details.main.all { it.visible })
        assertTrue(details.extras.none { it.visible })
        // show_as_column_in_tables does not switch a field on: installing the update changes nothing.
        assertFalse(details.extras.single { it.field == shelf }.visible)
        assertTrue(details.visibleExtras.isEmpty())
        assertTrue(details.isDefault)
        assertEquals(StockBuiltIn.entries.filter { it.slot == StockDetailSlot.Extra }.map { it.key } + listOf(note.key, shelf.key, organic.key), details.extras.map { it.key })
    }

    @Test fun defaultRowsRenderNoExtraLines() {
        val product = buildJsonObject { put("id", 1); put("min_stock_amount", 2); put("location_id", 3); put("userfields", buildJsonObject { put("note", "Top shelf") }) }
        val lookups = StockRowLookups(listOf(buildJsonObject { put("product_id", 1); put("amount_opened", 1); put("value", 3.5) }), listOf(buildJsonObject { put("id", 3); put("name", "Fridge") }), emptyList(), emptyList())
        assertEquals(emptyList<String>(), stockRowExtras(StockDetails(null, fields), product, lookups, fractions, Locale.US))
    }

    @Test fun toggleChangesOnlyThatDetail() {
        val saved = StockDetails(null, fields).toggle(note.key, true)
        val details = StockDetails(saved, fields)
        assertTrue(details.items.single { it.key == note.key }.visible)
        assertEquals(listOf(note.key), details.visibleExtras.map { it.key })
        assertFalse(details.isDefault)
        val hiddenDue = StockDetails(StockDetails(saved, fields).toggle(StockBuiltIn.DueDate.key, false), fields)
        assertFalse(hiddenDue.shows(StockBuiltIn.DueDate))
        assertTrue(hiddenDue.shows(StockBuiltIn.Amount))
    }

    @Test fun reorderMovesExtrasAndStopsAtTheEnds() {
        var details = StockDetails(null, fields)
        val first = details.extras.first().key
        assertEquals(details.persisted, details.move(first, up = true))
        val last = details.extras.last().key
        assertEquals(details.persisted, details.move(last, up = false))
        details = StockDetails(details.move(organic.key, up = true), fields)
        assertEquals(listOf(note.key, organic.key, shelf.key), details.extras.map { it.key }.takeLast(3))
        details = StockDetails(details.move(note.key, up = false), fields)
        assertEquals(listOf(organic.key, note.key, shelf.key), details.extras.map { it.key }.takeLast(3))
        // Main details keep their place in the row and do not move.
        assertEquals(details.persisted, details.move(StockBuiltIn.Amount.key, up = false))
        assertFalse(details.isDefault)
    }

    @Test fun visibleExtrasRenderInTheChosenOrder() {
        var saved = StockDetails(null, fields).toggle(note.key, true)
        saved = StockDetails(saved, fields).toggle(StockBuiltIn.Location.key, true)
        saved = StockDetails(saved, fields).move(note.key, up = true).let { s -> (1..20).fold(s) { acc, _ -> StockDetails(acc, fields).move(note.key, up = true) } }
        val product = buildJsonObject { put("id", 1); put("location_id", 3); put("userfields", buildJsonObject { put("note", "Top shelf") }) }
        val lookups = StockRowLookups(emptyList(), listOf(buildJsonObject { put("id", 3); put("name", "Fridge") }), emptyList(), emptyList())
        assertEquals(listOf("Note: Top shelf", "Default location: Fridge"), stockRowExtras(StockDetails(saved, fields), product, lookups, fractions, Locale.US))
    }

    @Test fun removedServerFieldsArePrunedAndNewOnesAppearHidden() {
        val saved = StockDetails(null, fields).toggle(shelf.key, true)
        val added = UserfieldDefinition("brand", "Brand", "text-single-line")
        val now = StockDetails(saved, listOf(note, organic, added))
        assertNull(now.items.find { it.key == shelf.key })
        assertNull(now.persisted.find { it.key == shelf.key })
        assertEquals(added.key, now.extras.last().key)
        assertFalse(now.extras.last().visible)
        // Unknown or duplicate saved keys are ignored.
        val noisy = listOf(StockDetailSetting("builtin:retired", true), StockDetailSetting(note.key, true), StockDetailSetting(note.key, false))
        val cleaned = StockDetails(noisy, fields)
        assertNull(cleaned.items.find { it.key == "builtin:retired" })
        assertTrue(cleaned.items.single { it.key == note.key }.visible)
        assertEquals(StockBuiltIn.entries.size + fields.size, cleaned.items.size)
    }

    @Test fun unknownDefinitionsKeepSavedFieldChoices() {
        val saved = StockDetails(null, fields).toggle(shelf.key, true)
        val offline = StockDetails(saved, null)
        assertTrue(offline.items.none { it.field != null })
        // Changing a built-in while definitions are unavailable must not erase the userfield choice.
        val next = offline.toggle(StockBuiltIn.Opened.key, true)
        assertTrue(next.single { it.key == shelf.key }.visible)
        assertTrue(StockDetails(next, fields).items.single { it.key == shelf.key }.visible)
    }

    @Test fun choicesAreIsolatedPerAccount() {
        val store = InMemoryStockDetailsStore()
        store.save("account-a", StockDetails(null, fields).toggle(note.key, true))
        assertNull(store.load("account-b"))
        assertTrue(StockDetails(store.load("account-b"), fields).isDefault)
        assertTrue(StockDetails(store.load("account-a"), fields).items.single { it.key == note.key }.visible)
        store.clear("account-a")
        assertNull(store.load("account-a"))
    }

    @Test fun codecRoundTripsAndToleratesGarbage() {
        val saved = StockDetails(null, fields).toggle(note.key, true)
        assertEquals(saved, StockDetailsCodec.decode(StockDetailsCodec.encode(saved)))
        assertNull(StockDetailsCodec.decode(null))
        assertNull(StockDetailsCodec.decode("not json"))
        assertEquals(listOf(StockDetailSetting("a", true)), StockDetailsCodec.decode("""[{"key":"a","visible":true},{"visible":true},3]"""))
    }

    @Test fun parsesOnlyProductUserfieldsInGrocyOrder() {
        val rows = Json.parseToJsonElement("""[
            {"id":1,"entity":"products","name":"z","caption":"Zed","type":"text-single-line","show_as_column_in_tables":0,"sort_number":null},
            {"id":2,"entity":"products","name":"b","caption":"","type":"checkbox","show_as_column_in_tables":1,"sort_number":2},
            {"id":3,"entity":"chores","name":"c","caption":"Chore","type":"text-single-line"},
            {"id":4,"entity":"products","name":"a","caption":"Alpha","type":"date","sort_number":1}
        ]""").jsonArray.map { it.jsonObject }
        val parsed = UserfieldDefinition.parse(rows)
        assertEquals(listOf("a", "b", "z"), parsed.map { it.name })
        assertTrue(parsed[1].showAsColumn)
        assertEquals("b", parsed[1].label)
    }

    private fun render(type: String, value: String?, formatter: QuantityFormatter = fractions) =
        UserfieldText.render(type, value?.let { JsonPrimitive(it) } ?: JsonNull, formatter, Locale.US)

    @Test fun rendersEveryUserfieldType() {
        assertEquals("Top shelf", render("text-single-line", " Top shelf "))
        assertEquals("Line one · Line two", render("text-multi-line", "Line one\n\nLine two"))
        assertEquals("3", render("number-integral", "3"))
        assertEquals("1½", render("number-decimal", "1.5"))
        assertEquals("1.5", render("number-decimal", "1.5", decimals))
        assertEquals("≈⅓", render("number-decimal", "0.33"))
        assertEquals("0.33", render("number-decimal", "0.33", decimals))
        assertEquals("4.50", render("number-currency", "4.5"))
        assertEquals("Oct 8, 2026", render("date", "2026-10-08"))
        assertTrue(render("datetime", "2026-10-08 14:30:00")!!.startsWith("Oct 8, 2026"))
        assertEquals("Yes", render("checkbox", "1"))
        assertEquals("No", render("checkbox", "0"))
        assertEquals("Red", render("preset-list", "Red"))
        assertEquals("Red, Blue", render("preset-checklist", "Red,Blue"))
        assertEquals("https://example.org/a", render("link", "https://example.org/a"))
        assertEquals("Recipe", render("link-with-title", """{"title":"Recipe","link":"https://example.org"}"""))
        assertEquals("https://example.org", render("link-with-title", """{"title":"","link":"https://example.org"}"""))
        val stored = "abc123_" + Base64.getEncoder().encodeToString("receipt.pdf".toByteArray())
        assertEquals("File: receipt.pdf", render("file", stored))
        assertEquals("Image: receipt.pdf", render("image", stored))
        assertEquals("File: plain", render("file", "plain"))
        assertEquals("legacy", render("text", "legacy"))
        assertEquals("not-a-date", render("date", "not-a-date"))
        assertNull(render("text-single-line", ""))
        assertNull(render("checkbox", null))
    }

    @Test fun builtInExtrasUseTheQuantityFormatterAndSkipEmptyValues() {
        val all = StockDetails(null, fields).let { d -> d.extras.filter { it.builtIn != null }.fold(d.persisted) { acc, item -> StockDetails(acc, fields).toggle(item.key, true) } }
        val details = StockDetails(all, fields)
        val product = buildJsonObject { put("id", 1); put("location_id", 3); put("product_group_id", 5); put("min_stock_amount", 0.5) }
        val stock = buildJsonObject { put("product_id", 1); put("amount_opened", 0.25); put("amount_aggregated", 2.5); put("is_aggregated_amount", 1); put("value", 6.25) }
        val lookups = StockRowLookups(listOf(stock), listOf(buildJsonObject { put("id", 3); put("name", "Fridge") }),
            listOf(buildJsonObject { put("id", 5); put("name", "Dairy") }),
            listOf(buildJsonObject { put("product_id", 1); put("barcode", "123") }, buildJsonObject { put("product_id", 1); put("barcode", "456") }))
        assertEquals(listOf("Opened: ¼", "With subproducts: 2½", "Default location: Fridge", "Group: Dairy", "Value: 6.25", "Min. stock: ½", "Barcodes: 123, 456"),
            stockRowExtras(details, product, lookups, fractions, Locale.US))
        val bare = buildJsonObject { put("id", 2) }
        assertEquals(emptyList<String>(), stockRowExtras(details, bare, StockRowLookups(listOf(buildJsonObject { put("product_id", 2); put("amount_opened", 0); put("is_aggregated_amount", 0) }), emptyList(), emptyList(), emptyList()), fractions, Locale.US))
    }

    @Test fun productsWithoutInlineUserfieldsShowNothingForThem() {
        val details = StockDetails(StockDetails(null, fields).toggle(note.key, true), fields)
        val lookups = StockRowLookups(emptyList(), emptyList(), emptyList(), emptyList())
        assertEquals(emptyList<String>(), stockRowExtras(details, buildJsonObject { put("id", 1) }, lookups, fractions, Locale.US))
        assertEquals(emptyList<String>(), stockRowExtras(details, buildJsonObject { put("id", 1); put("userfields", JsonNull) }, lookups, fractions, Locale.US))
    }
}
