package app.stillroom.ui

import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.stillroom.domain.*
import app.stillroom.fractions.QuantityFractions
import kotlinx.serialization.json.*
import java.util.Locale

private fun CatalogEntity.singular(): String = when(this) {
    CatalogEntity.Batteries -> "battery"; CatalogEntity.Equipment -> "equipment"; CatalogEntity.Products -> "product"; CatalogEntity.Locations -> "location"; CatalogEntity.Stores -> "store"; CatalogEntity.Units -> "quantity unit"; CatalogEntity.Conversions -> "unit conversion"; CatalogEntity.Categories -> "task category"
}

private enum class HouseholdTab { Chores, Tasks, Catalog }

@Composable fun HouseholdHub(household:HouseholdViewModel,catalog:CatalogViewModel?,account:Account) {
    var tab by remember { mutableStateOf(HouseholdTab.Chores) }
    val hasCatalog=catalog!=null && CatalogEntity.entries.any { it.readable(account.permissions) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=16.dp, vertical=8.dp),
            horizontalArrangement=Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(tab==HouseholdTab.Chores, { tab=HouseholdTab.Chores }, { Text("Chores") })
            FilterChip(tab==HouseholdTab.Tasks, { tab=HouseholdTab.Tasks }, { Text("Tasks") })
            if(hasCatalog) FilterChip(tab==HouseholdTab.Catalog, { tab=HouseholdTab.Catalog }, { Text("Records") })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                HouseholdTab.Catalog -> if (catalog != null) CatalogScreen(catalog, account)
                HouseholdTab.Tasks -> HouseholdTasksScreen(household, account)
                HouseholdTab.Chores -> HouseholdChoresScreen(household, account)
            }
        }
    }
}
@Composable fun CatalogScreen(model:CatalogViewModel,account:Account) {
    val state by model.state.collectAsState();val s=state.snapshot
    val entities=CatalogEntity.entries.filter { it.readable(account.permissions) }
    if(entities.isEmpty()){PermissionDeniedState();return}
    var entity by remember { mutableStateOf(entities.first()) };var menu by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<JsonObject?>(null) };var create by remember { mutableStateOf(false) };var deletion by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit){model.refresh()}
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) {
    ChoiceField("Record type", entities.map { it.ordinal.toLong() to it.label }, entity.ordinal.toLong(), { id -> entities.firstOrNull { it.ordinal.toLong() == id }?.let { entity = it } })
    if(state.busy)TaskProgress()
    KitchenError(state.error)
    KitchenWhisper(if(s.stale)"Showing the last saved records." else null)
    s.unavailable.forEach { Text(it) }
    if(entity.writable(account.permissions))PrimaryButton(onClick={create=true},enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text("Add ${entity.singular()}")}
    if(s.rows(entity.entity).isEmpty()) {
        when {
            state.busy -> Text("Loading ${entity.label.lowercase()}…")
            state.error != null -> ErrorState(state.error!!, model::refresh)
            s.unavailable.isNotEmpty() -> ErrorState("Some records are unavailable from Grocy.", model::refresh)
            else -> Text("No ${entity.label.lowercase()} yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    state.operations.filter { it.state in setOf("pending", "in-flight", "needs-review", "failed") }.forEach { operation ->
        Text(when(operation.state) { "pending" -> "Change pending sync"; "in-flight" -> "Saving change…"; "failed" -> "Could not save change"; else -> "Change needs review in Settings → Pending changes" }, color = if(operation.state in setOf("failed", "needs-review")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    s.rows(entity.entity).forEach { row->val id=row.catalogId("id")!!
        Card(Modifier.fillMaxWidth().padding(vertical=4.dp)) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(entity==CatalogEntity.Conversions) {
                fun name(e:String,key:String)=s.rows(e).find { it.catalogId("id")==row.catalogId(key) }?.catalogText("name").orEmpty()
                Text("${name("quantity_units","from_qu_id")} → ${name("quantity_units","to_qu_id")} × ${row.catalogText("factor")}")
                Text(name("products","product_id").ifBlank { "Global conversion" })
            } else Text(row.catalogText("name"),style=MaterialTheme.typography.titleLarge)
            if(row.catalogText("description").isNotBlank())Text(androidx.core.text.HtmlCompat.fromHtml(row.catalogText("description"),androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY).toString())
            if(entity==CatalogEntity.Equipment && row.catalogText("instruction_manual_file_name").isNotBlank())Text("Manual: ${row.catalogText("instruction_manual_file_name")} · available in Grocy")
            if(entity==CatalogEntity.Batteries) {
                Text("Used in: ${row.catalogText("used_in")}")
                val status=s.rows("battery_status").find { it.catalogId("battery_id")==id }
                Text("Last charged: ${status?.catalogText("last_tracked_time").orEmpty().ifBlank { "Never" }}")
                Text("Next charge: ${status?.catalogText("next_estimated_charge_time").orEmpty().ifBlank { "Unscheduled" }}")
                val waiting=state.operations.any { it.path=="/batteries/$id/charge" && it.state in setOf("pending","in-flight","needs-review") }
                if(HouseholdAccess.has(account.permissions,"BATTERIES_TRACK_CHARGE_CYCLE"))SecondaryButton(onClick={model.charge(id)},enabled=!state.busy && !waiting){Text(if(waiting)"Awaiting confirmation" else "Record charge")}
                if(HouseholdAccess.has(account.permissions,"BATTERIES"))QuietButton(onClick={model.history(id)},enabled=!state.busy){Text("Charge history")}
            }
            val values=row["userfields"] as? JsonObject
            s.userfields(entity.entity).forEach { field->UserfieldText.render(field.type,values?.get(field.name),LocalQuantityFormatter.current,Locale.getDefault())?.let { Text("${field.label}: $it") } }
            if(entity.writable(account.permissions))Row { QuietButton(onClick={edit=row},enabled=!state.busy){Text("Edit")};QuietButton(onClick={deletion=id},enabled=!state.busy){Text("Delete")} }
        } }
    }
    }
    if(create || edit!=null)CatalogEditor(entity,edit,s,state.busy,state.outcome,{create=false;edit=null;model.clearOutcome()}) { fields,custom,extras->model.save(entity,edit?.catalogId("id"),fields,custom,extras) }
    // Close only when Grocy confirmed the save; after a partial create, keep editing the new record.
    LaunchedEffect(state.outcome, s) {
        when(val outcome=state.outcome) {
            is CatalogSaveOutcome.Saved->{create=false;edit=null;model.clearOutcome()}
            is CatalogSaveOutcome.Partial->s.rows(entity.entity).find { it.catalogId("id")==outcome.id }?.let { edit=it;create=false }
            else->Unit
        }
    }
    deletion?.let { id->AlertDialog(onDismissRequest={deletion=null},title={Text("Delete ${entity.singular()}?")},text={Text("Grocy checks references and may reject deletion of a record still in use.")},dismissButton={QuietButton(onClick={deletion=null}){Text("Cancel")}},confirmButton={PrimaryButton(onClick={model.delete(entity,id);deletion=null}){Text("Delete")}}) }
    state.history?.let { rows->AlertDialog(onDismissRequest=model::closeHistory,title={Text("Charge cycles")},text={Column(Modifier.verticalScroll(rememberScrollState())) { if(rows.isEmpty())Text("No charges recorded.")
        rows.forEach { row->Text("${row.catalogText("tracked_time")} · ${if(row.catalogText("undone")=="1")"Undone" else "Confirmed"}")
        if(row.catalogText("undone")!="1" && HouseholdAccess.has(account.permissions,"BATTERIES_UNDO_CHARGE_CYCLE"))QuietButton(onClick={model.undo(row.catalogId("id")!!);model.closeHistory()},enabled=!state.busy){Text("Undo cycle")} }
    }},dismissButton={QuietButton(onClick=model::closeHistory){Text("Close")}},confirmButton={}) }
}

/**
 * The one record editor: Household → Records for every record type, and Pantry → Add product /
 * Edit product for products. Stays open until Grocy confirms the save ([CatalogSaveOutcome.Saved]);
 * on failure the entries are kept and the reason is shown. Tapping outside does not discard it.
 */
@Composable internal fun CatalogEditor(
    entity:CatalogEntity,row:JsonObject?,s:CatalogSnapshot,busy:Boolean,outcome:CatalogSaveOutcome?,
    close:()->Unit,save:(JsonObject,JsonObject,ProductExtras)->Unit,
) {
    val quantities=LocalQuantityFormatter.current
    val locale=Locale.getDefault()
    val form=remember(row) { CatalogForm(entity,row,quantities,locale) }
    val startedCreate=remember { row==null }
    var texts by remember { mutableStateOf(form.initial()) }
    var advanced by remember { mutableStateOf(false) }
    val basicProductFields = setOf("name", "description", "product_group_id", "location_id", "qu_id_stock", "qu_id_purchase", "qu_id_consume", "qu_id_price")
    val custom=s.userfields(entity.entity)
    val original=row?.get("userfields") as? JsonObject
    var touched by remember { mutableStateOf(mapOf<String,String>()) }
    val shownTexts=if(startedCreate && row!=null) CatalogCreateHandoff.seedUnits(texts) else texts
    val shownTouched=if(startedCreate && row!=null) UserfieldValues.createDefaults(custom,original,touched) else touched
    fun current(field:UserfieldDefinition)=shownTouched[field.name] ?: UserfieldValues.initial(field,(original?.get(field.name) as? JsonPrimitive)?.contentOrNull,row==null)
    // Product extras: purchase → stock factor and new barcodes.
    val product=entity==CatalogEntity.Products
    val id=row?.catalogId("id")
    val purchase=shownTexts["qu_id_purchase"]?.toLongOrNull(); val stock=shownTexts["qu_id_stock"]?.toLongOrNull()
    val currentFactor=ProductExtrasForm.currentFactor(s.rows("quantity_unit_conversions"),id,purchase,stock)
    var factorText by remember { mutableStateOf<String?>(null) }
    val shownFactor=factorText ?: currentFactor.stripTrailingZeros().toPlainString()
    var barcodesText by remember { mutableStateOf("") }
    val ownBarcodes=s.rows("product_barcodes").filter { id!=null && it.catalogId("product_id")==id }.map { it.catalogText("barcode") }
    val newBarcodes=ProductExtrasForm.parseBarcodes(barcodesText).filter { it !in ownBarcodes }
    val fieldErrors=form.errors(shownTexts)
    val customErrors=custom.filter { UserfieldTypes.editable(it.type) }.mapNotNull { f-> UserfieldValues.error(f,current(f),locale) }
    val extraErrors=if(product)ProductExtrasForm.errors(shownFactor,purchase,stock,newBarcodes,s.rows("product_barcodes").map { it.catalogText("barcode") },locale) else emptyMap()
    val blockedCreate=if(row==null)custom.firstOrNull { it.inputRequired && !UserfieldTypes.editable(it.type) } else null
    val problems=fieldErrors.values+customErrors+extraErrors.values+listOfNotNull(blockedCreate?.let { "Required custom field ${it.label} must be filled in Grocy's web app first." })
    val title=(if(row==null)"Add " else "Edit ")+entity.singular()
    AlertDialog(onDismissRequest={},properties=androidx.compose.ui.window.DialogProperties(dismissOnClickOutside=false),title={Text(title)},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        form.fields.filter { !product || advanced || it.name in basicProductFields }.forEach { f->
            val error=fieldErrors[f.name]
            val text=shownTexts[f.name].orEmpty()
            fun set(value:String){texts=texts+(f.name to value)}
            when(f.kind) {
                CatalogKind.Reference->{
                    val none=when(f.name){ "qu_id_consume"->"Same as stock unit"; "qu_id_price"->"Same as purchase unit"; else->"None" }
                    ChoiceField(f.label+if(form.optional(f))"" else " (required)",s.rows(f.reference!!).mapNotNull { r->r.catalogId("id")?.let { it to r.catalogText("name") } },text.toLongOrNull(),{ v->set(v?.toString().orEmpty()) },allowNone=form.optional(f),noneLabel=none,enabled=!busy)
                    error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                }
                CatalogKind.Toggle->CheckRow(f.label,text=="1",!busy) { set(if(it)"1" else "0") }
                else->LabeledTextField(text,::set,label=f.label+if(form.optional(f))"" else " (required)",enabled=!busy,isError=error!=null,
                    supportingText=error ?: when(f.kind){ CatalogKind.Decimal->"1.5 and 1½ both work"; else->null })
            }
        }
        if(product) {
            QuietButton(onClick={advanced=!advanced}){Text(if(advanced) "Hide advanced product settings" else "Advanced product settings")}
            if(purchase!=null && stock!=null && purchase!=stock) {
                val unitName={ u:Long-> s.rows("quantity_units").find { it.catalogId("id")==u }?.catalogText("name").orEmpty() }
                LabeledTextField(shownFactor,{ factorText=it },label="Stock units per ${unitName(purchase)} (${unitName(stock)})",enabled=!busy,isError=extraErrors["factor"]!=null,
                    supportingText=extraErrors["factor"] ?: "Saved as this product's ${unitName(purchase)} → ${unitName(stock)} conversion in Grocy.")
            }
            if(ownBarcodes.isNotEmpty())Text("Barcodes: "+ownBarcodes.joinToString(", "))
            LabeledTextField(barcodesText,{ barcodesText=it },label="Add barcodes",enabled=!busy,isError=extraErrors["barcodes"]!=null,
                supportingText=extraErrors["barcodes"] ?: "One per line. Existing barcodes are changed in Grocy's web app.")
        }
        if(custom.isNotEmpty())Text("Custom fields",style=MaterialTheme.typography.titleMedium)
        custom.forEach { field-> UserfieldEditor(field,current(field),{ touched=touched+(field.name to it) },enabled=!busy) }
        blockedCreate?.let { Text("Required custom field ${it.label} (${UserfieldText.typeLabel(it.type)}) cannot be filled here, so this record must be created in Grocy's web app.",color=MaterialTheme.colorScheme.error) }
        if(busy)TaskProgress()
        when(outcome) {
            is CatalogSaveOutcome.Failed->Text(outcome.message,color=MaterialTheme.colorScheme.error)
            is CatalogSaveOutcome.Partial->Text(outcome.message,color=MaterialTheme.colorScheme.error)
            is CatalogSaveOutcome.Unconfirmed->Text(outcome.message,color=MaterialTheme.colorScheme.error)
            else->Unit
        }
        if(problems.isNotEmpty() && !busy)Text("Check: "+problems.first(),color=MaterialTheme.colorScheme.error)
        if(row!=null)Text("Only changed values are sent. Other server fields, files and images stay as they are.",style=MaterialTheme.typography.bodySmall)
    }},dismissButton={QuietButton(onClick=close,enabled=!busy){Text("Cancel")}},confirmButton={PrimaryButton(onClick={
        runCatching {
            val fields=form.payload(shownTexts)
            val values=UserfieldValues.changes(custom,original,custom.associate { it.name to current(it) },locale)
            val extras=if(product)ProductExtrasForm.build(shownFactor,currentFactor,purchase,stock,newBarcodes,locale) else ProductExtras()
            save(fields,values,extras)
        }
    },enabled=problems.isEmpty() && !busy){Text(if(busy)"Saving…" else "Save")}})
}
@Composable private fun CatalogReference(label:String,rows:List<JsonObject>,selected:Long?,nullable:Boolean,choose:(Long?)->Unit) {
    ChoiceField(label, rows.mapNotNull { row -> row.catalogId("id")?.let { it to row.catalogText("name") } }, selected, choose, allowNone=nullable)
}

