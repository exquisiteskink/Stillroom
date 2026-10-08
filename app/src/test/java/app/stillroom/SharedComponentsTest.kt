package app.stillroom

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.stillroom.ui.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SharedComponentsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fieldKeepsVisibleLabelAndNamedEditableInput() {
        var input = ""
        compose.setContent { StillroomTheme { LabeledTextField(input, { input = it }, "Quantity", modifier = Modifier.testTag("quantity")) } }
        compose.onNodeWithText("Quantity").assertIsDisplayed()
        compose.onNodeWithTag("quantity").assertContentDescriptionEquals("Quantity").performTextInput("2")
        assertEquals("2", input)
    }

    @Test fun namedCheckRowHasOneToggleAndPendingDoesNotClaimCompletion() {
        var clicks = 0
        compose.setContent { StillroomTheme { KitchenCheckRow("Wash dishes", checked = true, onCheckedChange = { clicks++ }, waiting = true) } }
        val checkbox = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        checkbox.assertIsOff().assertIsNotEnabled()
        compose.onNodeWithText("Wash dishes").assertIsDisplayed()
        assertEquals(0, clicks)
    }

    @Test fun reducedMotionPreservesActivityStatus() {
        compose.setContent { StillroomTheme(reducedMotion = true) { SyncSpinner(true) } }
        compose.onNodeWithText("Syncing…").assertIsDisplayed()
    }

    @Test fun choiceSearchFiltersExistingOptionsAndChoosesIdentity() {
        var picked: Long? = null
        compose.setContent { StillroomTheme { ChoiceField("Product", listOf(1L to "Milk", 2L to "Bread"), picked, { picked = it }) } }
        compose.onNodeWithContentDescription("Product: Choose").performClick()
        compose.onNodeWithContentDescription("Search product").performTextInput("Bre")
        compose.onNodeWithText("Milk").assertDoesNotExist()
        compose.onNodeWithText("Bread").performClick()
        assertEquals(2L, picked)
    }
}
