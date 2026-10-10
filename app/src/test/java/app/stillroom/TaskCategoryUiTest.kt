package app.stillroom

import androidx.compose.runtime.*
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
class TaskCategoryUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun addingTaskCanCreateAndSelectCategoryWithoutLosingDraft()=createCategory(null)
    @Test fun editingTaskCanCreateAndSelectCategoryWithoutLosingDraft()=createCategory(buildJsonObject {
        put("id",3);put("name","Existing task");put("description","Original notes");put("category_id",2);put("assigned_to_user_id",4)
    })
    private fun createCategory(row:JsonObject?) {
        var saved:JsonObject?=null
        var created:String?=null
        var categories by mutableStateOf(listOf(buildJsonObject { put("id",2);put("name","Original category") }))
        var categoryId by mutableStateOf<Long?>(null)
        compose.setContent { StillroomTheme {
            TaskEditor(row,categories,emptyList(),false,{}, { saved=it },onCreateCategory={ name ->
                created=name
                categories=categories+buildJsonObject { put("id",12);put("name",name) }
                categoryId=12
            },createdCategoryId=categoryId)
        } }
        compose.onNodeWithContentDescription("Name").performTextReplacement("My task draft")
        compose.onNodeWithContentDescription("Notes").performTextReplacement("Keep these notes")
        compose.onNodeWithText("New category").performScrollTo().performClick()
        compose.onNodeWithText("Create category").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Category name").performTextReplacement(" Cleaning ")
        compose.onNodeWithText("Create category").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Task category: Cleaning").assertExists()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals("Cleaning",created)
            assertEquals("My task draft",saved!!.houseText("name"))
            assertEquals("Keep these notes",saved!!.houseText("description"))
            assertEquals(12L,saved!!.houseId("category_id"))
            if(row!=null) assertEquals(4L,saved!!.houseId("assigned_to_user_id"))
        }
    }
    @Test fun unconfirmedCategoryKeepsDraftAndBlocksTaskSave() {
        compose.setContent { StillroomTheme {
            TaskEditor(null,emptyList(),emptyList(),false,{}, {},onCreateCategory={},categoryError="Category waiting for confirmation")
        } }
        compose.onNodeWithContentDescription("Name").performTextReplacement("Draft")
        compose.onNodeWithText("New category").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Category name").performTextReplacement("Cleaning")
        compose.onNodeWithText("Create category").performScrollTo().performClick()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Category waiting for confirmation").assertExists()
    }
}
