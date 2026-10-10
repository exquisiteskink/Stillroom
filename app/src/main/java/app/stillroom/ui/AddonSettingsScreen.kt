package app.stillroom.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.stillroom.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

@Composable
fun AddonSettingsScreen(model:AddonViewModel) {
    val state by model.state.collectAsState()
    val account=state.account ?: return
    var draft by remember(account.id,state.settings) { mutableStateOf(state.settings) }
    var key by remember(account.id) { mutableStateOf("") }
    var clearKey by remember(account.id) { mutableStateOf(false) }
    var showCustom by remember(account.id) { mutableStateOf(false) }
    val admin=HouseholdAccess.has(account.permissions,"ADMIN")
    val uri=LocalUriHandler.current
    var browserError by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Grocy add-ons",style=MaterialTheme.typography.titleLarge)
        Text("Changes from other Grocy clients are checked while Stillroom is open. Web add-ons open in your browser.")
        if(state.observation.stale)Text("Using saved server settings. External changes could not be fully refreshed.")
        state.observation.changedTime?.let { Text("Grocy last changed: $it",style=MaterialTheme.typography.bodySmall) }
        if(state.observation.capabilities.disabled.isNotEmpty())Text("Disabled in Grocy: ${state.observation.capabilities.disabled.sorted().joinToString()}")
        QuietButton(onClick=model::refresh,enabled=!state.busy) { Text("Check external changes") }
        SettingToggle("Use public barcode lookup",draft.publicLookup,supportingText="Use Open Food Facts when Grocy's lookup cannot find a product.",onChange={draft=draft.copy(publicLookup=it)})
        Text("Shopping purchases",style=MaterialTheme.typography.titleMedium)
        ChoiceField("Who adds purchases to pantry?",listOf(0L to "Stillroom checkbox",1L to "Another scanner or importer"),if(draft.shoppingPurchaseOwner=="external")1L else 0L,{draft=draft.copy(shoppingPurchaseOwner=if(it==1L)"external" else "stillroom")},enabled=!state.busy)
        Text(if(draft.shoppingPurchaseOwner=="external")"Checking a grocery item only crosses it out. Your other tool must add the purchase to Grocy." else "Checking a grocery item adds its quantity to Grocy stock.",style=MaterialTheme.typography.bodySmall)
        LabeledTextField(draft.webAddonUrl,{draft=draft.copy(webAddonUrl=it)},"Web add-on URL (optional)",singleLine=true,modifier=Modifier.fillMaxWidth())
        SettingToggle("Allow HTTP for web add-on",draft.webAddonInsecure,onChange={draft=draft.copy(webAddonInsecure=it)})
        if(draft.webAddonUrl.isNotBlank())QuietButton(onClick={
            try { uri.openUri(validateAddonWebUrl(draft.webAddonUrl,draft.webAddonInsecure));browserError=null }
            catch(_:Exception){browserError="Could not open this add-on URL. Check its address and HTTP setting."}
        }) { Text("Open web add-on") }
        browserError?.let { KitchenError(it) }
        if(admin) {
            Text("BarcodeBuddy",style=MaterialTheme.typography.titleMedium)
            Text("Use its API URL and separate API key. Scans use BarcodeBuddy's current mode; Stillroom does not change that shared mode.")
            LabeledTextField(draft.barcodeBuddyUrl,{draft=draft.copy(barcodeBuddyUrl=it)},"BarcodeBuddy URL (optional)",singleLine=true,modifier=Modifier.fillMaxWidth())
            SettingToggle("Allow HTTP for BarcodeBuddy",draft.barcodeBuddyInsecure,onChange={draft=draft.copy(barcodeBuddyInsecure=it)})
            OutlinedTextField(key,{key=it},label={Text(if(state.settings.barcodeBuddyConfigured)"Replace BarcodeBuddy API key" else "BarcodeBuddy API key")},singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
            if(state.settings.barcodeBuddyConfigured)SettingToggle("Remove saved BarcodeBuddy key",clearKey,onChange={clearKey=it})
            QuietButton(onClick=model::testBarcodeBuddy,enabled=!state.busy && state.settings.barcodeBuddyConfigured && state.settings.barcodeBuddyUrl.isNotBlank()) { Text("Check BarcodeBuddy connection and mode") }
        }
        val valid=runCatching { draft.validated() }.isSuccess
        PrimaryButton(onClick={model.save(draft,key,clearKey);key=""},enabled=!state.busy && valid,modifier=Modifier.fillMaxWidth()) { Text("Save add-on settings") }
        state.error?.let { KitchenError(it) };state.message?.let { Text(it) }
        state.receipts.forEach { (id,barcode)->
            Text("Uncertain BarcodeBuddy scan: $barcode. Check BarcodeBuddy and Grocy before scanning it again.")
            QuietButton(onClick={model.reviewReceipt(id)},enabled=!state.busy) { Text("I checked this scan's outcome") }
        }
        if(HouseholdAccess.has(account.permissions,"MASTER_DATA_EDIT")) {
            HorizontalDivider()
            QuietButton(onClick={showCustom=!showCustom;if(showCustom)model.loadCustom()},enabled=!state.busy) { Text(if(showCustom)"Hide custom records" else "Manage custom records") }
            if(showCustom)CustomRecords(model,state)
        }
    }
}

