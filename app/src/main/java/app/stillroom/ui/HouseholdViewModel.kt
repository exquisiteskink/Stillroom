package app.stillroom.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stillroom.data.AndroidAccountsRepository
import app.stillroom.data.GrocyFailure
import app.stillroom.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonObject

data class HouseholdUiState(val snapshot: HouseholdSnapshot = HouseholdSnapshot(), val operations: List<PendingChange> = emptyList(), val history: List<JsonObject>? = null, val busy: Boolean = false, val error: String? = null, val createdTaskCategory: Long? = null)
class HouseholdViewModel(private val accounts: AndroidAccountsRepository) : ViewModel() {
    private val ui = AccountBoundState(HouseholdUiState())
    val state = ui.flow
    private var work: Job? = null
    private var identity: Account? = null
    init { viewModelScope.launch { accounts.state.collect { next ->
        if (identity != next.active) { work?.cancel(); identity = next.active; ui.reset(HouseholdUiState()); if (next.active != null) refresh() }
    } } }
    fun externalRefresh()=execute { }
    fun refresh() = execute { it.sync() }
    fun save(id: Long?, fields: JsonObject) = execute { it.save(id,fields); it.sync() }
    fun delete(id: Long) = execute { it.delete(id); it.sync() }
    fun saveTask(id: Long?, fields: JsonObject) = execute { it.saveTask(id,fields); it.sync() }
    fun createTaskCategory(name: String) = execute {
        val id=it.createTaskCategory(name)
        publish { state -> state.copy(createdTaskCategory=id) }
    }
    fun deleteTask(id: Long) = execute { it.deleteTask(id); it.sync() }
    fun completeChore(id: Long) = execute { it.completeChore(id); it.sync() }
    fun completeTask(id: Long) = execute { it.completeTask(id); it.sync() }
    fun loadCompletedTasks()=execute { household -> val completed=household.completedTasks();publish { it.copy(snapshot=it.snapshot.copy(completedTasks=completed)) } }
    fun reopenTask(id: Long) = execute { household -> household.reopenTask(id); household.sync();val completed=household.completedTasks();publish { it.copy(snapshot=it.snapshot.copy(completedTasks=completed)) } }
    fun history(id: Long) = execute { household -> val history = household.history(id); publish { it.copy(history = history) } }
    fun closeHistory() { ui.update { it.copy(history = null) } }
    private fun execute(action: suspend AccountBoundState<HouseholdUiState>.Publisher.(ManageHousehold) -> Unit) {
        if (state.value.busy || identity == null) return
        val bound = ui.publisher()
        work = viewModelScope.launch {
            bound.publish { it.copy(busy = true,error = null) }
            try { accounts.withHousehold { household ->
                bound.action(household)
                val operations = household.operations()
                bound.publish { it.copy(operations = operations) }
                val snapshot = household.snapshot().copy(completedTasks=state.value.snapshot.completedTasks)
                bound.publish { it.copy(snapshot = snapshot) }
            } }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val denied = error is GrocyFailure && error.status in setOf(401,403)
                bound.publish { it.copy(snapshot = if (denied) HouseholdSnapshot() else it.snapshot,
                    error = if (denied) "Household access denied." else error.message ?: "Could not synchronize household records.") }
            } finally { bound.publish { it.copy(busy = false) } }
        }
    }
}
