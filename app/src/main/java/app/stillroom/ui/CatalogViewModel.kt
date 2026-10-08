package app.stillroom.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stillroom.data.*
import app.stillroom.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

data class CatalogUiState(val snapshot:CatalogSnapshot=CatalogSnapshot(),val operations:List<PendingChange> = emptyList(),val history:List<JsonObject>?=null,val busy:Boolean=false,val error:String?=null)
class CatalogViewModel(private val accounts:AndroidAccountsRepository):ViewModel() {
    private val mutable=MutableStateFlow(CatalogUiState());val state=mutable.asStateFlow()
    private var identity:Account?=null;private var work:Job?=null;private var generation=0L
    init { viewModelScope.launch { accounts.state.collect { next->if(identity!=next.active) {
        generation++;work?.cancel();identity=next.active;mutable.value=CatalogUiState()
        if(next.active!=null && CatalogEntity.entries.any { it.readable(next.active.permissions) })refresh()
    } } } }
    fun refresh()=execute { it.sync() }
    fun save(entity:CatalogEntity,id:Long?,fields:JsonObject,custom:JsonObject)=execute { it.save(entity,id,fields,custom);it.sync() }
    fun delete(entity:CatalogEntity,id:Long)=execute { it.delete(entity,id);it.sync() }
    fun charge(id:Long)=execute { it.charge(id);it.sync() }
    fun undo(id:Long)=execute { it.undoCycle(id);it.sync() }
    fun history(id:Long)=execute { mutable.value=state.value.copy(history=it.history(id)) }
    fun closeHistory(){mutable.value=state.value.copy(history=null)}
    private fun execute(action:suspend(ManageCatalog)->Unit) {
        if(state.value.busy || identity==null)return
        val bound=generation
        work=viewModelScope.launch {
            mutable.value=state.value.copy(busy=true,error=null)
            try { accounts.withCatalog { r->action(r);mutable.value=state.value.copy(snapshot=r.snapshot(),operations=r.operations()) } }
            catch(e:CancellationException){throw e}
            catch(e:Exception){mutable.value=state.value.copy(error=e.message ?: "Master-data sync failed.",snapshot=if(e is GrocyFailure && e.status in setOf(401,403))CatalogSnapshot() else state.value.snapshot)}
            finally { if(bound==generation)mutable.value=state.value.copy(busy=false) }
        }
    }
}
