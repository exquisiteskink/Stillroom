package app.stillroom.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stillroom.data.AndroidAccountsRepository
import app.stillroom.data.GrocyFailure
import app.stillroom.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonObject

data class HouseholdUiState(val snapshot: HouseholdSnapshot = HouseholdSnapshot(), val operations: List<PendingChange> = emptyList(), val history: List<JsonObject>? = null, val busy: Boolean = false, val error: String? = null)
class HouseholdViewModel(private val accounts: AndroidAccountsRepository) : ViewModel() {
    private val mutable = MutableStateFlow(HouseholdUiState())
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private var identity: Account? = null
    private var generation = 0L
    init { viewModelScope.launch { accounts.state.collect { next ->
        if (identity != next.active) { generation++; work?.cancel(); identity = next.active; mutable.value = HouseholdUiState(); if (next.active != null) refresh() }
    } } }
    fun refresh() = execute { it.sync() }
    fun save(id: Long?, fields: JsonObject) = execute { it.save(id,fields); it.sync() }
    fun delete(id: Long) = execute { it.delete(id); it.sync() }
    fun saveTask(id: Long?, fields: JsonObject) = execute { it.saveTask(id,fields); it.sync() }
    fun deleteTask(id: Long) = execute { it.deleteTask(id); it.sync() }
    fun completeChore(id: Long) = execute { it.completeChore(id); it.sync() }
    fun completeTask(id: Long) = execute { it.completeTask(id); it.sync() }
    fun history(id: Long) = execute { mutable.value = state.value.copy(history = it.history(id)) }
    fun closeHistory() { mutable.value = state.value.copy(history = null) }
    private fun execute(action: suspend (ManageHousehold) -> Unit) {
        if (state.value.busy || identity == null) return
        val boundGeneration = generation
        work = viewModelScope.launch {
            mutable.value = state.value.copy(busy = true,error = null)
            try { accounts.withHousehold { household ->
                action(household)
                mutable.value = state.value.copy(operations = household.operations())
                mutable.value = state.value.copy(snapshot = household.snapshot())
            } }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val denied = error is GrocyFailure && error.status in setOf(401,403)
                mutable.value = state.value.copy(snapshot = if (denied) HouseholdSnapshot() else state.value.snapshot,
                    error = if (denied) "Household access denied." else error.message ?: "Could not synchronize household records.")
            } finally { if (generation == boundGeneration) mutable.value = state.value.copy(busy = false) }
        }
    }
}
