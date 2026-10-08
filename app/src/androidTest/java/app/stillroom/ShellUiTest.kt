package app.stillroom

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.stillroom.data.LocalAppInfoRepository
import app.stillroom.domain.*
import app.stillroom.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Real Android Compose tests; every scenario runs in both fixed light and dark themes. */
@RunWith(Parameterized::class)
class ShellUiTest(private val theme: ThemeChoice) {
    @get:Rule val compose = createComposeRule()
    private var preferences = ShellPreferences(theme = theme)
    private val repository = object : ShellPreferencesRepository {
        override fun load() = preferences
        override fun save(preferences: ShellPreferences) { this@ShellUiTest.preferences = preferences }
    }
    private val model = ShellViewModel(GetAppName(LocalAppInfoRepository()), ManageShellPreferences(repository))
    private var tablet = false

    private fun launch() {
        compose.setContent {
            val state by model.state.collectAsState()
            tablet = LocalConfiguration.current.screenWidthDp >= 600
            val arguments = InstrumentationRegistry.getArguments()
            arguments.getString("expectedWidthClass")?.let {
                assertEquals("Actual device width class", it == "tablet", tablet)
            }
            arguments.getString("expectedFontScale")?.let {
                assertEquals("Actual device font scale", it.toFloat(), LocalConfiguration.current.fontScale, 0.01f)
            }
            StillroomTheme(state.preferences.theme, state.preferences.dynamicColor, state.preferences.reducedMotion) {
                StillroomShell(state, model)
            }
        }
    }
    private fun navigate(section: Section) {
        val node = compose.onNodeWithContentDescription("Go to ${section.name}")
        if (compose.onAllNodesWithTag("navigation-scroll").fetchSemanticsNodes().isNotEmpty()) node.performScrollTo()
        node.assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
        node.assertIsSelected()
    }
    private fun setting(description: String) =
        compose.onNodeWithContentDescription(description).performScrollTo()

    @Test fun everyDestinationIsAccessibleAndDisconnected() {
        launch()
        compose.onNodeWithTag("page-title").assertTextEquals("Today")
        Section.entries.forEach { section ->
            navigate(section)
            compose.onNodeWithTag("page-title").assertTextEquals(section.name)
            compose.onNodeWithText(DisconnectedMessage).assertIsDisplayed()
        }
        // Verify the actual adaptive layout on the emulator's configured width.
        if (tablet) {
            compose.onNodeWithTag("navigation-sidebar").assertIsDisplayed().assertWidthIsEqualTo(240.dp)
            compose.onNodeWithTag("navigation-bottom").assertDoesNotExist()
        } else {
            compose.onNodeWithTag("navigation-sidebar").assertDoesNotExist()
        }
    }

    @Test fun realThemeBackgroundHasExpectedLuminance() {
        launch()
        val image = compose.onNodeWithTag("shell").captureToImage().toPixelMap()
        val luminance = image[0, 0].luminance()
        if (theme == ThemeChoice.Light) assertTrue("Light background: $luminance", luminance > 0.8f)
        else assertTrue("Dark background: $luminance", luminance < 0.1f)
    }

    @Test fun searchScannerAndBackAreNavigableWithoutAccount() {
        launch()
        navigate(Section.Pantry)
        compose.onNodeWithContentDescription("Open search").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("search-entry").performTextInput("apples")
        compose.onNodeWithTag("search-entry").assertTextContains("apples")
        compose.onNodeWithText(DisconnectedMessage).assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("page-title").assertTextEquals("Pantry")
        compose.onNodeWithContentDescription("Open scanner").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("page-title").assertTextEquals("Scan products")
        compose.onNodeWithText("$DisconnectedMessage Scanning will be available after connection.").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("page-title").assertTextEquals("Pantry")
    }

    @Test fun visibleSectionsAndChildLandingRespondToSettings() {
        launch()
        compose.onNodeWithContentDescription("Open settings").performClick()
        setting("Show Pantry").assertIsOn().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Go to Pantry").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open settings").performClick()
        setting("Open Household first").assertIsOff().performClick()
        setting("Show Household").assertIsOn().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("page-title").assertTextEquals("Household")
        compose.onNodeWithText(DisconnectedMessage).assertIsDisplayed()
    }

    @Test fun themeDynamicColorAndReducedMotionSettingsWork() {
        launch()
        compose.onNodeWithContentDescription("Open settings").performClick()
        val other = if (theme == ThemeChoice.Light) ThemeChoice.Dark else ThemeChoice.Light
        setting("${other.name} theme").performClick().assertIsSelected()
        setting("Dynamic color").assertIsOff().performClick().assertIsOn()
        setting("Reduced motion").assertIsOff().performClick().assertIsOn()
        compose.runOnIdle {
            assertEquals(other, preferences.theme)
            assertTrue(preferences.dynamicColor)
            assertTrue(preferences.reducedMotion)
        }
    }

    @Test fun sharedStatesRenderAndReducedMotionHasNoSpinner() {
        var retries = 0
        compose.setContent {
            StillroomTheme(theme, reducedMotion = true) {
                Surface {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        LoadingState()
                        EmptyState()
                        ErrorState("Synthetic test error", onRetry = { retries++ })
                        OfflineState()
                        PermissionDeniedState()
                    }
                }
            }
        }
        compose.onNodeWithTag("state-loading").assertIsDisplayed()
        compose.onNodeWithTag("loading-indicator").assertDoesNotExist()
        compose.onNodeWithText(DisconnectedMessage).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Reload").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithText("Offline").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Access unavailable").performScrollTo().assertIsDisplayed()
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun themes() = listOf(arrayOf(ThemeChoice.Light), arrayOf(ThemeChoice.Dark))
    }
}
