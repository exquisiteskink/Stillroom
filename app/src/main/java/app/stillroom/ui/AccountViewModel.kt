package app.stillroom.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.stillroom.data.GrocyFailure
import app.stillroom.data.TlsFailures
import app.stillroom.domain.AccountId
import app.stillroom.domain.AccountState
import app.stillroom.domain.ManageAccounts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AccountUiState(val accounts: AccountState = AccountState(), val busy: Boolean = false, val error: String? = null)

class AccountViewModel(private val manage: ManageAccounts) : ViewModel() {
    private val mutableState = MutableStateFlow(AccountUiState(manage.state.value))
    val state = mutableState.asStateFlow()
    private var work: Job? = null
    private var operation = 0L

    init {
        viewModelScope.launch { manage.state.collect { mutableState.value = state.value.copy(accounts = it) } }
        manage.preferredAccountId?.let { id -> runOperation { manage.activate(id) } }
    }

    fun connect(baseUrl: String, apiKey: String, insecure: Boolean, verifier: AccountId?) {
        runOperation { manage.connect(baseUrl, apiKey, insecure, verifier) }
    }
    fun activate(id: AccountId) { runOperation { manage.activate(id) } }
    fun logout(id: AccountId) { runOperation { manage.logout(id) } }
    fun cancel() {
        operation++
        work?.cancel()
        manage.cancelConnections()
        mutableState.value = state.value.copy(busy = false, error = null)
    }

    private fun runOperation(action: suspend () -> Unit) {
        cancel()
        val token = operation
        mutableState.value = state.value.copy(busy = true, error = null)
        work = viewModelScope.launch {
            try { action() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val message = when (error) {
                    is GrocyFailure -> error.message!!
                    is IllegalArgumentException, is IllegalStateException -> error.message?.takeIf { it in safeMessages }
                        ?: "Could not verify or securely save this account."
                    else -> "The server returned an invalid identity or permission response."
                }
                if (operation == token) mutableState.value = state.value.copy(error = message)
            } finally {
                if (operation == token) mutableState.value = state.value.copy(busy = false)
            }
        }
    }

    override fun onCleared() { cancel(); super.onCleared() }

    companion object {
        private val safeMessages = setOf(
            "Enter a server URL.", "Enter a valid server URL.", "Enter a valid server port.", "Enter a valid API key.",
            "HTTPS is required. Enable insecure HTTP only for your local test server.",
            "Use a server URL without credentials, query parameters, or a fragment.",
            "Choose an administrator on the same server.", "The selected administrator cannot verify permissions.",
            "The key no longer belongs to this saved account.",
            TlsFailures.GENERIC,
            TlsFailures.EXPIRED,
            TlsFailures.NOT_YET_VALID,
            TlsFailures.HOSTNAME,
            TlsFailures.UNTRUSTED,
            "Cannot reach the server or read its response. Check the address and connection.",
        )
        fun factory(manage: ManageAccounts): ViewModelProvider.Factory = viewModelFactory { initializer { AccountViewModel(manage) } }
    }
}
