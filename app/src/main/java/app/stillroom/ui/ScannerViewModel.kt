package app.stillroom.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stillroom.data.AndroidAccountsRepository
import app.stillroom.data.GrocyFailure
import app.stillroom.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ScannerUiState(val candidates:List<ScanCode> = emptyList(),val lastCode:ScanCode?=null,
    val result:ScanSuggestion?=null,val product:Long?=null,val units:List<Pair<Long,String>> = emptyList(),
    val locations:List<Pair<Long,String>> = emptyList(),val operations:List<PendingChange> = emptyList(),
    val busy:Boolean=false,val denied:Boolean=true,val error:String?=null,
    val action:StockAction=StockAction.Purchase,val createOperation:String?=null)
class ScannerViewModel(private val accounts:AndroidAccountsRepository):ViewModel() {
    private val ui=AccountBoundState(ScannerUiState())
    val state=ui.flow
    private var session=ScanSession()
    private var identity:Account?=null
    private var work:Job?=null
    init { viewModelScope.launch { accounts.state.collect { current ->
        if(identity!=current.active) {
            work?.cancel();identity=current.active;session=ScanSession()
            ui.reset(ScannerUiState(denied=!StockAccess.canRead(current.active?.permissions)))
        }
    } } }
    fun detected(codes:List<ScanCode>) {
        if(state.value.busy || state.value.denied || state.value.product!=null || state.value.result!=null || state.value.candidates.isNotEmpty()) return
        val candidates=session.offer(codes)
        if(candidates.size==1) choose(candidates.single())
        else if(candidates.isNotEmpty()) ui.update { it.copy(candidates=candidates,error=null) }
    }
    fun manual(raw:String,format:ScanFormat=ScanFormat.Manual) {
        try {
            val code=ScanCode(raw.trim(),format).validated()
            if(session.offer(listOf(code)).isEmpty()) { ui.update { it.copy(error="Already read. Choose Scan same code again to repeat.") };return }
            choose(code)
        } catch(error:IllegalArgumentException) { ui.update { it.copy(error=error.message) } }
    }
    fun choose(code:ScanCode)=execute { scanner ->
        session.choose(code)
        publish { it.copy(lastCode=code,candidates=emptyList(),result=null,product=null) }
        scanner.sync()
        val result=scanner.lookup(code)
        val choices=if(result.productIds.isEmpty() && HouseholdAccess.has(identity?.permissions,"MASTER_DATA_EDIT")) scanner.choices() else emptyList<Pair<Long,String>>() to emptyList()
        publish { it.copy(result=result,product=automaticScanProduct(result.productIds),units=choices.first,locations=choices.second) }
    }
    fun setAction(action:StockAction) {
        if(!state.value.busy && action in setOf(StockAction.Purchase,StockAction.Consume) && StockAccess.canWrite(identity?.permissions,action))
            ui.update { it.copy(action=action) }
    }
    fun retryLookup() { state.value.lastCode?.let { choose(it) } }
    fun chooseProduct(id:Long) { ui.update { it.copy(product=id) } }
    fun scanNext(identical:Boolean=false) {
        if(state.value.busy || scanCreationLocked(state.value.createOperation,state.value.product)) return
        if(identical) state.value.lastCode?.let(session::scanAnotherIdentical)
        ui.update { it.copy(result=null,product=null,candidates=emptyList(),error=null,createOperation=null) }
    }
    fun create(review:ScanReview)=execute { scanner ->
        val operation=scanner.create(review)
        val operations=scanner.operations()
        publish { it.copy(operations=operations,createOperation=operation) }
        scanner.sync()
        val product=scanner.createdProduct(operation)
        publish { it.copy(product=product,error=if(product==null)"Could not confirm product creation. Check changes before trying again." else null) }
    }
    fun checkCreation()=execute { scanner ->
        val operation=state.value.createOperation ?: return@execute
        scanner.sync()
        val product=scanner.createdProduct(operation)
        publish { it.copy(product=product,error=if(product==null) "Could not confirm product creation. Check changes before trying again." else null) }
    }
    private fun execute(action:suspend AccountBoundState<ScannerUiState>.Publisher.(ManageScanner)->Unit) {
        if(state.value.busy || state.value.denied) return
        val bound=ui.publisher()
        ui.update { it.copy(busy=true,error=null) }
        work=viewModelScope.launch {
            try { accounts.withScanner { scanner -> bound.action(scanner);val operations=scanner.operations();bound.publish { it.copy(operations=operations) } } }
            catch(error:CancellationException) { throw error }
            catch(error:Exception) {
                val denied=error is GrocyFailure && error.status in setOf(401,403)
                bound.publish { it.copy(denied=denied,result=if(denied)null else it.result,error=if(denied)"Stock access denied." else error.message ?: "Could not look up this barcode.") }
            } finally { bound.publish { it.copy(busy=false) } }
        }
    }
}

internal fun automaticScanProduct(ids:List<Long>):Long? = ids.singleOrNull()

internal fun scanBookingOutcome(operation:String?,rows:List<PendingChange>):String? {
    if(operation==null) return null
    return when(rows.firstOrNull { it.clientOperationId==operation }?.state) {
        "confirmed" -> "Saved in Grocy"
        "needs-review" -> "Could not confirm this change. Check changes before trying again."
        "failed" -> "This change was not saved. Check changes for details."
        else -> "Waiting to sync"
    }
}

/** Creation is complete only when both product and barcode attachment are confirmed. */
internal fun scanCreationLocked(operation:String?,confirmedProduct:Long?):Boolean = operation!=null && confirmedProduct==null
