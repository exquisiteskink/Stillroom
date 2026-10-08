package app.stillroom.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stillroom.data.AndroidAccountsRepository
import app.stillroom.data.GrocyFailure
import app.stillroom.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

data class StockUiState(
    val resources: Map<String, JsonElement> = emptyMap(), val stale: Boolean = false, val stalePaths: Set<String> = emptySet(),
    val busy: Boolean = false, val denied: Boolean = false, val error: String? = null,
    val selected: Long? = null, val bookingOperation: String? = null, val operations: List<PendingChange> = emptyList(),
)
class StockViewModel(private val accounts: AndroidAccountsRepository) : ViewModel() {
    private val ui = AccountBoundState(StockUiState())
    val state = ui.flow
    private var work: Job? = null
    private var identity: Account? = null
    init {
        viewModelScope.launch {
            accounts.state.collect { accountState ->
                if (accountState.active != identity) {
                    identity = accountState.active; work?.cancel()
                    ui.reset(StockUiState(denied = !StockAccess.canRead(accountState.active?.permissions)))
                    if (!state.value.denied) refresh()
                }
            }
        }
    }
    fun refresh() = execute {
        accounts.drainStock()
        val paths = listOf("/stock", "/stock/volatile", "/objects/products", "/objects/locations", "/objects/quantity_units", "/objects/product_barcodes", "/objects/stock_log", "/objects/quantity_unit_conversions_resolved", "/openapi/specification") +
            state.value.selected?.let { listOf("/stock/products/$it", "/stock/products/$it/locations", "/stock/products/$it/entries", "/stock/products/$it/price-history") }.orEmpty()
        load(paths)
        load(locationPaths())
    }
    fun select(id: Long?) { if (state.value.busy) return; ui.update { it.copy(selected = id,bookingOperation=if(id!=it.selected) null else it.bookingOperation) }; refresh() }
    fun book(booking: StockBooking) = execute {
        val operation = accounts.withStock { it.book(booking) }
        publish { it.copy(bookingOperation = operation) }
        // Expose the durable pending record before transport begins; quantities remain confirmed reads.
        val queued = accounts.withStock { it.operations() }
        publish { it.copy(operations = queued) }
        accounts.drainStock()
        val sent = accounts.withStock { it.operations() }
        publish { it.copy(operations = sent) }
        load(listOf("/stock", "/stock/volatile", "/objects/stock_log") + listOf("", "/locations", "/entries", "/price-history").map { "/stock/products/${booking.productId}$it" } + locationPaths())
    }
    fun clearBookingOutcome() { if (!state.value.busy) ui.update { it.copy(bookingOperation = null) } }
    fun undo(id: Long) = execute {
        accounts.withStock { it.undo(id) }; accounts.drainStock()
        reloadAfterUndo()
    }
    fun undoTransaction(id: String) = execute {
        accounts.withStock { it.undoTransaction(id) }; accounts.drainStock(); reloadAfterUndo()
    }
    private suspend fun AccountBoundState<StockUiState>.Publisher.reloadAfterUndo() {
        load(listOf("/stock", "/stock/volatile", "/objects/stock_log") + state.value.selected?.let { listOf("/stock/products/$it", "/stock/products/$it/locations", "/stock/products/$it/entries", "/stock/products/$it/price-history") }.orEmpty() + locationPaths())
    }
    private fun locationPaths() = state.value.rows("/objects/locations").map { "/stock/locations/${it.text("id")}/entries" }
    private suspend fun AccountBoundState<StockUiState>.Publisher.load(paths: List<String>) {
        val reads = mutableMapOf<String, StockRead>()
        val operations = accounts.withStock { stock ->
            for (path in paths) reads[path] = stock.read(path)
            stock.operations()
        }
        publish { current ->
            val stalePaths = current.stalePaths.toMutableSet()
            reads.forEach { (path, read) -> if (read.stale) stalePaths.add(path) else stalePaths.remove(path) }
            current.copy(resources = current.resources + reads.mapValues { it.value.value }, stale = stalePaths.isNotEmpty(), stalePaths = stalePaths, operations = operations)
        }
    }
    private fun execute(block: suspend AccountBoundState<StockUiState>.Publisher.() -> Unit) {
        if (state.value.busy || state.value.denied) return
        val bound = ui.publisher()
        ui.update { it.copy(busy = true, error = null) }
        work = viewModelScope.launch {
            try { bound.block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val denied = (error is GrocyFailure && error.status in setOf(401, 403)) || error.message == "Stock access denied."
                bound.publish { it.copy(denied = denied, resources = if (denied) emptyMap() else it.resources,
                    error = if (denied) "Stock access denied." else "Stock could not be refreshed. Check the connection or booking fields.") }
            } finally { bound.publish { it.copy(busy = false) } }
        }
    }
}

internal fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.decimal(key: String) = text(key).toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
internal fun StockUiState.rows(path: String) = (resources[path] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
