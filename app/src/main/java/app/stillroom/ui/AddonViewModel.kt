package app.stillroom.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stillroom.data.*
import app.stillroom.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

val LocalServerCapabilities=androidx.compose.runtime.staticCompositionLocalOf { ServerCapabilities() }
val LocalAddonModel=androidx.compose.runtime.staticCompositionLocalOf<AddonViewModel?> { null }
val LocalAddonSettings=androidx.compose.runtime.staticCompositionLocalOf { AddonSettings() }

data class AddonUiState(val account:Account?=null,val observation:CompatibilityObservation=CompatibilityObservation(),val revision:Long=0,val settings:AddonSettings=AddonSettings(),val busy:Boolean=false,val error:String?=null,val message:String?=null,val custom:CustomRecordsSnapshot=CustomRecordsSnapshot(),val entity:JsonObject?=null,val objectId:Long?=null,val values:JsonObject?=null,val operation:String?=null,val receipts:List<Pair<String,String>> = emptyList())
class AddonViewModel(private val accounts:AndroidAccountsRepository):ViewModel() {
    private val ui=AccountBoundState(AddonUiState());val state=ui.flow
    private var identity:Account?=null
    private var polling:Job?=null
    private var work:Job?=null
    private var foreground=false
    init { viewModelScope.launch { accounts.state.collect { next->
        if(identity!=next.active) {
            polling?.cancel();work?.cancel();identity=next.active;ui.reset(AddonUiState(account=next.active))
            if(next.active!=null) { execute { val settings=accounts.addonSettings();publish { it.copy(settings=settings) } };startPolling() }
        }
    } } }
    fun foreground(active:Boolean) { foreground=active;if(active)startPolling() else { polling?.cancel();polling=null } }
    private fun startPolling() {
        if(!foreground || identity==null || polling?.isActive==true)return
        val bound=ui.publisher()
        polling=viewModelScope.launch {
            var first=true
            while(isActive && bound.current) {
                try {
                    val observed=accounts.observeGrocy(first);first=false
                    val receipts=accounts.barcodeBuddyReceipts()
                    val custom=if(observed.changed && state.value.custom.selectedEntity!=null && state.value.entity==null && !state.value.busy)
                        accounts.withCustomRecords { it.snapshot(state.value.custom.selectedEntity,state.value.custom.offset) } else null
                    bound.publish { it.copy(observation=observed,revision=it.revision+if(observed.changed)1 else 0,receipts=receipts,
                        custom=if(custom!=null && it.entity==null && !it.busy)custom else it.custom) }
                } catch(e:CancellationException){throw e}
                catch(e:Exception){bound.publish { it.copy(error=if(e is GrocyFailure && e.status in setOf(401,403)) "Server access denied. Reconnect this account." else "Could not check external changes. Cached data remains available.") }}
                delay(30_000)
            }
        }
    }
    fun refresh()=execute {
        val result=accounts.observeGrocy(true)
        val custom=if(state.value.custom.selectedEntity!=null && state.value.entity==null)accounts.withCustomRecords { it.snapshot(state.value.custom.selectedEntity,state.value.custom.offset) } else null
        publish { it.copy(observation=result,revision=it.revision+1,custom=custom ?: it.custom) }
    }
    fun save(settings:AddonSettings,key:String,clearKey:Boolean)=execute {
        accounts.saveAddonSettings(settings,key,clearKey)
        val saved=accounts.addonSettings();publish { it.copy(settings=saved,message="Add-on settings saved.") }
    }
    fun testBarcodeBuddy()=execute { val mode=accounts.barcodeBuddyMode();publish { it.copy(message="BarcodeBuddy mode: $mode") } }
    fun reviewReceipt(id:String)=execute { accounts.reviewBarcodeBuddyReceipt(id);val receipts=accounts.barcodeBuddyReceipts();publish { it.copy(receipts=receipts) } }
    fun loadCustom(entityId:Long?=null,offset:Int=0)=execute { val snapshot=accounts.withCustomRecords { it.snapshot(entityId,offset) };publish { it.copy(custom=snapshot) } }
    fun edit(entity:JsonObject,id:Long?)=execute {
        val values=if(id==null)JsonObject(emptyMap()) else accounts.withCustomRecords { it.values(entity,id) }
        publish { it.copy(entity=entity,objectId=id,values=values,operation=null) }
    }
    fun closeEditor() { if(!state.value.busy)ui.update { it.copy(entity=null,values=null,operation=null) } }
    fun saveCustom(changed:JsonObject)=execute {
        val entity=state.value.entity ?: return@execute
        val id=state.value.objectId
        val baseline=state.value.values ?: return@execute
        val previous=state.value.operation
        accounts.withCustomRecords { records->
            val op=previous ?: records.save(entity,id,changed,baseline)
            publish { it.copy(operation=op) }
            records.sync()
            val confirmed=records.confirmed(op)
            val snapshot=records.snapshot(state.value.custom.selectedEntity,state.value.custom.offset)
            publish { it.copy(custom=snapshot,entity=if(confirmed)null else it.entity,values=if(confirmed)null else it.values,message=if(confirmed) "Record saved in Grocy." else "Save is not confirmed. Check Pending changes.") }
        }
    }
    fun deleteCustom(entity:JsonObject,id:Long)=execute {
        accounts.withCustomRecords { it.delete(entity,id);it.sync();val snapshot=it.snapshot(state.value.custom.selectedEntity,state.value.custom.offset);publish { s->s.copy(custom=snapshot) } }
    }
    suspend fun media(group:String,name:String):ByteArray=accounts.grocyFile(group,name)
    suspend fun upload(bytes:ByteArray,extension:String,displayName:String?=null):String=accounts.withCustomRecords { it.upload(bytes,extension,displayName) }
    private fun execute(action:suspend AccountBoundState<AddonUiState>.Publisher.()->Unit) {
        if(state.value.busy || identity==null)return
        val bound=ui.publisher()
        work=viewModelScope.launch {
            bound.publish { it.copy(busy=true,error=null,message=null) }
            try { bound.action() }
            catch(e:CancellationException){throw e}
            catch(e:Exception){bound.publish { it.copy(error=e.message ?: "Could not complete the add-on action.") }}
            finally { bound.publish { it.copy(busy=false) } }
        }
    }
}
