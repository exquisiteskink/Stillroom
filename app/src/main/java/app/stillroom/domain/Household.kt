package app.stillroom.domain

import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

fun JsonObject.houseText(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
fun JsonObject.houseId(key: String): Long? = houseText(key).toLongOrNull()
object HouseholdAccess {
    fun has(grants: Set<String>?, permission: String) = grants?.let { "ADMIN" in it || permission in it } == true
    fun parent(grants: Set<String>?) = has(grants, "MASTER_DATA_EDIT")
    fun canReadUsers(grants: Set<String>?) = has(grants, "USERS_READ") || has(grants, "USERS")
}
fun houseDueDay(raw: String, locale: Locale): String? {
    if (raw.isBlank() || raw.equals("null", ignoreCase = true)) return null
    val date = runCatching { LocalDate.parse(raw.take(10)) }.getOrNull() ?: return null
    return date.format(DateTimeFormatter.ofPattern("MMM d", locale))
}
fun householdAssigneeName(user: JsonObject?): String {
    if (user == null) return ""
    return user.houseText("display_name").ifBlank { user.houseText("username") }
}
fun householdMembers(users: List<JsonObject>, chores: List<JsonObject> = emptyList(), tasks: List<JsonObject> = emptyList()): List<JsonObject> {
    if (users.isNotEmpty()) return users.distinctBy { it.houseId("id") }
    val fromChores = chores.mapNotNull { it["next_execution_assigned_user"] as? JsonObject }
    val fromTasks = tasks.mapNotNull { it["assigned_to_user"] as? JsonObject }
    return (fromChores + fromTasks).distinctBy { it.houseId("id") }
}
fun choreGroups(rows: List<JsonObject>, userId: Long?, today: LocalDate): Map<String, List<JsonObject>> {
    val groups = linkedMapOf("Overdue" to mutableListOf<JsonObject>(), "Today" to mutableListOf(), "Upcoming" to mutableListOf())
    rows.filter { userId == null || it.houseId("next_execution_assigned_to_user_id") == userId }.sortedBy { it.houseText("next_estimated_execution_time") }.forEach { row ->
        val due = runCatching { LocalDate.parse(row.houseText("next_estimated_execution_time").take(10)) }.getOrNull()
        val group = when { due == null -> "Upcoming"; due < today -> "Overdue"; due == today -> "Today"; else -> "Upcoming" }
        groups.getValue(group).add(row)
    }
    return groups
}
data class HouseholdSnapshot(val chores: List<JsonObject> = emptyList(), val tasks: List<JsonObject> = emptyList(), val categories: List<JsonObject> = emptyList(), val users: List<JsonObject> = emptyList(), val stale: Boolean = false)
interface HouseholdRepository {
    suspend fun snapshot(): HouseholdSnapshot
    suspend fun save(id: Long?, fields: JsonObject): String
    suspend fun delete(id: Long): String
    suspend fun saveTask(id: Long?, fields: JsonObject): String
    suspend fun deleteTask(id: Long): String
    suspend fun completeChore(id: Long): String
    suspend fun completeTask(id: Long): String
    suspend fun history(id: Long): List<JsonObject>
    suspend fun sync()
    suspend fun operations(): List<PendingChange>
}
class ManageHousehold(private val repository: HouseholdRepository) {
    suspend fun snapshot() = repository.snapshot()
    suspend fun save(id: Long?, fields: JsonObject) = repository.save(id, fields)
    suspend fun delete(id: Long) = repository.delete(id)
    suspend fun saveTask(id: Long?, fields: JsonObject) = repository.saveTask(id, fields)
    suspend fun deleteTask(id: Long) = repository.deleteTask(id)
    suspend fun completeChore(id: Long) = repository.completeChore(id)
    suspend fun completeTask(id: Long) = repository.completeTask(id)
    suspend fun history(id: Long) = repository.history(id)
    suspend fun sync() = repository.sync()
    suspend fun operations() = repository.operations()
}
