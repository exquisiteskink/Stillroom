package app.stillroom.domain

import kotlinx.serialization.json.*
import java.math.BigDecimal

data class TripLine(val id:String,val name:String,val unit:Long,val booking:StockBooking,val state:String="draft",val tare:BigDecimal?=null) {
    fun json()=buildJsonObject { put("id",id);put("name",name);put("unit",unit);put("product",booking.productId);put("payload",booking.payload());tare?.let { put("tare",it.toPlainString()) } }
    companion object {
        fun parse(row:JsonObject):TripLine {
            val payload=row.getValue("payload").jsonObject
            fun id(key:String)=payload[key]?.jsonPrimitive?.longOrNull
            fun text(key:String)=payload[key]?.jsonPrimitive?.contentOrNull
            return TripLine(row.getValue("id").jsonPrimitive.content,row.getValue("name").jsonPrimitive.content,row.getValue("unit").jsonPrimitive.long,
                StockBooking(StockAction.Purchase,row.getValue("product").jsonPrimitive.long,BigDecimal(text("amount")!!),
                    location=id("location_id"),date=text("best_before_date"),price=text("price")?.toBigDecimal(),note=text("note"),store=id("shopping_location_id"),reviewedStockUnit=row.getValue("unit").jsonPrimitive.long),tare=row["tare"]?.jsonPrimitive?.contentOrNull?.toBigDecimal())
        }
    }
}
data class ShoppingTrip(val id:String="",val lines:List<TripLine> = emptyList(),val submitted:Boolean=false) {
    val confirmed get()=submitted && lines.isNotEmpty() && lines.all { it.state=="confirmed" }
    fun json()=buildJsonObject { put("id",id);put("submitted",submitted);put("lines",JsonArray(lines.map { it.json() })) }.toString()
    companion object {
        fun parse(raw:String):ShoppingTrip { val row=Json.parseToJsonElement(raw).jsonObject
            return ShoppingTrip(row.getValue("id").jsonPrimitive.content,row.getValue("lines").jsonArray.map { TripLine.parse(it.jsonObject) },row["submitted"]?.jsonPrimitive?.booleanOrNull ?: false)
        }
    }
}
interface ShoppingTripRepository {
    suspend fun snapshot():ShoppingTrip
    suspend fun add(booking:StockBooking):ShoppingTrip
    suspend fun addScan(suggestion:ScanSuggestion):ShoppingTrip
    suspend fun edit(id:String,amount:BigDecimal,date:String?,price:BigDecimal?):ShoppingTrip
    suspend fun remove(id:String):ShoppingTrip
    suspend fun submit():ShoppingTrip
    suspend fun newTrip():ShoppingTrip
}
class ManageShoppingTrip(private val repository:ShoppingTripRepository) {
    suspend fun snapshot()=repository.snapshot()
    suspend fun add(booking:StockBooking)=repository.add(booking)
    suspend fun addScan(suggestion:ScanSuggestion)=repository.addScan(suggestion)
    suspend fun edit(id:String,amount:BigDecimal,date:String?,price:BigDecimal?)=repository.edit(id,amount,date,price)
    suspend fun remove(id:String)=repository.remove(id)
    suspend fun submit()=repository.submit()
    suspend fun newTrip()=repository.newTrip()
}
