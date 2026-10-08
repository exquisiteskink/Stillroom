package app.stillroom.domain

import kotlinx.coroutines.flow.StateFlow

data class PendingChange(
    val clientOperationId: String,
    val method: String,
    val path: String,
    val state: String,
    val detail: String?,
    val serverStateRead: Boolean,
)

interface PendingChangesRepository {
    val state: StateFlow<AccountState>
    suspend fun pendingChanges(): List<PendingChange>
    suspend fun reconcileMutation(id: String)
}

class ManagePendingChanges(private val repository: PendingChangesRepository) {
    val accounts = repository.state
    suspend fun list() = repository.pendingChanges()
    suspend fun reconcile(id: String) = repository.reconcileMutation(id)
}
