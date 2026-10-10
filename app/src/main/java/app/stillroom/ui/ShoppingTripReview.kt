package app.stillroom.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.stillroom.domain.*
import app.stillroom.fractions.QuantityFractions
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

@Composable internal fun ShoppingTripReview(trip:ShoppingTrip,stock:StockUiState,busy:Boolean,
    edit:(String,BigDecimal,String?,BigDecimal?)->Unit,remove:(String)->Unit,submit:()->Unit,newTrip:()->Unit,refresh:()->Unit,changes:()->Unit,reviewing:(Boolean)->Unit={}) {
    var expanded by remember(trip.id) { mutableStateOf(false) }
    val inspect=expanded || trip.submitted
    LaunchedEffect(inspect) { reviewing(inspect) }
    DisposableEffect(Unit) { onDispose { reviewing(false) } }
    var editing by remember(trip.id) { mutableStateOf<TripLine?>(null) }
    var clear by remember(trip.id) { mutableStateOf(false) }
    Text("Shopping trip review",style=MaterialTheme.typography.titleLarge)
    Text(if(trip.submitted)"${trip.lines.count { it.state=="confirmed" }} of ${trip.lines.size} purchases confirmed in Grocy." else "Scan items and review each quantity. Stock changes only after you confirm the trip.")
    if(trip.lines.isNotEmpty() && !trip.submitted)QuietButton(onClick={expanded=!expanded},enabled=!busy) { Text(if(expanded)"Return to scanning" else "Review ${trip.lines.size} scanned items") }
    if(inspect)trip.lines.forEach { line ->
        HorizontalDivider()
        Text(line.name,style=MaterialTheme.typography.titleMedium)
        val unit=stock.rows("/objects/quantity_units").firstOrNull { it.text("id")==line.unit.toString() }?.text("name") ?: "stock units"
        Text("${quantityText(line.booking.amount)} $unit${if(line.tare!=null)" gross" else ""}${line.booking.date?.let { " · due $it" } ?: " · Grocy default due date"}")
        line.booking.price?.let { Text("${it.stripTrailingZeros().toPlainString()} per stock unit") }
        if(trip.submitted)Text(when(line.state) { "confirmed"->"Saved in Grocy";"needs-review","in-flight"->"Outcome uncertain · inspect Pending changes";"failed"->"Not saved · inspect Pending changes";else->"Not sent yet" })
        else Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            QuietButton(onClick={editing=line},enabled=!busy){Text("Edit ${line.name}")}
            QuietButton(onClick={remove(line.id)},enabled=!busy){Text("Remove ${line.name}")}
        }
    }
    if(trip.lines.isNotEmpty() && inspect) {
        val blocked=trip.lines.any { it.state in setOf("needs-review","in-flight","failed") }
        if(!trip.confirmed)PrimaryButton(onClick=submit,enabled=!busy && !blocked && LocalAddonSettings.current.shoppingPurchaseOwner=="stillroom",modifier=Modifier.fillMaxWidth()) {
            Text(if(trip.submitted)"Continue reviewed purchases" else "Confirm ${trip.lines.size} purchases")
        }
        if(LocalAddonSettings.current.shoppingPurchaseOwner=="external")Text("Another scanner owns purchases. Change purchase ownership in Add-on settings to book this trip.")
        if(trip.submitted) {
            QuietButton(onClick=refresh,enabled=!busy){Text("Refresh trip status")}
            QuietButton(onClick=changes,enabled=!busy){Text("Check changes")}
        }
        if(trip.confirmed)SecondaryButton(onClick=newTrip,enabled=!busy){Text("Start another trip")}
        else if(!trip.submitted)QuietButton(onClick={clear=true},enabled=!busy){Text("Discard trip draft")}
    }
    if(clear)AlertDialog(onDismissRequest={clear=false},title={Text("Discard this trip draft?")},text={Text("No stock has been booked from this draft.")},
        confirmButton={PrimaryButton(onClick={newTrip();clear=false},enabled=!busy){Text("Discard draft")}},dismissButton={QuietButton(onClick={clear=false}){Text("Cancel")}})
    editing?.let { line -> TripLineEditor(line,busy,{editing=null}) { amount,date,price -> edit(line.id,amount,date,price);editing=null } }
}

@Composable internal fun TripLineEditor(line:TripLine,busy:Boolean,close:()->Unit,save:(BigDecimal,String?,BigDecimal?)->Unit) {
    var amount by remember(line.id) { mutableStateOf(line.booking.amount.toPlainString()) }
    var date by remember(line.id) { mutableStateOf(line.booking.date.orEmpty()) }
    var price by remember(line.id) { mutableStateOf(line.booking.price?.toPlainString().orEmpty()) }
    val parsed=QuantityFractions().parse(amount,Locale.getDefault())?.value
    val valid=parsed?.signum()==1 && (line.tare==null || parsed>line.tare) && (date.isBlank() || runCatching { LocalDate.parse(date) }.isSuccess) && (price.isBlank() || price.toBigDecimalOrNull()?.signum()?.let { it>=0 }==true)
    AlertDialog(onDismissRequest=close,title={Text("Review ${line.name}")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(if(line.tare!=null)"Quantity is gross weight in the stock unit. Grocy subtracts tare weight ${line.tare}. Price is per net stock unit." else "Quantity is in this product’s stock unit. Price is per stock unit.")
        LabeledTextField(amount,{amount=it},"Stock quantity",isError=parsed?.signum()!=1,enabled=!busy)
        LabeledTextField(date,{date=it},"Due date (optional)",enabled=!busy)
        LabeledTextField(price,{price=it},"Price per stock unit (optional)",enabled=!busy)
    }},confirmButton={PrimaryButton(onClick={save(parsed!!,date.takeIf { it.isNotBlank() },price.takeIf { it.isNotBlank() }?.toBigDecimal())},enabled=valid && !busy){Text("Save")}},dismissButton={QuietButton(onClick=close){Text("Cancel")}})
}
