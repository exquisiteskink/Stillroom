package app.stillroom.domain

import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow

/** A canonical API origin/path. HTTP is never inferred or used as a TLS fallback. */
@ConsistentCopyVisibility
data class ServerAddress private constructor(val apiBase: String, val allowInsecure: Boolean) {
    companion object {
        fun parse(input: String, allowInsecure: Boolean = false): ServerAddress {
            val text = input.trim()
            require(text.isNotEmpty()) { "Enter a server URL." }
            val uri = try { URI(if ("://" in text) text else "https://$text").normalize() }
                catch (_: Exception) { throw IllegalArgumentException("Enter a valid server URL.") }
            val scheme = uri.scheme?.lowercase()
            require(scheme == "https" || (scheme == "http" && allowInsecure)) {
                "HTTPS is required. Enable insecure HTTP only for your local test server."
            }
            require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "Use a server URL without credentials, query parameters, or a fragment."
            }
            require(uri.port == -1 || uri.port in 1..65535) { "Enter a valid server port." }
            val port = if ((scheme == "https" && uri.port == 443) || (scheme == "http" && uri.port == 80)) -1 else uri.port
            val origin = URI(scheme, null, uri.host.lowercase(), port, null, null, null).toASCIIString()
            val path = uri.rawPath.orEmpty().trimEnd('/').let { if (it.endsWith("/api")) it else "$it/api" }
            return ServerAddress(origin + path, allowInsecure)
        }
    }
}

/** Grocy's API-key QR is `server/api|key`. A calendar link is rejected. The key is not logged. */
data class ScannedApiKey(val serverUrl: String?, val apiKey: String, val insecureHttp: Boolean)

fun parseGrocyApiKeyQr(raw: String): ScannedApiKey {
    val text = raw.trim()
    require(text.length in 1..512 && text.none { it.isISOControl() }) { "This QR does not contain a Grocy API key." }
    require(!text.contains("secret=", ignoreCase = true) && !text.contains("/calendar/", ignoreCase = true)) {
        "This QR is a calendar link, not an API key."
    }
    val pipe = text.indexOf('|')
    require(pipe > 0 && pipe == text.lastIndexOf('|')) { "This QR does not contain a Grocy API key." }
    val urlPart = text.substring(0, pipe).trim()
    val key = text.substring(pipe + 1).trim()
    require(key.isNotEmpty() && key.none { it.isWhitespace() }) { "This QR does not contain a Grocy API key." }
    val insecure = urlPart.startsWith("http://")
    if (!insecure && !urlPart.startsWith("https://")) return ScannedApiKey(null, key, false)
    return ScannedApiKey(ServerAddress.parse(urlPart, insecure).apiBase, key, insecure)
}

data class AccountId(val value: String) {
    init { require(value.matches(Regex("[a-f0-9]{64}"))) }
    companion object {
        fun of(address: ServerAddress, userId: Long): AccountId {
            require(userId > 0)
            val bytes = MessageDigest.getInstance("SHA-256").digest("${address.apiBase}\n$userId".toByteArray())
            return AccountId(bytes.joinToString("") { "%02x".format(it) })
        }
    }
}

object GrocyCompatibility {
    val testedVersions = setOf("4.7.1", "4.6.0")
    fun warning(version: String): String? = when (version) {
        "4.7.1" -> null
        "4.6.0" -> "Compatibility note: Grocy 4.6.0 truncates fractional shopping amounts in its add-product endpoint."
        else -> "Version mismatch: Grocy $version is not in the compatibility matrix. Tested versions: ${testedVersions.joinToString(" and ")}."
    }
}

data class Account(
    val id: AccountId,
    val address: ServerAddress,
    val userId: Long,
    val username: String,
    val version: String,
    // Null means unavailable, never an inferred grant. These are explicit server grants.
    val permissions: Set<String>?,
    val permissionVerifier: AccountId? = null,
) {
    val restricted: Boolean get() = !(StockAccess.canRead(permissions) || ShoppingAccess.allowed(permissions) || RecipeAccess.allowed(permissions) || HouseholdAccess.has(permissions,"MASTER_DATA_EDIT") || HouseholdAccess.has(permissions,"BATTERIES") || HouseholdAccess.has(permissions,"EQUIPMENT"))
    val versionWarning: String? get() = GrocyCompatibility.warning(version)
}

internal data class RetainedGrants(val permissions: Set<String>?, val verifier: AccountId?)

/** A fresh connection stores only a grant list this login actually read. Reactivation keeps the last verified set when that read is unavailable. */
internal fun retainedVerifiedGrants(fresh: Set<String>?, verifiedBy: AccountId?, reactivatedId: AccountId?, preserved: Account?): RetainedGrants {
    if (fresh != null || verifiedBy != null) return RetainedGrants(fresh, verifiedBy)
    if (reactivatedId != null && preserved != null && reactivatedId == preserved.id && preserved.permissions != null) {
        return RetainedGrants(preserved.permissions, preserved.permissionVerifier)
    }
    return RetainedGrants(null, null)
}

data class AccountState(val accounts: List<Account> = emptyList(), val active: Account? = null)

/** A bound cache lease: no account selector is accepted by row operations. */
interface AccountCache {
    fun put(resource: String, rowId: String, payload: String)
    fun get(resource: String, rowId: String): String?
}

interface AccountsRepository {
    val state: StateFlow<AccountState>
    val preferredAccountId: AccountId?
    suspend fun connect(address: ServerAddress, apiKey: String, permissionVerifier: AccountId? = null): Account
    suspend fun activate(id: AccountId): Account
    suspend fun logout(id: AccountId)
    fun currentCache(): AccountCache
    fun launchAccountWork(block: suspend (AccountCache) -> Unit): Job
    fun cancelConnections()
}

class ManageAccounts(private val repository: AccountsRepository) {
    val state = repository.state
    val preferredAccountId get() = repository.preferredAccountId
    suspend fun connect(baseUrl: String, key: String, insecure: Boolean, verifier: AccountId? = null) =
        repository.connect(ServerAddress.parse(baseUrl, insecure), key, verifier)
    suspend fun activate(id: AccountId) = repository.activate(id)
    suspend fun logout(id: AccountId) = repository.logout(id)
    fun cancelConnections() = repository.cancelConnections()
}
