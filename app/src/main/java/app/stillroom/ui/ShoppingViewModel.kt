package app.stillroom.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stillroom.data.AndroidAccountsRepository
import app.stillroom.data.GrocyFailure
import app.stillroom.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonObject

data class ShoppingUiState(val snapshot: ShoppingSnapshot = ShoppingSnapshot(emptyMap(), false), val changes: List<ShoppingChange> = emptyList(), val busy: Boolean = false, val denied: Boolean = true, val error: String? = null)
class ShoppingViewModel(private val accounts: AndroidAccountsRepository) : ViewModel() {
    private val mutable = MutableStateFlow(ShoppingUiState())
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private var identity: Account? = null
    init { viewModelScope.launch { accounts.state.collect {
        if (identity != it.active) {
            work?.cancel(); identity = it.active
            mutable.value = ShoppingUiState(denied = !ShoppingAccess.allowed(it.active?.permissions))
            if (!state.value.denied) refresh()
        }
    } } }
    fun refresh() = execute { it.sync() }
    fun save(draft: ShoppingDraft, baseline: JsonObject?) = execute { it.save(draft, baseline); pending(it); it.sync() }
    fun delete(row: JsonObject) = execute { it.delete(row); pending(it); it.sync() }
    fun createList(name: String) = execute { it.createList(name); pending(it); it.sync() }
    fun purchase(row: JsonObject, booking: StockBooking) = execute { it.purchase(row, booking); pending(it); it.sync() }
    fun acceptServer(id: String) = execute { it.acceptServer(id) }
    fun merge(id: String, draft: ShoppingDraft, observed: JsonObject) = execute { it.acceptServer(id); it.save(draft, observed); pending(it); it.sync() }
    private suspend fun pending(shopping: ManageShopping) { mutable.value = state.value.copy(changes = shopping.changes()) }
    private fun execute(action: suspend (ManageShopping) -> Unit) {
        if (state.value.busy || state.value.denied) return
        work = viewModelScope.launch {
            mutable.value = state.value.copy(busy = true, error = null)
            try {
                accounts.withShopping { shopping ->
                    action(shopping); pending(shopping)
                    mutable.value = state.value.copy(snapshot = shopping.snapshot(), changes = shopping.changes())
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val denied = (error is GrocyFailure && error.status in setOf(401, 403)) || error.message == "Shopping access denied."
                mutable.value = state.value.copy(denied = denied, snapshot = if (denied) ShoppingSnapshot(emptyMap(), false) else state.value.snapshot,
                    error = if (denied) "Shopping access denied." else "Could not synchronize shopping. Your queued changes are retained; review their status and refresh.")
            } finally { mutable.value = state.value.copy(busy = false) }
        }
    }
}
