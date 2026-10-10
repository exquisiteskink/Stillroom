package app.stillroom.data

import app.stillroom.domain.*
import kotlinx.serialization.json.*

/** Uses only the active account's transport, cache and durable outbox. */
class GrocyHouseholdRepository(private val grants: Set<String>?, private val userId: Long,
    private val cache: CachedGrocyRepository, private val pending: suspend () -> List<PendingChange>) : HouseholdRepository {
    init { require(userId > 0) }

    private fun requireGrant(name: String) = check(HouseholdAccess.has(grants, name)) { "Household access denied." }
    private suspend fun rows(path: String): Pair<List<JsonObject>, Boolean> {
        val read = cache.read(path)
        return Json.parseToJsonElement(read.payload).jsonArray.map { it.jsonObject } to read.stale
    }
    override suspend fun snapshot(): HouseholdSnapshot {
        var stale = false
        suspend fun read(path: String): List<JsonObject> = rows(path).let { stale = stale || it.second; it.first }
        val chores = if (cache.capabilities().enabled("CHORES") && HouseholdAccess.has(grants,"CHORES")) {
            val master = read("/objects/chores").associateBy { it.houseId("id") }
            read("/chores").map { JsonObject(master[it.houseId("chore_id")].orEmpty() + it) }
                .filter { HouseholdAccess.parent(grants) || (it.houseText("active") != "0" && it.houseId("next_execution_assigned_to_user_id") == userId) }
        } else emptyList()
        val tasks = if (cache.capabilities().enabled("TASKS") && HouseholdAccess.has(grants,"TASKS")) read("/tasks") else emptyList()
        val categories = if (cache.capabilities().enabled("TASKS") && HouseholdAccess.has(grants,"TASKS")) read("/objects/task_categories") else emptyList()
        val loadedUsers = if (HouseholdAccess.canReadUsers(grants)) {
            runCatching { read("/users") }.getOrDefault(emptyList())
        } else emptyList()
        val users = householdMembers(loadedUsers, chores, tasks)
        return HouseholdSnapshot(chores,tasks.filter { it.houseText("done")!="1" },categories,users,stale,tasks.filter { it.houseText("done")=="1" })
    }
    private suspend fun queue(method: String, path: String, payload: JsonObject, read: String): String {
        check(operations().none { it.path == path && it.state in setOf("pending","in-flight","needs-review","guarded") }) { "An operation for this record is awaiting confirmation. Review pending changes." }
        return cache.enqueue(method,path,payload.toString(),read)
    }
    override suspend fun save(id: Long?, fields: JsonObject): String {
        requireGrant("MASTER_DATA_EDIT"); require(id == null || id > 0)
        require(fields.keys.all { it in setOf("name","description","period_type","period_interval","period_days","period_config","start_date","track_date_only","rollover","assignment_type","assignment_config","next_execution_assigned_to_user_id","rescheduled_next_execution_assigned_to_user_id") })
        require(fields.houseText("name").isNotBlank())
        require(fields.houseText("period_type") in setOf("manually","hourly","daily","weekly","monthly","yearly","adaptive"))
        require(fields.houseId("period_interval")?.let { it > 0 } == true)
        return queue(if (id == null) "POST" else "PUT", "/objects/chores" + (id?.let { "/$it" } ?: ""),fields,"/chores")
    }
    override suspend fun delete(id: Long): String {
        requireGrant("MASTER_DATA_EDIT"); require(id > 0)
        return queue("DELETE","/objects/chores/$id",JsonObject(emptyMap()),"/chores")
    }
    override suspend fun saveTask(id: Long?, fields: JsonObject): String {
        requireGrant("TASKS"); requireGrant("MASTER_DATA_EDIT"); require(id == null || id > 0)
        require(fields.keys.all { it in setOf("name","description","due_date","category_id","assigned_to_user_id") })
        require(fields.houseText("name").isNotBlank())
        val due = fields.houseText("due_date")
        if (due.isNotBlank()) require(runCatching { java.time.LocalDate.parse(due.take(10)) }.isSuccess) { "Invalid due date." }
        fields["category_id"]?.let { if (it !is JsonNull) require(fields.houseId("category_id")?.let { id -> id > 0 } == true) }
        fields["assigned_to_user_id"]?.let { if (it !is JsonNull) require(fields.houseId("assigned_to_user_id")?.let { id -> id > 0 } == true) }
        return queue(if (id == null) "POST" else "PUT", "/objects/tasks" + (id?.let { "/$it" } ?: ""), fields, "/tasks")
    }
    override suspend fun createTaskCategory(name: String): Long {
        requireGrant("TASKS"); requireGrant("MASTER_DATA_EDIT")
        val trimmed=name.trim()
        require(trimmed.isNotBlank()) { "Enter a category name." }
        val operation=queue("POST","/objects/task_categories",buildJsonObject { put("name",trimmed) },"/objects/task_categories")
        cache.drain()
        val result=cache.outboxRecords().firstOrNull { it.clientOperationId==operation }
        check(result?.state=="confirmed") { "Category creation is awaiting confirmation. Review pending changes before trying again." }
        return result.responsePayload?.let { Json.parseToJsonElement(it).jsonObject.houseId("created_object_id") }
            ?.takeIf { it>0 } ?: error("Grocy confirmed the category without its ID. Refresh categories before continuing.")
    }
    override suspend fun deleteTask(id: Long): String {
        requireGrant("TASKS"); requireGrant("MASTER_DATA_EDIT"); require(id > 0)
        return queue("DELETE","/objects/tasks/$id",JsonObject(emptyMap()),"/tasks")
    }
    override suspend fun completeChore(id: Long): String {
        requireGrant("CHORES"); requireGrant("CHORE_TRACK_EXECUTION"); require(id > 0)
        // Recheck assignment online before accepting child completion; never send a parent identity override.
        if (!HouseholdAccess.parent(grants)) {
            val current = cache.readFresh("/chores/$id")!!.jsonObject
            val assigned = current["next_execution_assigned_user"] as? JsonObject
            check(assigned?.houseId("id") == userId) { "This chore is no longer assigned to you. Refresh My Chores." }
        }
        return queue("POST","/chores/$id/execute",buildJsonObject { put("done_by",userId) },"/chores/$id")
    }
    override suspend fun completeTask(id: Long): String {
        requireGrant("TASKS"); requireGrant("TASKS_MARK_COMPLETED"); require(id > 0)
        check(operations().none { it.path=="/tasks/$id/undo" && it.state in setOf("pending","in-flight","needs-review","guarded") }) { "Resolve this task’s pending change first." }
        return queue("POST","/tasks/$id/complete",JsonObject(emptyMap()),"/tasks")
    }
    override suspend fun completedTasks(): List<JsonObject> {
        requireGrant("TASKS")
        return rows("/objects/tasks").first.filter { it.houseText("done")=="1" }
    }
    override suspend fun reopenTask(id: Long): String {
        requireGrant("TASKS"); requireGrant("TASKS_UNDO_EXECUTION"); require(id>0)
        check(operations().none { it.path in setOf("/tasks/$id/complete","/tasks/$id/undo") && it.state in setOf("pending","in-flight","needs-review","guarded") }) { "Resolve this task's pending change first." }
        return queue("POST","/tasks/$id/undo",JsonObject(emptyMap()),"/tasks")
    }
    override suspend fun history(id: Long): List<JsonObject> {
        requireGrant("CHORES"); requireGrant("MASTER_DATA_EDIT"); require(id > 0)
        return rows("/objects/chores_log").first.filter { it.houseId("chore_id") == id }.sortedByDescending { it.houseText("tracked_time") }
    }
    override suspend fun sync() {
        cache.drain()
        if (HouseholdAccess.parent(grants)) {
            // Grocy's web form calls this official endpoint after saving assignment settings.
            // Persist a deterministic follow-up so process death cannot lose it or duplicate it.
            for (operation in cache.outboxRecords().filter {
                it.state == "confirmed" && it.method in setOf("POST", "PUT") &&
                    (it.path == "/objects/chores" || it.path.matches(Regex("/objects/chores/[1-9][0-9]*")))
            }) {
                val id = if (operation.method == "POST") {
                    operation.responsePayload?.takeIf { it.isNotBlank() }?.let {
                        Json.parseToJsonElement(it).jsonObject.houseId("created_object_id")
                    }
                } else operation.path.substringAfterLast('/').toLongOrNull()
                if (id != null) {
                    val followUp = java.util.UUID.nameUUIDFromBytes((operation.clientOperationId + ":assignment").toByteArray()).toString()
                    cache.enqueue("POST", "/chores/executions/calculate-next-assignments",
                        buildJsonObject { put("chore_id", id) }.toString(), "/chores/$id", operationId = followUp)
                }
            }
            cache.drain()
        }
    }
    override suspend fun operations() = pending().filter {
        it.path.startsWith("/chores/") || it.path.startsWith("/objects/chores") ||
            it.path.startsWith("/tasks/") || it.path.startsWith("/objects/tasks") || it.path.startsWith("/objects/task_categories")
    }
}
