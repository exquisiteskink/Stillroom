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
    s.definitions(entity.entity).mapNotNull(CustomFields::reason).forEach { Text(it) }
    if(entity.writable(account.permissions))PrimaryButton(onClick={create=true},enabled=!state.busy){Text("Add ${entity.singular()}")}
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
            s.definitions(entity.entity).filter(CustomFields::supported).forEach { field->Text("${field.catalogText("caption")}: ${values?.catalogText(field.catalogText("name")).orEmpty()}") }
            if(entity.writable(account.permissions))Row { QuietButton(onClick={edit=row},enabled=!state.busy){Text("Edit")};QuietButton(onClick={deletion=id},enabled=!state.busy){Text("Delete")} }
        } }
    }
    }
    if(create || edit!=null)CatalogEditor(entity,edit,s,{create=false;edit=null}) { fields,custom->model.save(entity,edit?.catalogId("id"),fields,custom);create=false;edit=null }
    deletion?.let { id->AlertDialog(onDismissRequest={deletion=null},title={Text("Delete ${entity.singular()}?")},text={Text("Grocy checks references and may reject deletion of a record still in use.")},dismissButton={QuietButton(onClick={deletion=null}){Text("Cancel")}},confirmButton={PrimaryButton(onClick={model.delete(entity,id);deletion=null}){Text("Delete")}}) }
    state.history?.let { rows->AlertDialog(onDismissRequest=model::closeHistory,title={Text("Charge cycles")},text={Column(Modifier.verticalScroll(rememberScrollState())) { if(rows.isEmpty())Text("No charges recorded.")
        rows.forEach { row->Text("${row.catalogText("tracked_time")} · ${if(row.catalogText("undone")=="1")"Undone" else "Confirmed"}")
        if(row.catalogText("undone")!="1" && HouseholdAccess.has(account.permissions,"BATTERIES_UNDO_CHARGE_CYCLE"))QuietButton(onClick={model.undo(row.catalogId("id")!!);model.closeHistory()},enabled=!state.busy){Text("Undo cycle")} }
    }},confirmButton={QuietButton(onClick=model::closeHistory){Text("Close")}}) }
}

