package app.stillroom.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stillroom.data.*
import app.stillroom.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class TodayUiState(val snapshot:TodaySnapshot=TodaySnapshot(),val busy:Boolean=false,val error:String?=null)
class TodayViewModel(private val accounts:AndroidAccountsRepository):ViewModel() {
    private val ui=AccountBoundState(TodayUiState());val state=ui.flow
    private var work:Job?=null;private var loading:Job?=null;private var identity:Account?=null
    init { viewModelScope.launch { accounts.state.collect { next->if(identity!=next.active){work?.cancel();loading?.cancel();identity=next.active;ui.reset(TodayUiState());if(next.active!=null)refresh()} } } }
    fun load()=execute(false)
    fun refresh()=execute(true)
    private fun execute(refresh:Boolean) {
        if(identity==null)return
        if(refresh && state.value.busy)return
        val bound=ui.publisher()
        // A cache-only load keeps its own handle so it never replaces a running refresh's handle.
        val job=viewModelScope.launch {
            if(!refresh || state.value.snapshot.chores.isEmpty() && state.value.snapshot.meals.isEmpty()) {
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
