package app.stillroom

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import app.stillroom.data.AndroidShellPreferencesRepository
import app.stillroom.data.LocalAppInfoRepository
import app.stillroom.domain.*
import app.stillroom.fractions.QuantityFormatter
import app.stillroom.fractions.QuantityStyle
import app.stillroom.ui.*
import java.math.BigDecimal
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Host-side checks for the Settings quantity style; not phone or emulator tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class QuantityStyleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun defaultIsFractionsAndTheChoicePersists() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val repository = AndroidShellPreferencesRepository(context)
        assertEquals(QuantityStyle.Fractions, repository.load().quantityStyle)
        val model = ShellViewModel(GetAppName(LocalAppInfoRepository()), ManageShellPreferences(repository))
        model.setQuantityStyle(QuantityStyle.Decimals)
        assertEquals(QuantityStyle.Decimals, model.state.value.preferences.quantityStyle)
        assertEquals(QuantityStyle.Decimals, AndroidShellPreferencesRepository(context).load().quantityStyle)
    }

    @Test fun settingsRadioSwitchesTheStyle() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val model = ShellViewModel(GetAppName(LocalAppInfoRepository()), ManageShellPreferences(AndroidShellPreferencesRepository(context)))
        model.openPage(ShellPage.Settings)
        compose.setContent { StillroomTheme { StillroomShell(model.state.collectAsStateValue(), model) } }
        compose.onNodeWithContentDescription("Show quantities as decimals").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(QuantityStyle.Decimals, model.state.value.preferences.quantityStyle) }
        compose.onNodeWithContentDescription("Show quantities as fractions").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(QuantityStyle.Fractions, model.state.value.preferences.quantityStyle) }
    }

    @Test fun decimalStyleChangesOnlyTheDisplayNeverTheSavedAmount() {
        val snapshot = ShoppingSnapshot(mapOf(
            "products" to Json.parseToJsonElement("""[{"id":3,"name":"Flour","qu_id_stock":1}]""").jsonArray,
            "quantity_units" to Json.parseToJsonElement("""[{"id":1,"name":"Cup"}]""").jsonArray,
        ), false)
        val row = buildJsonObject {
            put("id", 7); put("shopping_list_id", 1); put("product_id", 3)
            put("amount", Json.parseToJsonElement("0.3333333")); put("qu_id", 1); put("note", "Original"); put("done", 0)
        }
        var saved: ShoppingDraft? = null
        compose.setContent { StillroomTheme { CompositionLocalProvider(LocalQuantityFormatter provides QuantityFormatter(QuantityStyle.Decimals)) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ShoppingItemForm(1, snapshot, row, null, false, {}) { saved = it }
            }
        } } }
        compose.onNodeWithText("0.333").assertExists()
        compose.onNodeWithContentDescription("Notes").performTextReplacement("Changed note")
        compose.onNodeWithText("Save item").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, BigDecimal("0.3333333").compareTo(saved!!.amount)) }
    }

    @Test fun fractionStyleIsTheDefaultDisplay() {
        compose.setContent { StillroomTheme { androidx.compose.material3.Text(quantityText(BigDecimal("1.5"))) } }
        compose.onNodeWithText("1½").assertExists()
    }
}

@androidx.compose.runtime.Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateValue(): T = collectAsState().value
