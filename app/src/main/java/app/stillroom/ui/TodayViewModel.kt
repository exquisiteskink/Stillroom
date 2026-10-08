package app.stillroom.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stillroom.data.*
import app.stillroom.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class TodayUiState(val snapshot:TodaySnapshot=TodaySnapshot(),val busy:Boolean=false,val error:String?=null)
class TodayViewModel(private val accounts:AndroidAccountsRepository):ViewModel() {
    private val mutable=MutableStateFlow(TodayUiState());val state=mutable.asStateFlow()
    private var work:Job?=null;private var identity:Account?=null;private var generation=0L
    init { viewModelScope.launch { accounts.state.collect { next->if(identity!=next.active){generation++;work?.cancel();identity=next.active;mutable.value=TodayUiState();if(next.active!=null)refresh()} } } }
    fun load()=execute(false)
    fun refresh()=execute(true)
    private fun execute(refresh:Boolean) {
        if(identity==null)return
        if(refresh && state.value.busy)return
        val bound=generation
        work=viewModelScope.launch {
            if(!refresh || state.value.snapshot.chores.isEmpty() && state.value.snapshot.meals.isEmpty()) {
                runCatching { accounts.cachedToday() }.onSuccess { if(bound==generation)mutable.value=state.value.copy(snapshot=it) }
            }
            if(!refresh)return@launch
            mutable.value=state.value.copy(busy=true,error=null)
            try { mutable.value=state.value.copy(snapshot=accounts.refreshToday()) }
            catch(e:CancellationException){throw e}
            catch(e:Exception){mutable.value=state.value.copy(error=e.message ?: "Could not update today's kitchen.",snapshot=if(e is GrocyFailure && e.status in setOf(401,403))TodaySnapshot() else state.value.snapshot)}
            finally { if(bound==generation)mutable.value=state.value.copy(busy=false) }
        }
    }
}
