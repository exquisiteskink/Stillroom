package app.stillroom

import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.stillroom.domain.*
import app.stillroom.ui.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ShellAccountIsolationTest {
    @get:Rule val compose = createComposeRule()
    private fun account(user: Long): Account {
        val address = ServerAddress.parse("example.org")
        return Account(AccountId.of(address, user), address, user, "User $user", "4.7.1", setOf("ADMIN"))
    }

    @Test fun rememberedFeatureFormsResetOnAccountSwitch() {
        var active by mutableStateOf(account(1))
        val preferences = object : ShellPreferencesRepository {
            override fun load() = ShellPreferences()
            override fun save(preferences: ShellPreferences) = Unit
        }
        val shell = ShellViewModel(GetAppName(AppInfoRepository { "Stillroom" }), ManageShellPreferences(preferences))
        compose.setContent { StillroomTheme {
            StillroomShell(ShellUiState(ShellPreferences(), page = ShellPage.PendingChanges), shell,
                AccountUiState(AccountState(listOf(active), active))) {
                // Feature forms remember quantities, locations and notes under this same shell boundary.
                var draft by remember { mutableStateOf("") }
                OutlinedTextField(draft, { draft = it }, label = { androidx.compose.material3.Text("Draft") })
            }
        } }
        compose.onNodeWithText("Draft").performTextInput("Account one's private draft")
        compose.runOnIdle { active = account(2) }
        compose.onNodeWithText("Account one's private draft").assertDoesNotExist()
    }

    @Test fun connectedFallbackDoesNotClaimImplementedChoresAreMissing() {
        compose.setContent { StillroomTheme { ConnectedPlaceholder(account(3).copy(permissions = setOf("CHORES"))) } }
        compose.onNodeWithText("Chores are not available in Stillroom yet.").assertDoesNotExist()
        compose.onNodeWithTag("permission-CHORES").assertExists()
    }
}
