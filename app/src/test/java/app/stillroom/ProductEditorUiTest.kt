package app.stillroom

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.stillroom.domain.*
import app.stillroom.ui.CatalogEditor
import app.stillroom.ui.StillroomTheme
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Host Compose checks of the shared record editor; not phone or emulator tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ProductEditorUiTest {
    @get:Rule val compose = createComposeRule()

    private val snapshot = CatalogSnapshot(mapOf(
        "userfields" to Json.parseToJsonElement("""[
            {"entity":"products","name":"brand","caption":"Brand","type":"text-single-line"},
            {"entity":"products","name":"organic","caption":"Organic","type":"checkbox"},
            {"entity":"products","name":"label","caption":"Label photo","type":"image"}
        ]""").jsonArray.map { it.jsonObject },
        "quantity_units" to listOf(buildJsonObject { put("id", 3); put("name", "Piece") }),
    ))
    private val row = buildJsonObject {
        put("id", 9); put("name", "Milk"); put("location_id", 2); put("qu_id_stock", 3); put("qu_id_purchase", 3); put("qu_id_consume", 3); put("qu_id_price", 3)
        put("userfields", buildJsonObject { put("brand", "Old"); put("organic", "0"); put("label", "eA==_bGFiZWwuanBn") })
    }

    @Test fun editsUserfieldsWithTypedInputsAndSendsOnlyChanges() {
        var saved: Pair<JsonObject, JsonObject>? = null
        compose.setContent { StillroomTheme { CatalogEditor(CatalogEntity.Products, row, snapshot, false, null, {}) { f, u, _ -> saved = f to u } } }
        compose.onNodeWithContentDescription("Brand").performScrollTo().performTextReplacement("Oatly")
        compose.onNodeWithText("Organic").performScrollTo().performClick()
        compose.onNodeWithText("Image: label.jpg").performScrollTo().assertExists()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals(JsonObject(emptyMap()), saved!!.first)
            assertEquals(buildJsonObject { put("brand", "Oatly"); put("organic", "1") }, saved!!.second)
        }
    }

    @Test fun failedSaveShowsGrocysReasonAndKeepsEntries() {
        compose.setContent { StillroomTheme { CatalogEditor(CatalogEntity.Products, null, snapshot, false, CatalogSaveOutcome.Failed("A product with this name already exists in Grocy."), {}) { _, _, _ -> } } }
        compose.onNodeWithText("A product with this name already exists in Grocy.").performScrollTo().assertExists()
        compose.onNodeWithText("Add product").assertExists()
    }
}
