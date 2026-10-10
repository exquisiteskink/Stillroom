package app.stillroom

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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], application=android.app.Application::class)
class TodayTasksUiTest {
    @get:Rule val compose=createComposeRule()
    private val address=ServerAddress.parse("example.org")
    private val account=Account(AccountId.of(address,4),address,4,"Member","4.7.1",setOf("TASKS","TASKS_MARK_COMPLETED"))
    private fun task(id:Long,name:String,assigned:Long?=4)=buildJsonObject {
        put("id",id);put("name",name);put("done",0);put("assigned_to_user_id",assigned?.let { JsonPrimitive(it) } ?: JsonNull)
    }
    @Test fun homepageFiltersOtherUsersAndCompletesTheSelectedTask() {
        var completed:Long?=null
        val snapshot=TodaySnapshot(account=account,tasks=listOf(task(1,"Mine"),task(2,"Everyone task",null),task(3,"Other",5)))
        compose.setContent { StillroomTheme { TodayContent(TodayUiState(snapshot),account,{}, { completed=it },{}) } }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Mine"))
        compose.onNodeWithText("Mine").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Everyone task"))
        compose.onNodeWithText("Everyone task").assertExists()
        compose.onNodeWithText("Other").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1L,completed) }
    }
    @Test fun pendingTaskIsDisabledAndShowsSyncStatus() {
        val operation=PendingChange("test","POST","/tasks/1/complete","pending",null,false)
        compose.setContent { StillroomTheme { TodayTaskRow(task(1,"Mine"),account,listOf(operation),false,{ fail("Duplicate completion") }) } }
        compose.onNodeWithText("Mine").assertIsNotEnabled()
        compose.onNodeWithText("Waiting to sync").assertExists()
    }
    @Test fun readOnlyTaskCheckboxIsDisabled() {
        compose.setContent { StillroomTheme { TodayTaskRow(task(1,"Mine"),account.copy(permissions=setOf("TASKS")),emptyList(),false,{ fail("Read-only completion") }) } }
        compose.onNodeWithText("Mine").assertIsNotEnabled()
    }
    @Test fun unknownTaskPermissionsHideTheSection() {
        compose.setContent { StillroomTheme { TodayContent(TodayUiState(),account.copy(permissions=null),{},{},{}) } }
        compose.onNodeWithText("Tasks").assertDoesNotExist()
    }
    @Test fun anotherAccountSnapshotIsHiddenUntilCurrentAccountLoads() {
        val otherAddress=ServerAddress.parse("other.example.org")
        val other=account.copy(id=AccountId.of(otherAddress,4),address=otherAddress)
        val snapshot=TodaySnapshot(account=other,tasks=listOf(task(1,"Mine")))
        compose.setContent { StillroomTheme { TodayContent(TodayUiState(snapshot),account,{},{},{}) } }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Tasks unavailable"))
        compose.onNodeWithText("Mine").assertDoesNotExist()
    }

}
