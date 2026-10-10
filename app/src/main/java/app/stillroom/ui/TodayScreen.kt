package app.stillroom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.stillroom.domain.*
import kotlinx.serialization.json.JsonObject
import app.stillroom.R
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

@Composable fun TodayScreen(model:TodayViewModel,account:Account,open:(Section)->Unit,openRecipe:(Long)->Unit = {},openMeal:(Long)->Unit = {},openTasks:()->Unit = { open(Section.Household) }) {
    val state by model.state.collectAsState()
    LaunchedEffect(Unit){model.refresh()}
    val tasksEnabled=LocalServerCapabilities.current.enabled("TASKS") && HouseholdAccess.has(account.permissions,"TASKS")
    var choicesLoaded by remember(account.id) { mutableStateOf(false) }
    LaunchedEffect(state.busy,state.snapshot.account?.id,tasksEnabled) {
        if(tasksEnabled && !choicesLoaded && !state.busy && state.snapshot.account?.id==account.id) { choicesLoaded=true;model.loadTaskChoices() }
    }
    TodayContent(state,account,model::refresh,model::completeTask,open,openRecipe,openMeal,openTasks,model::openTaskEditor,model::reopenTask,model::loadCompletedTasks)
    if(state.editorOpen && HouseholdAccess.has(account.permissions,"MASTER_DATA_EDIT")) {
        val self=kotlinx.serialization.json.buildJsonObject { put("id",kotlinx.serialization.json.JsonPrimitive(account.userId));put("username",kotlinx.serialization.json.JsonPrimitive(account.username)) }
        TaskEditor(null,state.taskChoices.categories,listOf(self),state.busy,model::closeTaskEditor,model::addTask,
            onCreateCategory=model::createTaskCategory,createdCategoryId=state.createdCategory,categoryError=state.error)
    }
}

