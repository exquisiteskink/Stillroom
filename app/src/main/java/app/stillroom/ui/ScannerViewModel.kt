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
    private val mutable=MutableStateFlow(ScannerUiState())
    val state=mutable.asStateFlow()
    private var session=ScanSession()
    private var identity:Account?=null
    private var generation=0L
    private var work:Job?=null
    init { viewModelScope.launch { accounts.state.collect { current ->
        if(identity!=current.active) {
            generation++;work?.cancel();identity=current.active;session=ScanSession()
            mutable.value=ScannerUiState(denied=!StockAccess.canRead(current.active?.permissions))
        }
    } } }
    fun detected(codes:List<ScanCode>) {
        if(state.value.busy || state.value.denied || state.value.product!=null || state.value.result!=null || state.value.candidates.isNotEmpty()) return
        val candidates=session.offer(codes)
        if(candidates.size==1) choose(candidates.single())
        else if(candidates.isNotEmpty()) mutable.value=state.value.copy(candidates=candidates,error=null)
    }
    fun manual(raw:String,format:ScanFormat=ScanFormat.Manual) {
        try {
            val code=ScanCode(raw.trim(),format).validated()
            if(session.offer(listOf(code)).isEmpty()) { mutable.value=state.value.copy(error="Already read. Choose Scan same code again to repeat.");return }
            choose(code)
        } catch(error:IllegalArgumentException) { mutable.value=state.value.copy(error=error.message) }
    }
    fun choose(code:ScanCode)=execute { scanner ->
        session.choose(code)
        mutable.value=state.value.copy(lastCode=code,candidates=emptyList(),result=null,product=null)
        scanner.sync()
        val result=scanner.lookup(code)
        val choices=if(result.productIds.isEmpty() && HouseholdAccess.has(identity?.permissions,"MASTER_DATA_EDIT")) scanner.choices() else emptyList<Pair<Long,String>>() to emptyList()
        mutable.value=state.value.copy(result=result,product=automaticScanProduct(result.productIds),units=choices.first,locations=choices.second)
    }
    fun setAction(action:StockAction) {
        if(!state.value.busy && action in setOf(StockAction.Purchase,StockAction.Consume) && StockAccess.canWrite(identity?.permissions,action))
            mutable.value=state.value.copy(action=action)
    }
    fun retryLookup() { state.value.lastCode?.let { choose(it) } }
    fun chooseProduct(id:Long) { mutable.value=state.value.copy(product=id) }
    fun scanNext(identical:Boolean=false) {
        if(state.value.busy || scanCreationLocked(state.value.createOperation,state.value.product)) return
        if(identical) state.value.lastCode?.let(session::scanAnotherIdentical)
        mutable.value=state.value.copy(result=null,product=null,candidates=emptyList(),error=null,createOperation=null)
    }
    fun create(review:ScanReview)=execute { scanner ->
        val operation=scanner.create(review)
        mutable.value=state.value.copy(operations=scanner.operations(),createOperation=operation)
        scanner.sync()
        val product=scanner.createdProduct(operation)
        mutable.value=state.value.copy(product=product,error=if(product==null)"Could not confirm product creation. Check changes before trying again." else null)
    }
    fun checkCreation()=execute { scanner ->
        val operation=state.value.createOperation ?: return@execute
        scanner.sync()
        val product=scanner.createdProduct(operation)
        mutable.value=state.value.copy(product=product,error=if(product==null) "Could not confirm product creation. Check changes before trying again." else null)
    }
    private fun execute(action:suspend(ManageScanner)->Unit) {
        if(state.value.busy || state.value.denied) return
        val boundGeneration=generation
        mutable.value=state.value.copy(busy=true,error=null)
        work=viewModelScope.launch {
            try { accounts.withScanner { scanner -> action(scanner);mutable.value=state.value.copy(operations=scanner.operations()) } }
            catch(error:CancellationException) { throw error }
            catch(error:Exception) {
                val denied=error is GrocyFailure && error.status in setOf(401,403)
                mutable.value=state.value.copy(denied=denied,result=if(denied)null else state.value.result,error=if(denied)"Stock access denied." else error.message ?: "Could not look up this barcode.")
            } finally { if(generation==boundGeneration) mutable.value=state.value.copy(busy=false) }
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
