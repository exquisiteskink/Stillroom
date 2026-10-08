package app.stillroom

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Exercise the production Activity, preference storage, and custom motion recomposer. */
class MainActivityUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Before @After fun clearLocalPreferences() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("shell_preferences", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun activityLaunchesAndChildLandingSurvivesRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            compose.onNodeWithTag("page-title").assertTextEquals("Today")
            compose.onNodeWithContentDescription("Open settings").performClick()
            compose.onNodeWithContentDescription("Open Household first").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Dark theme").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Reduced motion").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Back").performClick()
            activity.recreate()
            compose.onNodeWithTag("page-title").assertTextEquals("Household").assertIsDisplayed()
        }
        // A fresh Activity creates a new ViewModel and must reload saved preferences.
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("page-title").assertTextEquals("Household").assertIsDisplayed()
            compose.onNodeWithContentDescription("Open settings").performClick()
            compose.onNodeWithContentDescription("Open Household first").performScrollTo().assertIsOn()
            compose.onNodeWithContentDescription("Dark theme").performScrollTo().assertIsSelected()
            compose.onNodeWithContentDescription("Reduced motion").performScrollTo().assertIsOn()
        }
    }
}