@Composable
private fun CustomRecords(model:AddonViewModel,state:AddonUiState) {
    var deleting by remember { mutableStateOf<Pair<JsonObject,Long>?>(null) }
    if(state.custom.stale)Text("Showing saved custom records. Editing needs a fresh server connection.")
    if(state.custom.entities.isNotEmpty())ChoiceField("Custom entity",state.custom.entities.mapNotNull { row->row.catalogId("id")?.let { it to row.catalogText("caption").ifBlank { row.catalogText("name") } } },state.custom.selectedEntity,{model.loadCustom(it)},enabled=!state.busy)
    state.custom.entities.filter { it.catalogId("id")==state.custom.selectedEntity }.forEach { entity->
        Text(entity.catalogText("caption").ifBlank { entity.catalogText("name") },style=MaterialTheme.typography.titleMedium)
        QuietButton(onClick={model.edit(entity,null)},enabled=!state.busy) { Text("Add record") }
        val fields=UserfieldDefinition.parse(state.custom.fields,"userentity-"+entity.catalogText("name"))
        state.custom.objects.filter { it.catalogId("userentity_id")==entity.catalogId("id") }.forEach { row->
            val id=row.catalogId("id") ?: return@forEach
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                val values=row["userfields"] as? JsonObject
                val label=fields.filter { it.canonicalType==UserfieldTypes.TEXT }.firstNotNullOfOrNull { field->(values?.get(field.name) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } } ?: "Record $id"
                SecondaryButton(onClick={model.edit(entity,id)},enabled=!state.busy,modifier=Modifier.weight(1f)) { Text(label) }
                QuietButton(onClick={deleting=entity to id},enabled=!state.busy) { Text("Remove") }
            }
        }
    }
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        if(state.custom.offset>0)QuietButton(onClick={model.loadCustom(state.custom.selectedEntity,(state.custom.offset-50).coerceAtLeast(0))},enabled=!state.busy){Text("Previous records")}
        if(state.custom.hasMore)QuietButton(onClick={model.loadCustom(state.custom.selectedEntity,state.custom.offset+50)},enabled=!state.busy){Text("More records")}
    }
    if(state.custom.entities.isEmpty() && !state.busy)Text("No custom entities found. Create their definitions in Grocy.")
    deleting?.let { (entity,id)->AlertDialog(onDismissRequest={deleting=null},title={Text("Remove custom record?")},text={Text("Remove record $id from Grocy?")},dismissButton={QuietButton(onClick={deleting=null}){Text("Cancel")}},confirmButton={PrimaryButton(onClick={model.deleteCustom(entity,id);deleting=null}){Text("Remove")}}) }
    if(state.entity!=null && state.values!=null)key(state.entity.catalogId("id"),state.objectId) { CustomRecordEditor(model,state) }
}
@Composable
private fun CustomRecordEditor(model:AddonViewModel,state:AddonUiState) {
    val entity=state.entity!!
    val fields=UserfieldDefinition.parse(state.custom.fields,"userentity-"+entity.catalogText("name"))
    var changes by remember { mutableStateOf<Map<String,String>>(emptyMap()) }
    val initial=remember { fields.associate { it.name to UserfieldValues.initial(it,(state.values?.get(it.name) as? JsonPrimitive)?.contentOrNull,state.objectId==null) } }
    val locked=state.busy || state.operation!=null
    AlertDialog(onDismissRequest=model::closeEditor,title={Text(if(state.objectId==null)"Add custom record" else "Edit custom record")},text={
        Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            fields.forEach { field->
                val value=changes[field.name] ?: initial[field.name].orEmpty()
                if(field.canonicalType in setOf(UserfieldTypes.FILE,UserfieldTypes.IMAGE)) {
                    Text(field.label)
                    GrocyMediaPreview("userfiles",value,field.canonicalType==UserfieldTypes.IMAGE)
                    UserfileUpload(model,field,locked) { changes=changes+(field.name to it) }
                } else UserfieldEditor(field,value,{changes=changes+(field.name to it)},!locked)
            }
            state.error?.let { KitchenError(it) };state.message?.let { Text(it) }
            if(state.operation!=null)Text("This save has already been submitted. Check its status before starting another.")
        }
    },dismissButton={QuietButton(onClick=model::closeEditor,enabled=!state.busy){Text("Close")}},confirmButton={
        val editable=fields.filter { UserfieldTypes.editable(it.type) || it.canonicalType in setOf(UserfieldTypes.FILE,UserfieldTypes.IMAGE) }
        val valid=editable.all { field->val value=changes[field.name] ?: initial[field.name].orEmpty();if(field.canonicalType in setOf(UserfieldTypes.FILE,UserfieldTypes.IMAGE))!field.inputRequired || value.isNotBlank() else UserfieldValues.error(field,value)==null }
        PrimaryButton(onClick={model.saveCustom(buildJsonObject { for(field in editable) {
            val value=changes[field.name] ?: initial[field.name].orEmpty()
            if((state.objectId==null && (UserfieldTypes.editable(field.type) || field.name in changes)) || field.name in changes && value!=initial[field.name])put(field.name,value)
        } })},enabled=!state.busy && valid && (state.objectId==null || changes.isNotEmpty() || state.operation!=null)) { Text(if(state.operation==null)"Save" else "Check save status") }
    })
}

