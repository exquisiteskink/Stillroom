package app.stillroom

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.stillroom.domain.PendingChange
import app.stillroom.domain.StockAction
import app.stillroom.ui.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Host Compose checks of the transaction review; no camera or live server is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ScannerReviewTest {
    @get:Rule val compose = createComposeRule()
    private fun launch(action: StockAction, operations: List<PendingChange> = emptyList()) {
        val state = StockUiState(selected = 7, operations = operations, resources = mapOf(
            "/stock/products/7" to Json.parseToJsonElement("""{"product":{"id":7,"name":"Milk","qu_id_stock":1},"stock_amount":2,"quantity_unit_stock":{"name":"bottle"}}"""),
            "/objects/quantity_units" to Json.parseToJsonElement("""[{"id":1,"name":"bottle"}]"""),
        ))
        compose.setContent { StillroomTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) { StockForm(state, Json.parseToJsonElement("""{"id":7,"name":"Milk","qu_id_stock":1}""").jsonObject, {}, {}, setOf("ADMIN"), true, action) }
        } }
    }

    @Test fun addRequiresAnExplicitValidPackageDate() {
        launch(StockAction.Purchase)
        compose.onNodeWithContentDescription("Due date (required)").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithText("Confirm add").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Due date (required)").performScrollTo().performTextInput("2027-01-01")
        compose.onNodeWithText("Confirm add").performScrollTo().assertIsEnabled()
    }

    @Test fun useDoesNotAskForPurchaseDateOrPrice() {
        launch(StockAction.Consume)
        compose.onNodeWithContentDescription("Due date (required)").assertDoesNotExist()
        compose.onNodeWithText("Add price").assertDoesNotExist()
        compose.onNodeWithText("Confirm use").performScrollTo().assertIsEnabled()
    }

    @Test fun anUncertainEarlierUseCannotBeSubmittedThroughAnotherScan() {
        launch(StockAction.Consume, listOf(PendingChange("uncertain", "POST", "/stock/products/7/consume", "needs-review", null, false)))
        compose.onNodeWithText("Confirm use").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Could not confirm this change. Check changes before trying again.").assertExists()
    }
}
