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
    /** Settings → Stock → Shown details for this account; null means defaults (unchanged rows). */
    val detailSettings: List<StockDetailSetting>? = null,
)

/** Product userfield definitions, or null when they could not be read (older server, no access, not loaded yet). */
internal fun StockUiState.userfieldDefinitions(): List<UserfieldDefinition>? =
    (resources["/objects/userfields"] as? JsonArray)?.let { array -> UserfieldDefinition.parse(array.mapNotNull { it as? JsonObject }) }

internal fun StockUiState.stockDetails() = StockDetails(detailSettings, userfieldDefinitions())

class StockViewModel(
    private val accounts: AndroidAccountsRepository,
    private val detailStore: StockDetailsStore = InMemoryStockDetailsStore(),
) : ViewModel() {
    private val ui = AccountBoundState(StockUiState())
    val state = ui.flow
    private var work: Job? = null
    private var identity: Account? = null
    init {
        viewModelScope.launch {
            accounts.state.collect { accountState ->
                if (accountState.active != identity) {
                    identity = accountState.active; work?.cancel()
                    ui.reset(StockUiState(denied = !StockAccess.canRead(accountState.active?.permissions),
                        detailSettings = accountState.active?.let { detailStore.load(it.id.value) }))
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
        loadOptional(OPTIONAL_PATHS)
    }

    /** Shown details: changes apply to the active account only and are saved on this phone. */
    fun setDetailVisible(key: String, visible: Boolean) = saveDetails { it.toggle(key, visible) }
    fun moveDetail(key: String, up: Boolean) = saveDetails { it.move(key, up) }
    fun resetDetails() {
        val account = identity ?: return
        detailStore.clear(account.id.value)
        ui.update { it.copy(detailSettings = null) }
    }
    private fun saveDetails(change: (StockDetails) -> List<StockDetailSetting>) {
        val account = identity ?: return
        val next = change(state.value.stockDetails())
        detailStore.save(account.id.value, next)
        ui.update { it.copy(detailSettings = next) }
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
    /**
     * Reads that only feed Shown details. Each is tried on its own and a failure (older Grocy,
     * missing permission, offline with no cache) leaves the pantry working without it.
     */
    private suspend fun AccountBoundState<StockUiState>.Publisher.loadOptional(paths: List<String>) {
        for (path in paths) {
            try { load(listOf(path)) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { }
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

/** Product userfield definitions and product group names for Shown details; both optional. */
internal val OPTIONAL_PATHS = listOf("/objects/userfields", "/objects/product_groups")

internal fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.decimal(key: String) = text(key).toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
internal fun StockUiState.rows(path: String) = (resources[path] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
