package app.stillroom.ui

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.stillroom.domain.*
import kotlinx.coroutines.flow.first

@Composable
fun ScannerScreen(model:ScannerViewModel,stock:StockViewModel,account:Account,onReviewChanges:()->Unit={},onExit:()->Unit={}) {
    val state by model.state.collectAsState()
    val stockState by stock.state.collectAsState()
    var manual by remember(account.id) { mutableStateOf("") }
    var manualFormat by remember(account.id) { mutableStateOf(ScanFormat.Manual) }
    var manualExpanded by remember(account.id) { mutableStateOf(false) }
    val product = state.product
    val locked = state.busy || stockState.busy
    val creationLocked=scanCreationLocked(state.createOperation,state.product)
    val repeatAllowed=stockState.bookingOperation==null || stockState.operations.any { it.clientOperationId==stockState.bookingOperation && it.state=="confirmed" }
    val next = { identical:Boolean -> if(repeatAllowed) stock.clearBookingOutcome(); model.scanNext(identical) }
    androidx.activity.compose.BackHandler(enabled=state.result!=null || state.candidates.isNotEmpty()) {
        if (!locked) { if (creationLocked) onExit() else next(false) }
    }
    if(state.denied || !StockAccess.canRead(account.permissions)) { PermissionDeniedState(); return }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf(StockAction.Purchase to "Add stock",StockAction.Consume to "Use stock").filter { StockAccess.canWrite(account.permissions,it.first) }.forEach { (action,label) ->
                FilterChip(state.action==action,{model.setAction(action)},{Text(label)},enabled=!locked && product==null)
            }
        }
        if(state.busy) {
            val message = if (state.createOperation != null) "Checking product status…" else if (state.result != null) "Saving product…" else "Finding product…"
            TaskProgress(message); if (!LocalReducedMotion.current) KitchenWhisper(message)
        }
        state.error?.let {
            KitchenError(it)
            if(state.lastCode!=null && state.result==null) QuietButton(onClick=model::retryLookup,enabled=!locked) { Text("Try lookup again") }
        }
        // This call remains in composition during review; analysis pauses without losing the session.
        CameraScanner(onCodes=model::detected,active=state.result==null && state.candidates.isEmpty() && !locked && !manualExpanded)
        if(product!=null) {
            LaunchedEffect(product) { stock.state.first { !it.busy }; stock.select(product) }
            ScannerStockReview(stockState,product,stock,account.permissions,state.action)
            if(stockState.bookingOperation!=null || stockState.operations.any { it.path=="/stock/products/$product/${state.action.endpoint}" && it.state in setOf("pending","guarded","in-flight","needs-review") }) QuietButton(onClick=onReviewChanges,enabled=!locked) { Text("Check changes") }
        } else if(state.result!=null) {
            val result=state.result!!
            if(result.productIds.isNotEmpty()) {
                Text("Choose a product",style=MaterialTheme.typography.titleLarge)
                result.productIds.forEach { id ->
                    val name=stockState.rows("/objects/products").firstOrNull { it.text("id")==id.toString() }?.text("name") ?: "Product $id"
                    SecondaryButton(onClick={model.chooseProduct(id)},enabled=!locked,modifier=Modifier.fillMaxWidth()) { Text(name) }
                }
            } else if(HouseholdAccess.has(account.permissions,"MASTER_DATA_EDIT")) {
                val products=stockState.rows("/objects/products").mapNotNull { row -> row.text("id").toLongOrNull()?.let { it to row.text("name").ifBlank { "Product $it" } } }
                if(products.isEmpty()) LaunchedEffect(result.code.raw) { stock.refresh() }
                ProductReview(result,state.units,state.locations,products,state.busy || state.createOperation!=null,model::create,model::attach)
                if(creationLocked) QuietButton(onClick=model::checkCreation,enabled=!locked) { Text("Check product status") }
                if(state.createOperation!=null) QuietButton(onClick=onReviewChanges,enabled=!locked) { Text("Check changes") }
            } else Text("No product matches this barcode. Ask someone with product-edit access to add it in Grocy.")
        } else if(state.candidates.isNotEmpty()) {
            Text("Choose a barcode",style=MaterialTheme.typography.titleLarge)
            state.candidates.forEach { code -> SecondaryButton(onClick={model.choose(code)},enabled=!locked,modifier=Modifier.fillMaxWidth()) { Text(code.raw) } }
            QuietButton(onClick={next(false)},enabled=!locked) { Text("Cancel") }
        } else {
            QuietButton(onClick={manualExpanded=!manualExpanded},enabled=!locked) { Text(if(manualExpanded) "Hide manual entry" else "Enter barcode") }
            if(manualExpanded) {
                LabeledTextField(manual,{manual=it},label="Barcode or QR text",singleLine=true,modifier=Modifier.fillMaxWidth().testTag("manual-barcode"))
                ChoiceField("Code format",listOf(0L to "Barcode",1L to "UPC-E",2L to "QR or household code"),when(manualFormat) { ScanFormat.UpcE->1L;ScanFormat.Qr->2L;else->0L }, { value->manualFormat=when(value) { 1L->ScanFormat.UpcE;2L->ScanFormat.Qr;else->ScanFormat.Manual } },enabled=!locked)
                PrimaryButton(onClick={model.manual(manual,manualFormat)},enabled=!locked && manual.isNotBlank(),modifier=Modifier.fillMaxWidth().testTag("manual-lookup")) { Text("Find product") }
            }
        }
        if(state.result!=null || product!=null) {
            QuietButton(onClick={next(false)},enabled=!locked && !creationLocked) { Text("Scan next") }

        }
        if(state.lastCode!=null) QuietButton(onClick={next(true)},enabled=!locked && !creationLocked && repeatAllowed) { Text("Scan same code again") }
    }
}
@Composable
private fun ProductReview(result:ScanSuggestion,units:List<Pair<Long,String>>,locations:List<Pair<Long,String>>,products:List<Pair<Long,String>>,busy:Boolean,create:(ScanReview)->Unit,attach:(Long)->Unit) {
    var name by remember(result.code.raw) { mutableStateOf(result.name) }
    var description by remember(result.code.raw) { mutableStateOf(result.description) }
    var unit by remember(result.code.raw) { mutableStateOf<Long?>(null) }
    var location by remember(result.code.raw) { mutableStateOf<Long?>(null) }
    var existing by remember(result.code.raw) { mutableStateOf<Long?>(null) }
    Text("Review new product",style=MaterialTheme.typography.titleLarge)
    Text("Review the details before creating this product.")
    if(result.source!="Manual review") Text("Suggested by ${result.source}" + if(result.stale) " · last saved suggestion" else "",color=MaterialTheme.colorScheme.onSurfaceVariant)
    LabeledTextField(name,{name=it},label="Product name (required)",enabled=!busy,isError=name.length>200,supportingText=if(name.length>200) "Use 200 characters or fewer" else null,modifier=Modifier.fillMaxWidth())
    LabeledTextField(description,{description=it},label="Description or brand (optional)",enabled=!busy,isError=description.length>5000,supportingText=if(description.length>5000) "Use 5,000 characters or fewer" else null,modifier=Modifier.fillMaxWidth())
    ChoiceField("Stock unit (required)",units,unit,{unit=it},enabled=!busy)
    ChoiceField("Location (required)",locations,location,{location=it},enabled=!busy)
    if(units.isEmpty() || locations.isEmpty()) Text("Create the required unit or location in Grocy, then look up the code again.")
    if(name.isBlank() || unit==null || location==null) Text("Enter a name and choose a stock unit and location.",color=MaterialTheme.colorScheme.onSurfaceVariant)
    PrimaryButton(onClick={create(ScanReview(result.code,name,description,unit!!,location!!))},enabled=!busy && name.isNotBlank() && name.length<=200 && description.length<=5000 && unit!=null && location!=null,modifier=Modifier.fillMaxWidth()) { Text("Create product") }
    Text("Or add this barcode to a product already in Grocy.", style=MaterialTheme.typography.titleMedium)
    if(products.isEmpty()) Text("Loading products…")
    else ChoiceField("Existing product", products, existing, { existing = it }, enabled = !busy)
    Text("A wrong barcode stays on that product until it is removed in Grocy. Stillroom cannot delete it.", color=MaterialTheme.colorScheme.onSurfaceVariant)
    SecondaryButton(onClick={existing?.let(attach)},enabled=!busy && existing!=null,modifier=Modifier.fillMaxWidth()) { Text("Attach barcode") }
}
