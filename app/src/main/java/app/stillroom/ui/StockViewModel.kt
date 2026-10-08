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
    private val mutable = MutableStateFlow(StockUiState())
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private var identity: Account? = null
    private var generation=0L
    init {
        viewModelScope.launch {
            accounts.state.collect { accountState ->
                if (accountState.active != identity) {
                    generation++;identity = accountState.active; work?.cancel()
                    mutable.value = StockUiState(denied = !StockAccess.canRead(accountState.active?.permissions))
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
    fun select(id: Long?) { if (state.value.busy) return; mutable.value = state.value.copy(selected = id,bookingOperation=if(id!=state.value.selected) null else state.value.bookingOperation); refresh() }
    fun book(booking: StockBooking) = execute {
        val operation = accounts.withStock { it.book(booking) }
        mutable.value = state.value.copy(bookingOperation = operation)
        // Expose the durable pending record before transport begins; quantities remain confirmed reads.
        mutable.value = state.value.copy(operations = accounts.withStock { it.operations() })
        accounts.drainStock()
        mutable.value=state.value.copy(operations=accounts.withStock { it.operations() })
        load(listOf("/stock", "/stock/volatile", "/objects/stock_log") + listOf("", "/locations", "/entries", "/price-history").map { "/stock/products/${booking.productId}$it" } + locationPaths())
    }
    fun clearBookingOutcome() { if (!state.value.busy) mutable.value = state.value.copy(bookingOperation = null) }
    fun undo(id: Long) = execute {
        accounts.withStock { it.undo(id) }; accounts.drainStock()
        reloadAfterUndo()
    }
    fun undoTransaction(id: String) = execute {
        accounts.withStock { it.undoTransaction(id) }; accounts.drainStock(); reloadAfterUndo()
    }
    private suspend fun reloadAfterUndo() {
        load(listOf("/stock", "/stock/volatile", "/objects/stock_log") + state.value.selected?.let { listOf("/stock/products/$it", "/stock/products/$it/locations", "/stock/products/$it/entries", "/stock/products/$it/price-history") }.orEmpty() + locationPaths())
    }
    private fun locationPaths() = state.value.rows("/objects/locations").map { "/stock/locations/${it.text("id")}/entries" }
    private suspend fun load(paths: List<String>) {
        val result = state.value.resources.toMutableMap()
        val stalePaths = state.value.stalePaths.toMutableSet()
        accounts.withStock { stock ->
            for (path in paths) {
                val read = stock.read(path)
                result[path] = read.value
                if (read.stale) stalePaths.add(path) else stalePaths.remove(path)
            }
            mutable.value = state.value.copy(resources = result, stale = stalePaths.isNotEmpty(), stalePaths = stalePaths, operations = stock.operations())
        }
    }
    private fun execute(block: suspend () -> Unit) {
        if (state.value.busy || state.value.denied) return
        val boundGeneration=generation
        mutable.value = state.value.copy(busy = true, error = null)
        work = viewModelScope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val denied = (error is GrocyFailure && error.status in setOf(401, 403)) || error.message == "Stock access denied."
                mutable.value = state.value.copy(denied = denied, resources = if (denied) emptyMap() else state.value.resources,
                    error = if (denied) "Stock access denied." else "Stock could not be refreshed. Check the connection or booking fields.")
            } finally { if(generation==boundGeneration) mutable.value = state.value.copy(busy = false) }
        }
    }
}

internal fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.decimal(key: String) = text(key).toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
internal fun StockUiState.rows(path: String) = (resources[path] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
