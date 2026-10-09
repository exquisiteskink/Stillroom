package app.stillroom.ui

import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.stillroom.domain.*
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.util.Locale

@Composable
fun HouseholdChoresScreen(model: HouseholdViewModel, account: Account) {
    val state by model.state.collectAsState()
    val parent = HouseholdAccess.parent(account.permissions)
    var editing by remember { mutableStateOf<JsonObject?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { model.refresh() }
    val locale = Locale.getDefault()
    val today = remember { LocalDate.now() }
    val groups = choreGroups(state.snapshot.chores, if (parent) null else account.userId, today)
    KitchenList(state.busy, model::refresh, spacedBy = 0.dp) {
        item {
            Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(if (parent) "Chores" else "My chores", style = MaterialTheme.typography.headlineSmall)
                KitchenWhisper(if (state.snapshot.stale) "Showing the last chore list." else null)
                KitchenError(state.error)
                if (parent) PrimaryButton(onClick = { creating = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Add chore") }
                if (!HouseholdAccess.has(account.permissions, "CHORES")) Text("This account cannot see chores.", color = MaterialTheme.colorScheme.error)
            }
        }
        if (groups.values.all { it.isEmpty() }) item {
            when {
                !HouseholdAccess.has(account.permissions,"CHORES") -> Text("Chore access is unavailable.")
                state.busy -> Text("Loading chores…")
                state.error != null -> ErrorState(state.error!!, model::refresh)
                else -> Text(if(parent) "No chores yet." else "No assigned chores.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        groups.filterValues { it.isNotEmpty() }.forEach { (group, rows) ->
            item { KitchenSectionTitle(group, Modifier.padding(top = 8.dp)) }
            items(rows, key = { it.houseText("chore_id") + it.houseText("chore_name") }) { row ->
                val id = row.houseId("chore_id")!!
                val waiting = state.operations.any { it.path == "/chores/$id/execute" && it.state in setOf("pending", "in-flight", "needs-review", "guarded") }
                val canComplete = HouseholdAccess.has(account.permissions, "CHORE_TRACK_EXECUTION")
                val assigned = row["next_execution_assigned_user"] as? JsonObject
                KitchenCheckRow(
                    name = row.houseText("chore_name"),
                    checked = false,
                    waiting = waiting,
                    onCheckedChange = { checked -> if (checked && canComplete) model.completeChore(id) },
                    due = houseDueDay(row.houseText("next_estimated_execution_time"), locale),
                    tone = when (group) {
                        "Overdue" -> ColorTone.Overdue
                        "Today" -> ColorTone.Due
                        else -> ColorTone.Neutral
                    },
                    detail = listOfNotNull(householdAssigneeName(assigned).takeIf { parent && it.isNotBlank() }?.let { "For $it" }, row.houseText("description").takeIf { it.isNotBlank() }).joinToString("\n").takeIf { it.isNotBlank() },
                    enabled = canComplete && !state.busy && !waiting,
                    trailing = if (parent) {
                        {
                            QuietButton(onClick = { editing = row }, enabled = !state.busy) { Text("Edit") }
                        }
                    } else null,
                )
            }
        }
    }
    if (creating || editing != null) {
        val row = editing
        val choreId = row?.houseId("chore_id")
        ChoreEditor(
            row, state.snapshot.users, state.busy,
            { creating = false; editing = null },
            { fields -> model.save(choreId, fields); creating = false; editing = null },
            onHistory = choreId?.let { id -> { model.history(id); creating = false; editing = null } },
            onDelete = choreId?.let { id -> { deleting = id; creating = false; editing = null } },
        )
    }
    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete chore?") },
            text = { Text("Remove this chore from Grocy?") },
            confirmButton = { PrimaryButton(onClick = { model.delete(id); deleting = null }) { Text("Delete") } },
            dismissButton = { QuietButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    state.history?.let { rows ->
        AlertDialog(onDismissRequest = model::closeHistory, title = { Text("Chore history") }, text = {
            Column(Modifier.imePadding().verticalScroll(rememberScrollState())) {
                if (rows.isEmpty()) Text("No chore history")
                rows.forEach { row ->
                    val user = state.snapshot.users.firstOrNull { it.houseId("id") == row.houseId("done_by_user_id") }
                    val status = when {
                        row.houseText("undone") == "1" -> "Undone"
                        row.houseText("skipped") == "1" -> "Skipped"
                        else -> "Completed"
                    }
                    Text("${houseDueDay(row.houseText("tracked_time"), locale) ?: row.houseText("tracked_time")} • ${householdAssigneeName(user).ifBlank { "User ${row.houseText("done_by_user_id")}" }} • $status")
                }
            }
        }, dismissButton = { QuietButton(onClick = model::closeHistory) { Text("Close") } }, confirmButton = {})
    }
}

@Composable
fun HouseholdTasksScreen(model: HouseholdViewModel, account: Account) {
    val state by model.state.collectAsState()
    val parent = HouseholdAccess.parent(account.permissions)
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<JsonObject?>(null) }
    var deleting by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { model.refresh() }
    val locale = Locale.getDefault()
    val canComplete = HouseholdAccess.has(account.permissions, "TASKS_MARK_COMPLETED")
    KitchenList(state.busy, model::refresh, spacedBy = 0.dp) {
        item {
            Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Tasks", style = MaterialTheme.typography.headlineSmall)
                KitchenWhisper(if (state.snapshot.stale) "Showing the last task list." else null)
                KitchenError(state.error)
                if (parent && HouseholdAccess.has(account.permissions, "TASKS")) {
                    PrimaryButton(onClick = { creating = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Add task") }
                }
                if (!HouseholdAccess.has(account.permissions, "TASKS")) Text("This account cannot see tasks.", color = MaterialTheme.colorScheme.error)
            }
        }
        if (HouseholdAccess.has(account.permissions, "TASKS")) {
            if (state.snapshot.tasks.isEmpty()) item {
                when {
                    state.busy -> Text("Loading tasks…")
                    state.error != null -> ErrorState(state.error!!, model::refresh)
                    else -> Text("No open tasks", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
                }
            }
            state.snapshot.tasks.groupBy { it.houseId("category_id") }.forEach { (category, tasks) ->
                item {
                    KitchenSectionTitle(
                        state.snapshot.categories.firstOrNull { it.houseId("id") == category }?.houseText("name") ?: "Uncategorized",
                        Modifier.padding(top = 8.dp),
                    )
                }
                items(tasks, key = { it.houseText("id") + it.houseText("name") }) { task ->
                    val id = task.houseId("id")!!
                    val waiting = state.operations.any { it.path == "/tasks/$id/complete" && it.state in setOf("pending", "in-flight", "needs-review") }
                    val assigned = state.snapshot.users.firstOrNull { it.houseId("id") == task.houseId("assigned_to_user_id") }
                        ?: task["assigned_to_user"] as? JsonObject
                    KitchenCheckRow(
                        name = task.houseText("name"),
                        checked = false,
                    waiting = waiting,
                        onCheckedChange = { checked -> if (checked && canComplete) model.completeTask(id) },
                        due = houseDueDay(task.houseText("due_date"), locale),
                        detail = listOfNotNull(householdAssigneeName(assigned).takeIf { it.isNotBlank() }, task.houseText("description").takeIf { it.isNotBlank() }).joinToString("\n").takeIf { it.isNotBlank() },
                        enabled = canComplete && !state.busy && !waiting,
                        trailing = if (parent) {
                            { QuietButton(onClick = { editing = task }, enabled = !state.busy) { Text("Edit") } }
                        } else null,
                    )
                }
            }
        }
    }
    if (creating || editing != null) {
        val row = editing
        val taskId = row?.houseId("id")
        TaskEditor(
            row, state.snapshot.categories, state.snapshot.users, state.busy,
            { creating = false; editing = null },
            { fields -> model.saveTask(taskId, fields); creating = false; editing = null },
            onDelete = taskId?.let { id -> { deleting = id; creating = false; editing = null } },
        )
    }
    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete task?") },
            text = { Text("Remove this task from Grocy?") },
            confirmButton = { PrimaryButton(onClick = { model.deleteTask(id); deleting = null }) { Text("Delete") } },
            dismissButton = { QuietButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ChoreEditor(
    row: JsonObject?,
    users: List<JsonObject>,
    busy: Boolean,
    close: () -> Unit,
    save: (JsonObject) -> Unit,
    onHistory: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(row?.houseText("name").orEmpty()) }
    var instructions by remember { mutableStateOf(row?.houseText("description").orEmpty()) }
    var period by remember { mutableStateOf(row?.houseText("period_type") ?: "daily") }
    var interval by remember { mutableStateOf(row?.houseText("period_interval") ?: "1") }
    var days by remember { mutableStateOf(row?.houseText("period_days") ?: "1") }
    var config by remember { mutableStateOf(row?.houseText("period_config").orEmpty().split(',').filter { it.isNotBlank() }.toSet()) }
    var start by remember { mutableStateOf(row?.houseText("start_date") ?: "${LocalDate.now()} 00:00:00") }
    var assignment by remember { mutableStateOf(row?.houseText("assignment_type") ?: "no-assignment") }
    var selected by remember {
        mutableStateOf(row?.houseText("assignment_config").orEmpty().split(',').mapNotNull { it.toLongOrNull() }.toSet())
    }
    var reassigned by remember { mutableStateOf(row?.houseId("rescheduled_next_execution_assigned_to_user_id")) }
    var dateOnly by remember { mutableStateOf(row?.houseText("track_date_only") == "1") }
    var rollover by remember { mutableStateOf(row?.houseText("rollover") == "1") }
    val assignedType = if (selected.isEmpty()) "no-assignment" else if (assignment == "no-assignment") "in-alphabetical-order" else assignment
    val valid = name.isNotBlank() && interval.toIntOrNull()?.let { it > 0 } == true && days.toIntOrNull()?.let { it >= 0 } == true &&
        runCatching { java.time.LocalDateTime.parse(start.replace(' ', 'T')) }.isSuccess && (period != "weekly" || config.isNotEmpty())
    AlertDialog(onDismissRequest = close, title = { Text(if (row == null) "Add chore" else "Edit chore") }, text = {
        Column(Modifier.imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            LabeledTextField(name, { name = it }, label = "Name", isError = name.isBlank(), supportingText = if(name.isBlank()) "Enter a name." else null, modifier = Modifier.fillMaxWidth())
            LabeledTextField(instructions, { instructions = it }, label = "Instructions", modifier = Modifier.fillMaxWidth())
            Text("Assign to", style = MaterialTheme.typography.titleMedium)
            if (users.isEmpty()) {
                Text("Household members will show here when Grocy lets this account read users.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else users.forEach { user ->
                val id = user.houseId("id") ?: return@forEach
                Row(Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).toggleable(value = id in selected, role = Role.Checkbox, onValueChange = { checked -> selected = if (checked) selected + id else selected - id }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(id in selected, null)
                    Text(householdAssigneeName(user).ifBlank { "User $id" })
                }
            }
            if (selected.isNotEmpty()) HouseChoice("Rotation", assignedType, listOf("in-alphabetical-order", "random", "who-least-did-first")) { assignment = it }
            HouseChoice("Recurrence", period, listOf("manually", "hourly", "daily", "weekly", "monthly", "yearly", "adaptive")) { period = it }
            LabeledTextField(interval, { interval = it }, label = "Repeat every", isError = interval.toIntOrNull()?.let { it > 0 } != true, supportingText = if(interval.toIntOrNull()?.let { it > 0 } != true) "Enter a whole number greater than zero." else null, modifier = Modifier.fillMaxWidth())
            if (period == "monthly") LabeledTextField(days, { days = it }, label = "Period days", isError = days.toIntOrNull()?.let { it >= 0 } != true, supportingText = "Enter a whole number of days.", modifier = Modifier.fillMaxWidth())
            if (period == "weekly") listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday").forEach { day ->
                Row(Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).toggleable(value = day in config, role = Role.Checkbox, onValueChange = { config = if (it) config + day else config - day }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(day in config, null); Text(day.replaceFirstChar { it.uppercase() })
                }
            }
            LabeledTextField(start, { start = it }, label = "Start date and time", isError = !runCatching { java.time.LocalDateTime.parse(start.replace(' ', 'T')) }.isSuccess, supportingText = "Use YYYY-MM-DD HH:MM:SS.", enabled = row?.houseText("last_tracked_time").isNullOrBlank(), modifier = Modifier.fillMaxWidth())
            if (row != null && users.isNotEmpty()) {
                ChoiceField("Next assigned member", users.mapNotNull { user -> user.houseId("id")?.let { it to householdAssigneeName(user).ifBlank { "User $it" } } }, reassigned, { reassigned = it }, allowNone = true, noneLabel = "Use rotation", enabled = !busy)
            }
            Row(Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).toggleable(value=dateOnly,role=Role.Checkbox,onValueChange={dateOnly=it}), verticalAlignment = Alignment.CenterVertically) { Checkbox(dateOnly, null); Text("Track date only") }
            Row(Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).toggleable(value=rollover,role=Role.Checkbox,onValueChange={rollover=it}), verticalAlignment = Alignment.CenterVertically) { Checkbox(rollover, null); Text("Carry missed chores forward") }
            if (onHistory != null || onDelete != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onHistory != null) QuietButton(onClick = onHistory, enabled = !busy) { Text("History") }
                if (onDelete != null) QuietButton(onClick = onDelete, enabled = !busy) { Text("Delete") }
            }
        }
    }, confirmButton = {
        PrimaryButton(enabled = valid && !busy, onClick = {
            save(buildJsonObject {
                put("name", name.trim()); put("description", instructions); put("period_type", period)
                put("period_interval", interval.toInt()); put("period_days", days.toInt()); put("period_config", config.joinToString(","))
                put("start_date", start); put("track_date_only", if (dateOnly) 1 else 0); put("rollover", if (rollover) 1 else 0)
                put("assignment_type", assignedType); put("assignment_config", selected.joinToString(","))
                if (row != null) put("rescheduled_next_execution_assigned_to_user_id", reassigned?.let { JsonPrimitive(it) } ?: JsonNull)
            })
        }) { Text("Save") }
    }, dismissButton = { QuietButton(onClick = close) { Text("Cancel") } })
}

@Composable
private fun TaskEditor(
    row: JsonObject?,
    categories: List<JsonObject>,
    users: List<JsonObject>,
    busy: Boolean,
    close: () -> Unit,
    save: (JsonObject) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(row?.houseText("name").orEmpty()) }
    var description by remember { mutableStateOf(row?.houseText("description").orEmpty()) }
    var due by remember { mutableStateOf(row?.houseText("due_date")?.take(10).orEmpty()) }
    var category by remember { mutableStateOf(row?.houseId("category_id")) }
    var assigned by remember { mutableStateOf(row?.houseId("assigned_to_user_id")) }
    val valid = name.isNotBlank() && (due.isBlank() || runCatching { LocalDate.parse(due) }.isSuccess)
    AlertDialog(onDismissRequest = close, title = { Text(if (row == null) "Add task" else "Edit task") }, text = {
        Column(Modifier.imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            LabeledTextField(name, { name = it }, label = "Name", isError = name.isBlank(), supportingText = if(name.isBlank()) "Enter a name." else null, modifier = Modifier.fillMaxWidth())
            LabeledTextField(description, { description = it }, label = "Notes", modifier = Modifier.fillMaxWidth())
            LabeledTextField(due, { due = it }, label = "Due date (optional)", isError = due.isNotBlank() && !runCatching { LocalDate.parse(due) }.isSuccess, supportingText = "Use YYYY-MM-DD.", modifier = Modifier.fillMaxWidth())
            ChoiceField("Task category", categories.mapNotNull { item -> item.houseId("id")?.let { it to item.houseText("name") } }, category, { category = it }, allowNone = true, enabled = !busy)
            ChoiceField("Assign to", users.mapNotNull { user -> user.houseId("id")?.let { it to householdAssigneeName(user).ifBlank { "User $it" } } }, assigned, { assigned = it }, allowNone = true, noneLabel = "Anyone", enabled = !busy)
            if (onDelete != null) QuietButton(onClick = onDelete, enabled = !busy) { Text("Delete") }
        }
    }, confirmButton = {
        PrimaryButton(enabled = valid && !busy, onClick = {
            save(buildJsonObject {
                put("name", name.trim()); put("description", description)
                if (due.isBlank()) put("due_date", JsonNull) else put("due_date", due)
                put("category_id", category?.let { JsonPrimitive(it) } ?: JsonNull)
                put("assigned_to_user_id", assigned?.let { JsonPrimitive(it) } ?: JsonNull)
            })
        }) { Text("Save") }
    }, dismissButton = { QuietButton(onClick = close) { Text("Cancel") } })
}

@Composable private fun HouseChoice(label: String, value: String, choices: List<String>, choose: (String) -> Unit) {
    fun display(raw:String)=when(raw) {
        "in-alphabetical-order" -> "Alphabetical order"
        "random" -> "Random order"
        "who-least-did-first" -> "Least completed first"
        "manually" -> "When needed"
        else -> raw.replaceFirstChar { it.uppercase() }
    }
    ChoiceField(label, choices.mapIndexed { index, choice -> index.toLong() to display(choice) }, choices.indexOf(value).toLong(), { index -> index?.toInt()?.let { choose(choices[it]) } })
}