@Composable
private fun UserfileUpload(model:AddonViewModel,field:UserfieldDefinition,disabled:Boolean,onUploaded:(String)->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri->if(uri!=null)scope.launch {
        busy=true;error=null
        try {
            val displayName=withContext(Dispatchers.IO) {
                context.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use { cursor->if(cursor.moveToFirst())cursor.getString(0) else null }
            }
            val (bytes,extension)=withContext(Dispatchers.IO) {
                val mime=context.contentResolver.getType(uri)
                val ext=android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
                val bytes=context.contentResolver.openInputStream(uri)!!.use { input->
                    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(true) { val count=input.read(buffer);if(count<0)break;require(output.size()+count<=5_242_880) { "Choose a file smaller than 5 MB." };output.write(buffer,0,count) }
                    output.toByteArray()
                }
                require(bytes.isNotEmpty() && bytes.size<=5_242_880) { "Choose a file smaller than 5 MB." }
                if(field.canonicalType==UserfieldTypes.IMAGE) {
                    val bounds=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true };android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                    require(bounds.outWidth>0 && bounds.outHeight>0) { "Choose an image." }
                }
                bytes to ext
            }
            onUploaded(model.upload(bytes,extension,displayName))
        }catch(e:CancellationException){throw e}catch(_:Exception){error="Upload was not confirmed. Check Grocy before uploading again."}finally{busy=false}
    } }
    QuietButton(onClick={launcher.launch(arrayOf(if(field.canonicalType==UserfieldTypes.IMAGE)"image/*" else "*/*"))},enabled=!disabled && !busy) { Text(if(busy)"Uploading…" else "Upload ${field.label}") }
    error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
}

@Composable
internal fun GrocyMediaPreview(group:String,name:String,image:Boolean) {
    val model=LocalAddonModel.current ?: return
    if(name.isBlank())return
    val account by model.state.collectAsState()
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var bytes by remember(account.account?.id,group,name) { mutableStateOf<ByteArray?>(null) }
    var error by remember(account.account?.id,group,name) { mutableStateOf<String?>(null) }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri->
        val data=bytes
        if(uri!=null && data!=null)scope.launch { try { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)!!.use { it.write(data) } } }catch(_:Exception){error="Could not save the file."} }
    }
    LaunchedEffect(account.account?.id,group,name,image) { if(image)try { bytes=model.media(group,name) }catch(e:CancellationException){throw e}catch(_:Exception){error="Image unavailable."} }
    val bitmap=rememberDownsampledImage(bytes,TILE_DECODED_PIXELS)
    if(image && bitmap!=null)Image(bitmap,name,Modifier.fillMaxWidth().heightIn(max=180.dp))
    if(!image)QuietButton(onClick={scope.launch { try { bytes=model.media(group,name);export.launch(if(group=="userfiles")UserfileReference.parse(name).displayName else name) }catch(e:CancellationException){throw e}catch(_:Exception){error="File unavailable."} }}) { Text("Save ${if(group=="userfiles")UserfileReference.parse(name).displayName else name}") }
    error?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
internal fun GrocyBrowserButton(label:String,path:String) {
    val model=LocalAddonModel.current ?: return
    val state by model.state.collectAsState()
    val account=state.account ?: return
    val uri=LocalUriHandler.current
    var error by remember { mutableStateOf<String?>(null) }
    QuietButton(onClick={try { uri.openUri(account.address.apiBase.removeSuffix("/api")+"/"+path) }catch(_:Exception){error="Could not open Grocy in the browser."}}) { Text(label) }
    error?.let { Text(it) }
}
