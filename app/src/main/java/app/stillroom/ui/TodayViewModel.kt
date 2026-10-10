package app.stillroom.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stillroom.data.*
import app.stillroom.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class TodayUiState(val snapshot:TodaySnapshot=TodaySnapshot(),val busy:Boolean=false,val error:String?=null,val taskChoices:HouseholdSnapshot=HouseholdSnapshot(),val createdCategory:Long?=null,val editorOpen:Boolean=false)
class TodayViewModel(private val accounts:AndroidAccountsRepository):ViewModel() {
    private val ui=AccountBoundState(TodayUiState());val state=ui.flow
    private var work:Job?=null;private var loading:Job?=null;private var identity:Account?=null
    init { viewModelScope.launch { accounts.state.collect { next->if(identity!=next.active){work?.cancel();loading?.cancel();identity=next.active;ui.reset(TodayUiState());if(next.active!=null)refresh()} } } }
    fun load()=execute(false)
    fun refresh()=execute(true)
    fun loadCompletedTasks()=taskAction { household ->
        household.completedTasks();val cached=accounts.cachedToday();publish { it.copy(snapshot=cached) }
    }
    fun loadTaskChoices()=taskAction { household ->
        val choices=household.snapshot(); publish { it.copy(taskChoices=choices) }
    }
    fun openTaskEditor() { if(!state.value.busy) { ui.update { it.copy(editorOpen=true) };loadTaskChoices() } }
    fun closeTaskEditor() { if(!state.value.busy)ui.update { it.copy(editorOpen=false) } }
    fun createTaskCategory(name:String)=taskAction { household ->
        val id=household.createTaskCategory(name);val choices=household.snapshot()
        publish { it.copy(createdCategory=id,taskChoices=choices) }
    }
    fun addTask(fields:kotlinx.serialization.json.JsonObject)=taskAction { household ->
        val account=identity ?: return@taskAction
        check(HouseholdAccess.has(account.permissions,"MASTER_DATA_EDIT"))
        val assigned=fields.houseId("assigned_to_user_id")
        check(assigned==null || assigned==account.userId) { "Today tasks must be for you or everyone." }
        val operation=household.saveTask(null,fields);household.sync()
        val confirmed=household.operations().any { it.clientOperationId==operation && it.state=="confirmed" }
        check(confirmed) { "Task creation is not confirmed. Review Pending changes before saving again." }
        // Close on acknowledgement even if the following refresh fails, preventing a second create.
        publish { it.copy(editorOpen=false) }
        val fresh=accounts.refreshToday();publish { it.copy(snapshot=fresh) }
    }
    fun reopenTask(id:Long) {
        val account=identity ?: return
        if(state.value.busy || state.value.snapshot.account?.id!=account.id || !HouseholdAccess.has(account.permissions,"TASKS_UNDO_EXECUTION") ||
            tasksForUser(state.value.snapshot.completedTasks,account,completed=true).none { it.houseId("id")==id })return
        taskAction { household -> household.reopenTask(id);household.sync();household.completedTasks();val fresh=accounts.refreshToday();publish { it.copy(snapshot=fresh) } }
    }
    private fun taskAction(action:suspend AccountBoundState<TodayUiState>.Publisher.(ManageHousehold)->Unit) {
        if(identity==null || state.value.busy)return
        val bound=ui.publisher();loading?.cancel();ui.update { it.copy(busy=true,error=null) }
        work=viewModelScope.launch {
            try { accounts.withHousehold { bound.action(it) } }
            catch(e:CancellationException){throw e}
            catch(e:Exception){bound.publish { it.copy(error=e.message ?: "Could not update tasks.",snapshot=if(e is GrocyFailure && e.status in setOf(401,403))TodaySnapshot() else it.snapshot) }}
            finally { bound.publish { it.copy(busy=false) } }
        }
    }
    fun completeTask(id: Long) {
        val account=identity ?: return
        if(state.value.busy || state.value.snapshot.account?.id != account.id || !HouseholdAccess.has(account.permissions,"TASKS_MARK_COMPLETED") ||
            tasksForUser(state.value.snapshot.tasks,account).none { it.houseId("id")==id }) return
        val bound=ui.publisher()
        loading?.cancel()
        work=viewModelScope.launch {
            bound.publish { it.copy(busy=true,error=null) }
            try {
                accounts.withHousehold { household -> household.completeTask(id); household.sync() }
                val fresh=accounts.refreshToday()
                bound.publish { it.copy(snapshot=fresh) }
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) {
                val denied=e is GrocyFailure && e.status in setOf(401,403)
                val cached=if(denied) TodaySnapshot() else runCatching { accounts.cachedToday() }.getOrNull()
                bound.publish { it.copy(snapshot=cached ?: it.snapshot,error=if(denied) "Task access denied." else "Could not confirm the task. Check its sync status and refresh.") }
            } finally { bound.publish { it.copy(busy=false) } }
        }
    }
    private fun execute(refresh:Boolean) {
        if(identity==null)return
        if(refresh && state.value.busy)return
        val bound=ui.publisher()
        // A cache-only load keeps its own handle so it never replaces a running refresh's handle.
        val job=viewModelScope.launch {
            if(!refresh || state.value.snapshot.chores.isEmpty() && state.value.snapshot.meals.isEmpty() && state.value.snapshot.tasks.isEmpty()) {
                runCatching { accounts.cachedToday() }.onSuccess { cached->bound.publish { it.copy(snapshot=cached) } }
            }
            if(!refresh)return@launch
            bound.publish { it.copy(busy=true,error=null) }
            try { val fresh=accounts.refreshToday();bound.publish { it.copy(snapshot=fresh) } }
            catch(e:CancellationException){throw e}
            catch(e:Exception){bound.publish { it.copy(error=e.message ?: "Could not update today's kitchen.",snapshot=if(e is GrocyFailure && e.status in setOf(401,403))TodaySnapshot() else it.snapshot) }}
            finally { bound.publish { it.copy(busy=false) } }
        }
        if(refresh)work=job else loading=job
    }
}