@Composable internal fun CatalogEditor(entity:CatalogEntity,row:JsonObject?,s:CatalogSnapshot,close:()->Unit,save:(JsonObject,JsonObject)->Unit) {
    val definitions=remember(entity){CatalogFields.fields(entity)}
    var values by remember { mutableStateOf(definitions.associate { f->f.name to (row?.get(f.name) ?: if(f.kind==CatalogKind.Toggle)JsonPrimitive(if(f.name=="active")1 else 0) else JsonNull) }) }
    val amounts=remember { definitions.filter { it.kind==CatalogKind.Decimal }.associate { field->
        val original=(row?.get(field.name) as? JsonPrimitive)?.contentOrNull?.toBigDecimalOrNull()
        field.name to original?.let { RecipeAmountInput(it,Locale.getDefault()) }
    } }
    var decimalTexts by remember { mutableStateOf(amounts.mapValues { it.value?.text.orEmpty() }) }
    var advanced by remember { mutableStateOf(false) }
    val basicProductFields = setOf("name", "description", "location_id", "qu_id_stock", "qu_id_purchase", "qu_id_consume", "qu_id_price")
    val custom=s.definitions(entity.entity)
    val original=row?.get("userfields") as? JsonObject
    var customValues by remember { mutableStateOf(custom.filter(CustomFields::supported).associate { it.catalogText("name") to (original?.catalogText(it.catalogText("name")) ?: it.catalogText("default_value")) }) }
    fun payload():JsonObject=buildJsonObject { for(f in definitions) {
        val value=if(f.kind==CatalogKind.Decimal) {
            val text=decimalTexts[f.name].orEmpty();val input=amounts[f.name]
            when {
                text.isBlank()->JsonNull
                input!=null->Json.parseToJsonElement(input.saved().toPlainString())
                else->Json.parseToJsonElement((QuantityFractions().parse(text,Locale.getDefault())?.value ?: error("Enter a quantity.")).toPlainString())
            }
        } else values.getValue(f.name)
        if(value!=JsonNull || f.kind==CatalogKind.Reference)put(f.name,value)
    } }
    val valid=runCatching {
        val fields=payload();CatalogFields.validate(entity,fields)
        require(definitions.filter { it.required }.all { fields[it.name]!=null && fields[it.name]!=JsonNull })
        custom.filter(CustomFields::supported).forEach { CustomFields.validate(it,customValues[it.catalogText("name")].orEmpty()) }
        require(row!=null || custom.none { !CustomFields.supported(it) && it.catalogText("input_required")=="1" })
    }.isSuccess
    AlertDialog(onDismissRequest=close,title={Text(if(row==null)"Add ${entity.singular()}" else "Edit ${entity.singular()}")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        definitions.filter { entity != CatalogEntity.Products || advanced || it.name in basicProductFields }.forEach { f->when(f.kind) {
            CatalogKind.Reference->CatalogReference(f.label,s.rows(f.reference!!),values[f.name]?.jsonPrimitive?.longOrNull,!f.required) { id->values=values+(f.name to (id?.let(::JsonPrimitive) ?: JsonNull)) }
            CatalogKind.Toggle->RecordToggle(f.label, values[f.name]?.jsonPrimitive?.content=="1") { checked->values=values+(f.name to JsonPrimitive(if(checked)1 else 0)) }
            CatalogKind.Decimal->LabeledTextField(decimalTexts[f.name].orEmpty(),{text->amounts[f.name]?.change(text);decimalTexts=decimalTexts+(f.name to text)},label = f.label)
            else->LabeledTextField((values[f.name] as? JsonPrimitive)?.contentOrNull.orEmpty(),{text->values=values+(f.name to if(f.kind==CatalogKind.Whole && text.toLongOrNull()!=null)JsonPrimitive(text.toLong()) else JsonPrimitive(text))},label = f.label, isError = !runCatching { val fieldValue=values.getValue(f.name); if(f.required)require(fieldValue!=JsonNull && (fieldValue as? JsonPrimitive)?.contentOrNull?.isNotBlank()==true); if(fieldValue!=JsonNull)CatalogFields.validate(entity,JsonObject(mapOf(f.name to fieldValue))) }.isSuccess, supportingText = if(f.required) "Required" else null)
        } }
        if(entity==CatalogEntity.Products)QuietButton(onClick={advanced=!advanced}){Text(if(advanced) "Hide advanced product settings" else "Advanced product settings")}
        if(entity==CatalogEntity.Products)QuietButton(onClick={values["qu_id_stock"]?.takeIf { it!=JsonNull }?.let { stock->values=values+listOf("qu_id_purchase","qu_id_consume","qu_id_price").associateWith { stock } }}){Text("Use selected stock unit for purchase, consume and price")}
        custom.forEach { field->val key=field.catalogText("name");val reason=CustomFields.reason(field)
            if(reason!=null)Text(reason) else if(field.catalogText("type")=="checkbox")RecordToggle(field.catalogText("caption"),customValues[key]=="1"){customValues=customValues+(key to if(it)"1" else "0")}
            else LabeledTextField(customValues[key].orEmpty(),{customValues=customValues+(key to it)},label = field.catalogText("caption")+if(field.catalogText("input_required")=="1")" (required)" else "")
        }
        if(!valid)Text("Check required fields and values before saving.",color=MaterialTheme.colorScheme.error)
        if(row!=null)Text("Additional server fields and unsupported custom values remain unchanged.")
    }},dismissButton={QuietButton(onClick=close){Text("Cancel")}},confirmButton={PrimaryButton(onClick={save(payload(),buildJsonObject { customValues.forEach { (key,value)->put(key,value) } })},enabled=valid){Text("Save")}})
}
@Composable private fun CatalogReference(label:String,rows:List<JsonObject>,selected:Long?,nullable:Boolean,choose:(Long?)->Unit) {
    ChoiceField(label, rows.mapNotNull { row -> row.catalogId("id")?.let { it to row.catalogText("name") } }, selected, choose, allowNone=nullable)
}

@Composable private fun RecordToggle(label:String,checked:Boolean,onChange:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(value=checked,role=Role.Checkbox,onValueChange=onChange),verticalAlignment=Alignment.CenterVertically) {
        Checkbox(checked,null)
        Text(label,Modifier.weight(1f))
    }
}
