package app.stillroom

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import app.stillroom.data.*
import app.stillroom.domain.*
import app.stillroom.ui.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class AccountUiTest(private val theme: ThemeChoice) {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture get() = LiveFixtures.read(context)[0]
    private val viewModels = ViewModelStore()
    private lateinit var repo: AndroidAccountsRepository
    private lateinit var accounts: AccountViewModel

    private fun launch(api: GrocyApi = GrocyApi()) {
        repo = AndroidAccountsRepository(context, api, "stage3_ui_${theme.name}")
        runBlocking { repo.state.value.accounts.toList().forEach { repo.logout(it.id) } }
        accounts = AccountViewModel(ManageAccounts(repo))
        viewModels.put("accounts", accounts)
        val preferenceRepo = object : ShellPreferencesRepository {
            var value = ShellPreferences(theme = theme)
            override fun load() = value
            override fun save(preferences: ShellPreferences) { value = preferences }
        }
        val shell = ShellViewModel(GetAppName(LocalAppInfoRepository()), ManageShellPreferences(preferenceRepo))
        compose.setContent {
            val shellState by shell.state.collectAsState()
            val accountState by accounts.state.collectAsState()
            StillroomTheme(theme) { StillroomShell(shellState, shell, accountState, accounts) }
        }
    }

    @After fun clear() {
        viewModels.clear()
        if (::repo.isInitialized) runBlocking { repo.state.value.accounts.toList().forEach { repo.logout(it.id) } }
    }

    private fun openAccounts() = compose.onNodeWithContentDescription("Open accounts").assertHeightIsAtLeast(48.dp).performClick()
    private fun waitForUser(role: String) {
        compose.waitUntil(30_000) { repo.state.value.active?.userId == fixture.getLong(role + "_user_id") && !accounts.state.value.busy }
        compose.waitForIdle()
    }
    private fun typeKey(value: String) {
        compose.onNodeWithTag("api-key").performScrollTo().performTextInput(value)
        compose.onNodeWithTag("api-key").performImeAction()
    }
    private fun connect(role: String, verifier: Boolean = false) {
        if (compose.onAllNodesWithTag("server-url").fetchSemanticsNodes().isEmpty()) compose.onNodeWithText("Add account").performScrollTo().performClick()
        compose.onNodeWithTag("server-url").performScrollTo().performTextClearance()
        compose.onNodeWithTag("server-url").performTextInput(fixture.getString("base_url"))
        typeKey(fixture.getString(role + "_key"))
        compose.onNodeWithContentDescription("Allow insecure HTTP").performScrollTo().assertIsOff().performClick()
        if (verifier) compose.onNodeWithContentDescription("Verify permissions with ${fixture.getString("parent_username")}").performScrollTo().performClick()
        compose.onNodeWithTag("connect-account").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        waitForUser(role)
    }

    @Test fun parentAndChildUseSeparateKeysAndChildLandsOnExactGrantedPermissions() {
        launch()
        openAccounts()
        connect("parent")
        compose.onNodeWithTag("page-title").assertTextEquals("Today")
        compose.onNodeWithText("Connected as ${fixture.getString("parent_username")}").assertIsDisplayed()
        openAccounts()
        connect("child", verifier = true)
        compose.onNodeWithTag("page-title").assertTextEquals("Household")
        compose.onNodeWithTag("permission-CHORES").assertIsDisplayed()
        compose.onNodeWithTag("permission-CHORE_TRACK_EXECUTION").assertIsDisplayed()
        compose.onNodeWithTag("permission-ADMIN").assertDoesNotExist()
        compose.onNodeWithTag("permission-CHORE_UNDO_EXECUTION").assertDoesNotExist()
        compose.onNodeWithContentDescription("Go to Pantry").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open scanner").assertDoesNotExist()
        openAccounts()
        compose.onNodeWithContentDescription("Log out ${fixture.getString("child_username")}").performScrollTo().performClick()
        compose.onNodeWithTag("confirm-logout").performClick()
        compose.waitUntil(30_000) { !accounts.state.value.busy && repo.state.value.active == null }
        compose.onNodeWithContentDescription("Use account ${fixture.getString("parent_username")}").performScrollTo().performClick()
        waitForUser("parent")
        compose.onNodeWithTag("page-title").assertTextEquals("Today")
    }

    @Test fun httpRequiresExplicitOptInAndBadKeyIsRejected() {
        launch()
        openAccounts()
        compose.onNodeWithContentDescription("Allow insecure HTTP").assertIsOff()
        compose.onNodeWithTag("server-url").performTextInput(fixture.getString("base_url"))
        typeKey("stillroom-invalid-test-key")
        compose.onNodeWithTag("connect-account").performScrollTo().performClick()
        compose.waitUntil(10_000) { accounts.state.value.error != null }
        compose.onNodeWithTag("connection-error").assertTextContains("HTTPS is required", substring = true)
        assertTrue(repo.state.value.accounts.isEmpty())
        compose.onNodeWithTag("api-key").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithContentDescription("Allow insecure HTTP").performScrollTo().performClick()
        typeKey("stillroom-invalid-test-key")
        compose.onNodeWithTag("connect-account").performScrollTo().performClick()
        compose.waitUntil(30_000) { accounts.state.value.error == "The server rejected the API key." }
        compose.onNodeWithTag("connection-error").assertTextEquals("The server rejected the API key.")
        assertNull(repo.state.value.active)
    }

    @Test fun unknownVersionDisplaysTheCompatibilityMatrixMismatch() {
        val real = UrlConnectionGrocyTransport()
        launch(GrocyApi(object : GrocyTransport {
            override suspend fun get(address: ServerAddress, key: String, path: String): String {
                val response = real.get(address, key, path)
                return if (path == "/system/info") JSONObject(response).apply {
                    getJSONObject("grocy_version").put("Version", "99.0.0")
                }.toString() else response
            }
        }))
        runBlocking { repo.connect(ServerAddress.parse(fixture.getString("base_url"), true), fixture.getString("parent_key")) }
        compose.onNodeWithTag("version-warning").assertTextContains("Version mismatch: Grocy 99.0.0", substring = true)
        compose.onNodeWithTag("version-warning").assertTextContains("4.7.1 and 4.6.0", substring = true)
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun themes() = listOf(arrayOf(ThemeChoice.Light), arrayOf(ThemeChoice.Dark))
    }
}
