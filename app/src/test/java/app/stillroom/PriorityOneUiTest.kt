package app.stillroom

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
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
@Config(sdk=[35],application=android.app.Application::class)
class PriorityOneUiTest {
    @get:Rule val compose=createComposeRule()
    private val address=ServerAddress.parse("example.org")
    private val account=Account(AccountId.of(address,4),address,4,"Member","4.7.1",setOf("TASKS","MASTER_DATA_EDIT","TASKS_UNDO_EXECUTION"))
    private fun task(id:Long,name:String,category:Long,done:Boolean=false)=buildJsonObject {
        put("id",id);put("name",name);put("category_id",category);put("done",if(done)1 else 0);put("assigned_to_user_id",4)
    }
    @Test fun todayAddsTasksAndFiltersByCategory() {
        var adds=0
        val snapshot=TodaySnapshot(account=account,tasks=listOf(task(1,"Kitchen task",7),task(2,"Garden task",8)))
        compose.setContent { StillroomTheme { TodayContent(TodayUiState(snapshot),account,{},{},{},addTask={adds++}) } }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Add task"));compose.onNodeWithText("Add task").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Category 7"))
        compose.onNodeWithText("Category 7").performClick()
        compose.onNodeWithText("Category 7").assertIsSelected()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Kitchen task"))
        compose.onNodeWithText("Kitchen task").assertExists();compose.onNodeWithText("Garden task").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1,adds) }
    }
    @Test fun completedPersonalTasksCanBeReopenedAndPendingUndoBlocksRepeat() {
        var reopened=0L
        val row=task(4,"Finished task",7,true)
        compose.setContent { StillroomTheme { TodayContent(TodayUiState(TodaySnapshot(account=account,completedTasks=listOf(row))),account,{},{},{},reopenTask={reopened=it}) } }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Completed tasks"));compose.onNodeWithText("Completed tasks").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Finished task"))
        compose.onNodeWithText("Finished task").performClick();compose.runOnIdle { assertEquals(4L,reopened) }
    }
    @Test fun pendingUndoDisablesTheCompletedCheckbox() {
        compose.setContent { StillroomTheme { TodayTaskRow(task(4,"Finished task",7,true),account,
            listOf(PendingChange("undo","POST","/tasks/4/undo","guarded",null,false)),false,{fail("Duplicate undo")},completed=true) } }
        compose.onNodeWithText("Finished task").assertIsNotEnabled()
    }
    private fun line(state:String="draft")=TripLine("line","Milk",2,StockBooking(StockAction.Purchase,1,BigDecimal("0.50001"),date="2027-01-01"),state)
    @Test fun tripDraftUsesReviewConfirmationAndUnknownOutcomeLocksFurtherSubmission() {
        var submits=0
        compose.setContent { StillroomTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            ShoppingTripReview(ShoppingTrip("trip",listOf(line())),StockUiState(),false,{_,_,_,_->},{},{submits++},{},{},{})
        } } }
        compose.onNodeWithText("Review 1 scanned items").performClick()
        compose.onNodeWithText("Confirm 1 purchases").performClick();compose.runOnIdle { assertEquals(1,submits) }
        compose.onNodeWithText("Edit Milk").assertExists()
    }
    @Test fun uncertainTripOffersInspectionAndCannotStartAnotherOrConfirmAgain() {
        compose.setContent { StillroomTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            ShoppingTripReview(ShoppingTrip("trip",listOf(line("needs-review")),true),StockUiState(),false,{_,_,_,_->},{},{fail("Repeated unknown purchase")},{},{},{})
        } } }
        compose.onNodeWithText("Continue reviewed purchases").assertIsNotEnabled()
        compose.onNodeWithText("Check changes").assertExists()
        compose.onNodeWithText("Start another trip").assertDoesNotExist();compose.onNodeWithText("Edit Milk").assertDoesNotExist()
    }
    @Test fun editingTripPreservesUnchangedExactDecimal() {
        var saved:BigDecimal?=null
        compose.setContent { StillroomTheme { TripLineEditor(line(),false,{}) { amount,_,_->saved=amount } } }
        compose.onNodeWithText("Save").performClick();compose.runOnIdle { assertEquals(0,BigDecimal("0.50001").compareTo(saved)) }
    }
}
