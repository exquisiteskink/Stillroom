package app.stillroom

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ShoppingListItemTest {
    @get:Rule val compose = createComposeRule()
    private val row = Json.parseToJsonElement("""{"id":7,"shopping_list_id":1,"product_id":3,"amount":1.5003,"qu_id":4,"note":"Ripe","done":0}""").jsonObject
    private val snapshot = ShoppingSnapshot(mapOf(
        "products" to Json.parseToJsonElement("""[{"id":3,"name":"Apples","qu_id_stock":1,"location_id":2}]""").jsonArray,
        "quantity_units" to Json.parseToJsonElement("""[{"id":4,"name":"Pack"}]""").jsonArray,
        "quantity_unit_conversions_resolved" to Json.parseToJsonElement("""[{"product_id":3,"from_qu_id":4,"to_qu_id":1,"factor":3}]""").jsonArray
    ), false)
    private var purchase: StockBooking? = null
    private var saved: ShoppingDraft? = null
    private var removed = false
    private fun show(item: JsonObject = row, canPurchase: Boolean = true, change: ShoppingChange? = null) {
        compose.setContent { StillroomTheme {
            ShoppingItem(item, item, snapshot, change, false, false, canPurchase,
                onDone = { saved = it }, onDelete = { removed = true }, onPurchase = { purchase = it })
        } }
    }
    @Test fun purchaseChoiceBooksExactStockAmountOnlyAfterConfirmation() {
        show()
        compose.onNodeWithContentDescription("Choose action for Apples").performClick()
        compose.runOnIdle { assertNull(purchase); assertNull(saved) }
        compose.onNodeWithText("Buy and add to pantry").performClick()
        compose.onNodeWithText("Add stock").assertDoesNotExist()
        compose.onNodeWithText("Edit").assertDoesNotExist()
        compose.onNodeWithText("Remove").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, BigDecimal("1.5003").compareTo(purchase!!.amount))
            assertEquals(2L, purchase!!.location)
            assertNull(purchase!!.date)
            assertNull(saved)
        }
    }
    @Test fun markDoneNeverBooksStockEvenWithPurchasePermission() {
        show()
        compose.onNodeWithContentDescription("Choose action for Apples").performClick()
        compose.onNodeWithText("Mark done").performClick()
        compose.runOnIdle { assertTrue(saved!!.done); assertNull(purchase) }
    }
    @Test fun cancellingPurchaseChoiceDoesNothing() {
        show()
        compose.onNodeWithContentDescription("Choose action for Apples").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertNull(saved); assertNull(purchase) }
    }
    @Test fun cancellingRemovalKeepsItem() {
        show()
        compose.onNodeWithText("Apples").performTouchInput { longClick() }
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Remove Apples?").assertDoesNotExist()
        compose.runOnIdle { assertFalse(removed); assertNull(saved); assertNull(purchase) }
    }
    @Test fun zeroQuantityAllowsMarkDoneButBlocksPurchase() {
        show(JsonObject(row + ("amount" to JsonPrimitive(0))))
        compose.onNodeWithContentDescription("Choose action for Apples").performClick()
        compose.onNodeWithText("Buy and add to pantry").assertIsNotEnabled()
        compose.onNodeWithText("Mark done").performClick()
        compose.runOnIdle { assertTrue(saved!!.done); assertNull(purchase) }
    }
    @Test fun removalConfirmationDisablesIfAnUnresolvedPurchaseArrives() {
        val change = mutableStateOf<ShoppingChange?>(null)
        compose.setContent { StillroomTheme {
            ShoppingItem(row, row, snapshot, change.value, false, false, true,
                { saved = it }, { removed = true }, { purchase = it })
        } }
        compose.onNodeWithText("Apples").performTouchInput { longClick() }
        compose.runOnIdle { change.value = ShoppingChange("purchase", "purchase", "shopping_list", 7, row.toString(), row.toString(), "needs-review", null, null, false) }
        compose.onNodeWithText("Remove").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertFalse(removed) }
    }
    @Test fun accessibilityRemovalActionAlsoRequiresConfirmation() {
        show()
        compose.onNode(hasText("Apples") and hasClickAction()).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.runOnIdle { assertFalse(removed) }
        compose.onNodeWithText("Remove Apples?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
    }
    @Test fun disabledStockFeatureStillAllowsMarkDone() {
        compose.setContent { StillroomTheme { CompositionLocalProvider(LocalServerCapabilities provides ServerCapabilities(disabled = setOf("STOCK"))) {
            ShoppingItem(row, row, snapshot, null, false, false, true,
                { saved = it }, { removed = true }, { purchase = it })
        } } }
        compose.onNodeWithContentDescription("Choose action for Apples").performClick()
        compose.onNodeWithText("Buy and add to pantry").assertIsNotEnabled()
        compose.onNodeWithText("Mark done").performClick()
        compose.runOnIdle { assertTrue(saved!!.done); assertNull(purchase) }
    }
    @Test fun quantityEditConvertsDisplayUnitsToStockUnits() {
        show()
        compose.onNodeWithContentDescription("Change quantity for Apples").performClick()
        compose.onNodeWithContentDescription("Quantity (fractions accepted)").performTextReplacement("1/2")
        compose.onNodeWithText("Save quantity").performClick()
        compose.runOnIdle { assertEquals(0, BigDecimal("1.5").compareTo(saved!!.amount)); assertFalse(saved!!.done) }
    }
    @Test fun unchangedQuantityRetainsOriginalPrecision() {
        show()
        compose.onNodeWithContentDescription("Change quantity for Apples").performClick()
        compose.onNodeWithText("Save quantity").performClick()
        compose.runOnIdle { assertEquals(0, BigDecimal("1.5003").compareTo(saved!!.amount)) }
    }
    @Test fun holdingItemAsksBeforeRemovingIt() {
        show()
        compose.onNodeWithText("Apples").performTouchInput { longClick() }
        compose.runOnIdle { assertFalse(removed); assertNull(purchase) }
        compose.onNodeWithText("Remove Apples?").assertExists()
        compose.onNodeWithText("Remove").performClick()
        compose.runOnIdle { assertTrue(removed); assertNull(purchase) }
    }
    @Test fun missingStockPermissionStillAllowsMarkDone() {
        show(canPurchase = false)
        compose.onNodeWithContentDescription("Choose action for Apples").performClick()
        compose.onNodeWithText("Buy and add to pantry").assertIsNotEnabled()
        compose.onNodeWithText("Mark done").performClick()
        compose.runOnIdle { assertTrue(saved!!.done); assertNull(purchase) }
    }
    @Test fun noteOnlyCheckboxCompletesWithoutStock() {
        show(JsonObject(row + ("product_id" to JsonNull)))
        compose.onNodeWithContentDescription("Mark Ripe complete").performClick()
        compose.runOnIdle { assertTrue(saved!!.done); assertNull(purchase) }
    }
    @Test fun completedCheckboxReopensWithoutChangingStock() {
        show(JsonObject(row + ("done" to JsonPrimitive(1))))
        compose.onNodeWithContentDescription("Mark Apples not done").performClick()
        compose.runOnIdle { assertFalse(saved!!.done); assertNull(purchase) }
    }
    @Test fun pendingEditBlocksPurchaseUntilConfirmed() {
        show(change = ShoppingChange("test", "edit", "shopping_list", 7, row.toString(), row.toString(), "pending", null, null, false))
        compose.onNodeWithContentDescription("Choose action for Apples").assertIsNotEnabled()
    }
    @Test fun invalidQuantityCannotBeSavedAndCancelKeepsItem() {
        show()
        compose.onNodeWithContentDescription("Change quantity for Apples").performClick()
        compose.onNodeWithContentDescription("Quantity (fractions accepted)").performTextReplacement("-1")
        compose.onNodeWithText("Save quantity").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertNull(saved); assertFalse(removed) }
    }
    @Test fun unavailableProductCanBeMarkedDoneButCannotBePurchased() {
        show(JsonObject(row + ("product_id" to JsonPrimitive(99))))
        compose.onNodeWithContentDescription("Choose action for Ripe").performClick()
        compose.onNodeWithText("Buy and add to pantry").assertIsNotEnabled()
        compose.onNodeWithText("Mark done").performClick()
        compose.runOnIdle { assertTrue(saved!!.done); assertNull(purchase) }
    }

    @Test fun clearCheckedItemsRemovesOnlyCompletedRows() {
        var cleared: List<JsonObject>? = null
        val completed = JsonObject(row + ("done" to JsonPrimitive(1)))
        compose.setContent { StillroomTheme {
            RemoveCheckedShoppingItems(listOf(row, completed), emptyList(), false) { cleared = it }
        } }
        compose.onNodeWithText("Remove checked items").performClick()
        compose.runOnIdle { assertEquals(listOf(completed), cleared) }
    }
    @Test fun clearCheckedItemsIsHiddenWhenNoneAreCompleted() {
        compose.setContent { StillroomTheme { RemoveCheckedShoppingItems(listOf(row), emptyList(), false) {} } }
        compose.onNodeWithText("Remove checked items").assertDoesNotExist()
    }
    @Test fun clearCheckedItemsWaitsForUnresolvedCompletedChanges() {
        val completed = JsonObject(row + ("done" to JsonPrimitive(1)))
        val pending = ShoppingChange("test", "purchase", "shopping_list", 7, row.toString(), row.toString(), "pending", null, null, false)
        compose.setContent { StillroomTheme { RemoveCheckedShoppingItems(listOf(completed), listOf(pending), false) {} } }
        compose.onNodeWithText("Remove checked items").assertIsNotEnabled()
    }

}
