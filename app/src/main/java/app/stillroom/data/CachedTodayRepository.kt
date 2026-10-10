package app.stillroom.data

import app.stillroom.domain.*
import java.time.LocalDate

class CachedTodayRepository(private val account:Account,private val db:AccountDatabase) {
    fun snapshot(day:LocalDate=LocalDate.now()):TodaySnapshot {
        if(db.get("background","access-denied")=="true")return TodaySnapshot(account=account,missing=CachedToday.paths(account),accessDenied=true)
        val config=db.get("/system/config","current")?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) as? kotlinx.serialization.json.JsonObject }.getOrNull() } ?: kotlinx.serialization.json.JsonObject(emptyMap())
        val capabilities=ServerCapabilities.parse(config,kotlinx.serialization.json.JsonObject(emptyMap()))
        val data=CachedToday.paths(account).filter { capabilities.allows(it) }.mapNotNull { path->db.get(path,"current")?.let { path to it } }.toMap().toMutableMap()
        if(HouseholdAccess.has(account.permissions,"TASKS") && capabilities.enabled("TASKS"))db.get("/objects/tasks","current")?.let { data["/objects/tasks"]=it }
        val snapshot = CachedToday.build(account,data,day)
        val operations = if (HouseholdAccess.has(account.permissions,"TASKS")) db.operations()
            .filter { it.path.startsWith("/tasks/") && (it.path.endsWith("/complete") || it.path.endsWith("/undo")) }
            .map { PendingChange(it.clientOperationId,it.method,it.path,it.state,it.detail,it.observedPayload != null) } else emptyList()
        return snapshot.copy(taskOperations=operations,scan=snapshot.scan && capabilities.enabled("STOCK"),missing=snapshot.missing.filter { capabilities.allows(it) })
    }
    suspend fun refresh(cache:CachedGrocyRepository) {
        for(path in CachedToday.paths(account).filter { cache.capabilities().allows(it) })cache.read(path)
    }
}
