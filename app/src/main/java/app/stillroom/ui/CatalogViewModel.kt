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
    private val ui=AccountBoundState(CatalogUiState());val state=ui.flow
    private var identity:Account?=null;private var work:Job?=null
    init { viewModelScope.launch { accounts.state.collect { next->if(identity!=next.active) {
        work?.cancel();identity=next.active;ui.reset(CatalogUiState())
        if(next.active!=null && CatalogEntity.entries.any { it.readable(next.active.permissions) })refresh()
    } } } }
    fun refresh()=execute { it.sync() }
    fun save(entity:CatalogEntity,id:Long?,fields:JsonObject,custom:JsonObject)=execute { it.save(entity,id,fields,custom);it.sync() }
    fun delete(entity:CatalogEntity,id:Long)=execute { it.delete(entity,id);it.sync() }
    fun charge(id:Long)=execute { it.charge(id);it.sync() }
    fun undo(id:Long)=execute { it.undoCycle(id);it.sync() }
    fun history(id:Long)=execute { r->val history=r.history(id);publish { it.copy(history=history) } }
    fun closeHistory(){ui.update { it.copy(history=null) }}
    private fun execute(action:suspend AccountBoundState<CatalogUiState>.Publisher.(ManageCatalog)->Unit) {
        if(state.value.busy || identity==null)return
        val bound=ui.publisher()
        work=viewModelScope.launch {
            bound.publish { it.copy(busy=true,error=null) }
            try { accounts.withCatalog { r->bound.action(r);val snapshot=r.snapshot();val operations=r.operations();bound.publish { it.copy(snapshot=snapshot,operations=operations) } } }
            catch(e:CancellationException){throw e}
            catch(e:Exception){bound.publish { it.copy(error=e.message ?: "Master-data sync failed.",snapshot=if(e is GrocyFailure && e.status in setOf(401,403))CatalogSnapshot() else it.snapshot) }}
            finally { bound.publish { it.copy(busy=false) } }
        }
    }
}