@Composable internal fun TodayContent(state: TodayUiState, account: Account, refresh: () -> Unit, completeTask: (Long) -> Unit, open: (Section) -> Unit, openRecipe: (Long) -> Unit = {}, openMeal: (Long) -> Unit = {}, openTasks: () -> Unit = { open(Section.Household) }, addTask:(()->Unit)?=null,reopenTask:(Long)->Unit={},loadCompleted:()->Unit={}) {
    val capabilities=LocalServerCapabilities.current
    var completed by rememberSaveable(account.id.value) { mutableStateOf(false) }
    var category by rememberSaveable(account.id.value) { mutableStateOf<Long?>(null) }
    val s=state.snapshot
    val today=remember { LocalDate.now() }
    val greeting=remember(today) {
        val weekday=today.dayOfWeek.getDisplayName(TextStyle.FULL,Locale.getDefault())
        val month=today.month.getDisplayName(TextStyle.FULL,Locale.getDefault())
        "$weekday, $month ${today.dayOfMonth}"
    }
    val whisper=when {
        s.accessDenied->"This account cannot see household records until access is verified again."
        state.error!=null->null
        s.missing.isNotEmpty() && !state.busy->"Some records are unavailable. Refresh when you’re online."
        else->null
    }
    KitchenList(state.busy, refresh) {
        item {
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                val hour = LocalTime.now().hour
                Text(when { hour < 12 -> "Good morning"; hour < 17 -> "Good afternoon"; else -> "Good evening" },
                    style=MaterialTheme.typography.displaySmall, color=MaterialTheme.colorScheme.onSurface)
                Text(greeting, style=MaterialTheme.typography.bodyLarge, color=MaterialTheme.colorScheme.onSurfaceVariant)
                KitchenWhisper(whisper)
                KitchenError(state.error)
            }
        }
        item { TodayPhoto() }
        if(capabilities.enabled("TASKS") && HouseholdAccess.has(account.permissions,"TASKS")) {
            item { KitchenSectionTitle("Tasks") }
            item {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(addTask!=null && HouseholdAccess.has(account.permissions,"MASTER_DATA_EDIT"))SecondaryButton(onClick=addTask,enabled=!state.busy){Text("Add task")}
                    QuietButton(onClick=openTasks) { Text("Manage tasks") }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(!completed,{completed=false},{Text("Open tasks")})
                    FilterChip(completed,{completed=true;loadCompleted()},{Text("Completed tasks")},enabled=!state.busy)
                }
                val available=(if(s.account?.id==account.id && !s.accessDenied)s.tasks+s.completedTasks else emptyList()).mapNotNull { it.houseId("category_id") }.distinct()
                if(available.isNotEmpty())Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(category==null,{category=null},{Text("All categories")})
                    available.forEach { id -> FilterChip(category==id,{category=id},{Text(state.taskChoices.categories.firstOrNull { it.houseId("id")==id }?.houseText("name") ?: (s.tasks+s.completedTasks).firstOrNull { it.houseId("category_id")==id }?.let { (it["category"] as? JsonObject)?.houseText("name") }?.takeIf { it.isNotBlank() } ?: "Category $id")}) }
                }
            }
            val sameAccount=s.account?.id==account.id && !s.accessDenied
            val tasks=if(sameAccount) tasksForUser(if(completed)s.completedTasks else s.tasks,account,completed).filter { category==null || it.houseId("category_id")==category } else emptyList()
            if(tasks.isEmpty()) item {
                Text(when {
                    state.busy -> "Loading tasks…"
                    !sameAccount || "/tasks" in s.missing || s.accessDenied -> "Tasks unavailable"
                    else -> if(completed)"No completed tasks for you or everyone" else "No open tasks for you or everyone"
                }, color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(tasks,key={ "task-${it.houseText("id")}" }) { task ->
                TodayTaskRow(task,account,s.taskOperations,state.busy,if(completed)reopenTask else completeTask,completed)
            }
        }
        if(capabilities.enabled("CHORES") && HouseholdAccess.has(account.permissions,"CHORES")) {
            item { KitchenSectionTitle("Due chores") }
            if(s.chores.isEmpty()) item {
                KitchenCard { Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(if (state.busy) "Loading chores…" else if (s.account == null || s.missing.any { it.contains("chores") } || s.accessDenied) "Chores unavailable" else "No chores due today", style=MaterialTheme.typography.titleMedium)
                    Text("View other chores in Household.", color=MaterialTheme.colorScheme.onSurfaceVariant)
                    QuietButton(onClick={open(Section.Household)}){Text("View chores")}
                } }
            } else items(s.chores, key={ it.catalogText("chore_id")+it.catalogText("chore_name") }) { chore ->
                KitchenCard(onClick={open(Section.Household)}) {
                    Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(chore.catalogText("chore_name"), style=MaterialTheme.typography.titleMedium)
                        QuantityBadge(houseDueDay(chore.catalogText("next_estimated_execution_time"), Locale.getDefault()) ?: "Due", ColorTone.Due)
                    }
                }
            }
        }
        if(capabilities.enabled("RECIPES") && capabilities.enabled("RECIPES_MEALPLAN") && RecipeAccess.mealPlan(account.permissions)) {
            item { KitchenSectionTitle("Planned meals") }
            if(s.meals.isEmpty()) item {
                KitchenCard { Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(if (state.busy) "Loading meals…" else if (s.account == null || s.missing.any { it.contains("meal_plan") || it.contains("recipes") } || s.accessDenied) "Meal plan unavailable" else "No meals planned today", style=MaterialTheme.typography.titleMedium)
                    Text("Plan a meal in Meals.", color=MaterialTheme.colorScheme.onSurfaceVariant)
                    QuietButton(onClick={open(Section.Meals)}){Text("View meals")}
                } }
            } else items(s.meals, key={ it.catalogText("id")+it.catalogText("recipe_name") }) { row ->
                KitchenCard(onClick={
                    when (row.catalogText("type")) {
                        "recipe" -> row.catalogId("recipe_id")?.let(openRecipe) ?: open(Section.Meals)
                        else -> row.catalogId("id")?.let(openMeal) ?: open(Section.Meals)
                    }
                }) {
                    Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(row.catalogText("section_name").ifBlank { "Meal" }, style=MaterialTheme.typography.labelLarge, color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(row.catalogText("recipe_name").ifBlank { row.catalogText("note").ifBlank { "Planned product" } }, style=MaterialTheme.typography.titleMedium)
                        val servings=row.catalogText("recipe_servings").takeIf { row.catalogText("type")=="recipe" }
                        if(!servings.isNullOrBlank()) Text("$servings servings", color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable internal fun TodayTaskRow(task: JsonObject, account: Account, operations: List<PendingChange>, busy: Boolean, complete: (Long) -> Unit, completed:Boolean=false) {
    val id=task.houseId("id") ?: return
    val waiting=operations.any { it.path in setOf("/tasks/$id/complete","/tasks/$id/undo") && it.state in setOf("pending","in-flight","needs-review","guarded") }
    val enabled=HouseholdAccess.has(account.permissions,if(completed)"TASKS_UNDO_EXECUTION" else "TASKS_MARK_COMPLETED") && !busy && !waiting
    val assigned=task.houseId("assigned_to_user_id") ?: (task["assigned_to_user"] as? JsonObject)?.houseId("id")
    KitchenCheckRow(name=task.houseText("name"),checked=completed,waiting=waiting,enabled=enabled,
        onCheckedChange={ checked -> if(checked!=completed && enabled) complete(id) },
        due=houseDueDay(task.houseText("due_date"),Locale.getDefault()),
        detail=listOfNotNull(if(completed) "Uncheck to reopen" else null,if(assigned==account.userId) "For you" else "Everyone", task.houseText("description").takeIf { it.isNotBlank() }).joinToString("\n"))
}

/** Welcoming time-of-day photography; household meal records remain separately labeled. */
@Composable internal fun TodayPhoto() {
    val hour = LocalTime.now().hour
    val photo = when { hour < 11 -> R.drawable.today_breakfast; hour < 17 -> R.drawable.today_lunch; else -> R.drawable.today_dinner }
    Image(painterResource(photo), contentDescription = null, contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxWidth().height(200.dp).clip(MaterialTheme.shapes.large))
}
