package app.stillroom.data

import app.stillroom.domain.*
import kotlinx.serialization.json.Json

/** Binds every stock operation to the same account lease as the cache/outbox. */
class GrocyStockRepository(
    private val grants: Set<String>?,
    private val cached: CachedGrocyRepository,
    private val pending: suspend () -> List<PendingChange>,
) : StockRepository {
    override suspend fun read(path: String): StockRead {
        check(StockAccess.canRead(grants)) { "Stock access denied." }
        require(path in setOf("/stock", "/stock/volatile", "/objects/products", "/objects/locations", "/objects/quantity_units", "/objects/product_barcodes", "/objects/stock_log", "/objects/quantity_unit_conversions_resolved", "/objects/userfields", "/objects/product_groups") ||
            path.matches(Regex("/stock/products/[1-9][0-9]*(/(locations|entries|price-history))?")) ||
            path.matches(Regex("/stock/locations/[1-9][0-9]*/entries")) || path == "/openapi/specification")
        val response = cached.read(path)
        return StockRead(Json.parseToJsonElement(response.payload), response.stale)
    }
    override suspend fun book(booking: StockBooking, operationId: String?, guarded: Boolean): String {
        check(StockAccess.canRead(grants) && StockAccess.canWrite(grants, booking.action)) { "Stock access denied." }
        return cached.enqueue("POST", booking.path(), booking.payload().toString(), "/stock/products/${booking.productId}", operationId = operationId, guarded = guarded)
    }
    override suspend fun undo(bookingId: Long): String {
        check(StockAccess.canRead(grants)) { "Stock access denied." }
        require(bookingId > 0)
        return cached.enqueue("POST", "/stock/bookings/$bookingId/undo", "{}", "/objects/stock_log")
    }
    override suspend fun undoTransaction(transactionId: String): String {
        check(StockAccess.canRead(grants)) { "Stock access denied." }
        require(transactionId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        return cached.enqueue("POST", "/stock/transactions/$transactionId/undo", "{}", "/objects/stock_log")
    }
    override suspend fun operations() = pending()
}
