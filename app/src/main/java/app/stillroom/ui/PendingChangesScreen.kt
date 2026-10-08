package app.stillroom.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.stillroom.data.AndroidAccountsRepository
import app.stillroom.domain.PendingChange
import app.stillroom.domain.ManagePendingChanges
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PendingChangesViewModel(private val changes: ManagePendingChanges) : ViewModel() {
    private val rows = MutableStateFlow<List<PendingChange>>(emptyList())
    val state = rows.asStateFlow()
    private val status = MutableStateFlow<String?>(null)
    val message = status.asStateFlow()
    private val loading = MutableStateFlow(false)
    val busy = loading.asStateFlow()
    private val owner = MutableStateFlow<String?>(null)
    val accountId = owner.asStateFlow()
    private var generation = 0L
    init {
        viewModelScope.launch { changes.accounts.collect { refresh() } }
    }
    fun refresh() {
        val generationAtStart = ++generation
        rows.value = emptyList(); status.value = null; loading.value = false
        val boundId = changes.accounts.value.active?.id?.value
        owner.value = boundId
        if (changes.accounts.value.active == null) return
        loading.value = true
        viewModelScope.launch {
            try {
                val result = changes.list()
                if (generation == generationAtStart && changes.accounts.value.active?.id?.value == boundId) rows.value = result
            } catch (_: CancellationException) { /* account switch discards obsolete work */ }
            catch (_: Exception) { if (generation == generationAtStart) status.value = "Could not load changes. Refresh to try again." }
            finally { if (generation == generationAtStart) loading.value = false }
        }
    }
    fun reconcile(id: String) {
        val generationAtStart = generation
        viewModelScope.launch {
            try { changes.reconcile(id); if (generation == generationAtStart) refresh() }
            catch (_: CancellationException) { }
            catch (_: Exception) { if (generation == generationAtStart) status.value = "Server state unavailable. Change still needs review." }
        }
    }
}

@Composable
fun PendingChangesScreen(accounts: AndroidAccountsRepository) {
    val model: PendingChangesViewModel = viewModel(factory = viewModelFactory { initializer { PendingChangesViewModel(ManagePendingChanges(accounts)) } })
    val active by accounts.state.collectAsState()
    val rows by model.state.collectAsState()
    val message by model.message.collectAsState()
    val owner by model.accountId.collectAsState()
    val busy by model.busy.collectAsState()
    val visibleRows = rows.takeIf { owner == active.active?.id?.value }.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(active.active?.let { "Account: ${it.username}" } ?: "No account is active.")
        Text("Check changes that are waiting for Grocy. If a result is uncertain, check Grocy before trying again.")
        QuietButton(onClick = model::refresh, enabled = !busy) { Text("Refresh changes") }
        message?.let { KitchenError(it) }
        if (busy) Text("Loading changes…")
        if (visibleRows.isEmpty() && !busy && message == null) Text("No changes to review.")
        visibleRows.forEach { row ->
            KitchenCard {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(changeTitle(row.path), style = MaterialTheme.typography.titleMedium)
                    Text(changeStatus(row.state), style = MaterialTheme.typography.bodyMedium)
                    row.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    if (row.serverStateRead && row.state != "confirmed") Text("Grocy was checked. This change still needs review.")
                    if (row.state == "needs-review") QuietButton(onClick = { model.reconcile(row.clientOperationId) }) { Text("Check Grocy") }
                    var details by remember(row.clientOperationId) { mutableStateOf(false) }
                    QuietButton(onClick = { details = !details }) { Text(if (details) "Hide details" else "Show details") }
                    if (details) {
                        Text("${row.method} ${row.path}", style = MaterialTheme.typography.bodySmall)
                        Text("Reference: ${row.clientOperationId}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private fun changeTitle(path: String): String = when {
    path.endsWith("/add") -> "Add stock"
    path.endsWith("/consume") -> "Use stock"
    path.endsWith("/charge") -> "Record battery charge"
    path.endsWith("/execute") -> "Complete chore"
    path.endsWith("/complete") -> "Complete task"
    path.contains("/shopping") -> "Update shopping list"
    path.contains("/recipes") -> "Update recipe"
    path.contains("/products") -> "Update product"
    path.contains("/undo") -> "Undo change"
    else -> "Update household record"
}
private fun changeStatus(state: String): String = when (state) {
    "confirmed" -> "Saved in Grocy"
    "pending", "guarded" -> "Waiting to send"
    "in-flight" -> "Waiting for Grocy"
    "needs-review" -> "Result uncertain · review before trying again"
    "failed" -> "Could not save"
    else -> "Needs review"
}
