package app.stillroom

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.stillroom.domain.*
import app.stillroom.ui.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

/** Host-side Compose regressions; these are not phone or emulator tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ShoppingFormsTest {
    @get:Rule val compose = createComposeRule()
    private val snapshot = ShoppingSnapshot(mapOf(
        "products" to Json.parseToJsonElement("""[{"id":3,"name":"Test product","qu_id_stock":1,"location_id":2}]""").jsonArray,
        "quantity_units" to Json.parseToJsonElement("""[{"id":1,"name":"Unit"},{"id":4,"name":"Pack"}]""").jsonArray,
        "locations" to Json.parseToJsonElement("""[{"id":2,"name":"Shelf"}]""").jsonArray,
        "quantity_unit_conversions_resolved" to Json.parseToJsonElement("""[{"product_id":3,"from_qu_id":4,"to_qu_id":1,"factor":3}]""").jsonArray,
    ), false)
    private fun row(amount: String, unit: Long = 1) = buildJsonObject {
        put("id", 7); put("shopping_list_id", 1); put("product_id", 3)
        put("amount", Json.parseToJsonElement(amount)); put("qu_id", unit)
        put("note", "Original"); put("done", 0)
    }

    @Test fun unitChangePreservesUneditedApproximateQuantity() {
        var saved: ShoppingDraft? = null
        compose.setContent { StillroomTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ShoppingItemForm(1, snapshot, row("0.5001", 1), null, false, {}) { saved = it }
            }
        } }
        compose.onNodeWithContentDescription("Quantity unit: Unit").performClick()
        compose.onNodeWithText("Pack").performClick()
        compose.onNodeWithText("Save item").performScrollTo().performClick()
        compose.runOnIdle {
            assertNotNull(saved)
            assertEquals(4L, saved!!.unitId)
            assertEquals(0, BigDecimal("1.5003").compareTo(saved!!.amount))
        }
    }

    @Test fun noteOnlyEditPreservesApproximateQuantity() = editPreserves("0.3333", 1)
    @Test fun noteOnlyEditPreservesQuantityAcrossRepeatingUnitConversion() = editPreserves("1", 4)

    private fun editPreserves(original: String, unit: Long) {
        var saved: ShoppingDraft? = null
        compose.setContent { StillroomTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ShoppingItemForm(1, snapshot, row(original, unit), null, false, {}) { saved = it }
            }
        } }
        compose.onNodeWithContentDescription("Notes").performTextReplacement("Changed note")
        compose.onNodeWithText("Save item").performScrollTo().performClick()
        compose.runOnIdle {
            assertNotNull(saved)
            assertEquals("Changed note", saved!!.note)
            assertEquals(0, BigDecimal(original).compareTo(saved!!.amount))
        }
    }

    @Test fun purchaseReviewPreservesUnchangedApproximateQuantity() {
        var saved: StockBooking? = null
        compose.setContent { StillroomTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PurchaseFromListReview(row("0.5001"), snapshot, false, {}) { saved = it }
            }
        } }
        compose.onNodeWithContentDescription("Due date (optional)").performScrollTo().performTextReplacement("2027-01-01")
        compose.onNodeWithText("Add stock").performScrollTo().performClick()
        compose.runOnIdle { assertNotNull(saved); assertEquals(0, BigDecimal("0.5001").compareTo(saved!!.amount)) }
    }

    @Test fun purchaseReviewDoesNotInventAPackageDueDate() {
        var saved: StockBooking? = null
        compose.setContent { StillroomTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PurchaseFromListReview(row("1"), snapshot, false, {}) { saved = it }
            }
        } }
        compose.onNodeWithContentDescription("Due date (optional)").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithText("Add stock").performScrollTo().performClick()
        compose.runOnIdle { assertNotNull(saved); assertNull(saved!!.date) }
    }

    @Test fun purchaseReviewBlocksAnInvalidDateAndKeepsTheInput() {
        var saved: StockBooking? = null
        compose.setContent { StillroomTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PurchaseFromListReview(row("1"), snapshot, false, {}) { saved = it }
            }
        } }
        compose.onNodeWithContentDescription("Due date (optional)").performScrollTo().performTextReplacement("tomorrow")
        compose.onNodeWithText("Add stock").assertIsNotEnabled()
        compose.onNodeWithText("Enter a date as YYYY-MM-DD.").assertExists()
        compose.runOnIdle { assertNull(saved) }
    }

    @Test fun scalingWithoutEditingPreservesStoredServings() {
        var saved: BigDecimal? = null
        val row = buildJsonObject { put("desired_servings", Json.parseToJsonElement("0.3333")) }
        compose.setContent { StillroomTheme { Column { RecipeServings(row, false) { saved = it } } } }
        compose.onNodeWithText("Scale servings").performClick()
        compose.runOnIdle { assertNotNull(saved); assertEquals(0, BigDecimal("0.3333").compareTo(saved)) }
    }

    @Test fun importReviewEditedServingsUsesTheEnteredQuantity() {
        var saved: JsonObject? = null
        val draft = RecipeImport("https://example.org/import", "Soup", "Cook.", emptyList(), BigDecimal("0.5001"))
        compose.setContent { StillroomTheme {
            ImportRecipeReview(draft, RecipeSnapshot(), false, {}, {}) { fields, _ -> saved = fields }
        } }
        compose.onNodeWithContentDescription("Base servings · required").performTextReplacement("3")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertNotNull(saved)
            assertEquals(0, BigDecimal("3").compareTo(saved!!.recipeDecimal("base_servings")))
        }
    }

    @Test fun importReviewWithoutEditingPreservesBaseServings() {
        var saved: JsonObject? = null
        val draft = RecipeImport("https://example.org/import", "Soup", "Cook.", emptyList(), BigDecimal("0.5001"))
        compose.setContent { StillroomTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ImportRecipeReview(draft, RecipeSnapshot(), false, {}, {}) { fields, _ -> saved = fields }
            }
        } }
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertNotNull(saved)
            assertEquals(0, BigDecimal("0.5001").compareTo(saved!!.recipeDecimal("base_servings")))
        }
    }

    @Test fun productEditDoesNotInventZeroForUnsetDecimals() {
        var saved: JsonObject? = null
        val row = buildJsonObject {
            put("id", 9); put("name", "Milk"); put("description", "Original")
            put("location_id", 2); put("qu_id_stock", 3); put("qu_id_purchase", 3); put("qu_id_consume", 3); put("qu_id_price", 3)
            put("min_stock_amount", Json.parseToJsonElement("0.5001")); put("calories", JsonNull)
        }
        compose.setContent { StillroomTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CatalogEditor(CatalogEntity.Products, row, CatalogSnapshot(), false, null, {}) { fields, _, _ -> saved = fields }
            }
        } }
        compose.onNodeWithContentDescription("Description").performTextReplacement("Changed description")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertNotNull(saved)
            assertEquals("Changed description", saved!!.catalogText("description"))
            // Only changed fields are sent: the stored 0.5001 is kept by not sending it at all.
            assertFalse(saved!!.containsKey("min_stock_amount"))
            assertFalse(saved!!.containsKey("calories"))
            assertFalse(saved!!.containsKey("quick_consume_amount"))
        }
    }

    @Test fun productEditCanRecordAPreviouslyUnsetCalorieCount() {
        var saved: JsonObject? = null
        val row = buildJsonObject {
            put("name", "Milk"); put("location_id", 2)
            put("qu_id_stock", 3); put("qu_id_purchase", 3); put("qu_id_consume", 3); put("qu_id_price", 3)
        }
        compose.setContent { StillroomTheme {
            CatalogEditor(CatalogEntity.Products, row, CatalogSnapshot(), false, null, {}) { fields, _, _ -> saved = fields }
        } }
        compose.onNodeWithText("Advanced product settings").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Calories per stock unit").performScrollTo().performTextReplacement("12")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertNotNull(saved)
            assertEquals(0, BigDecimal("12").compareTo(saved!!.catalogText("calories").toBigDecimal()))
        }
    }
}
