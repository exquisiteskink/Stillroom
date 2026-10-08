package app.stillroom.data

import app.stillroom.domain.Account
import app.stillroom.domain.AccountId
import app.stillroom.domain.ServerAddress
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class GrocyFailure(val status: Int, val permission: String? = null) : Exception(
    when {
        status == 401 -> "The server rejected the API key."
        permission != null -> "Permission denied: $permission."
        status in 300..399 -> "The server redirected the request. Enter its final URL; API keys are not forwarded."
        else -> "The server could not complete the request (HTTP $status)."
    },
)

interface GrocyTransport {
    suspend fun get(address: ServerAddress, key: String, path: String): String
}

/** System TLS trust, no cookies, no redirects, no credential logging. */
class UrlConnectionGrocyTransport : GrocyTransport {
    override suspend fun get(address: ServerAddress, key: String, path: String): String = suspendCancellableCoroutine { continuation ->
        require(address.apiBase.startsWith("https://") || (address.allowInsecure && address.apiBase.startsWith("http://")))
        require(path.startsWith("/") && !path.contains("..") && !path.contains("://"))
        val connection = AtomicReference<HttpURLConnection?>()
        val task = executor.submit {
            var opened: HttpURLConnection? = null
            try {
                if (!continuation.isActive) return@submit
                opened = URL(address.apiBase + path).openConnection() as HttpURLConnection
                connection.set(opened)
                if (!continuation.isActive) return@submit
                opened.instanceFollowRedirects = false
                opened.useCaches = false
                opened.connectTimeout = 15_000
                opened.readTimeout = 15_000
                opened.setRequestProperty("Accept", "application/json")
                opened.setRequestProperty("GROCY-API-KEY", key)
                val status = opened.responseCode
                val stream = if (status in 200..299) opened.inputStream else opened.errorStream
                val body = stream?.bufferedReader()?.use { reader ->
                    val text = StringBuilder()
                    val buffer = CharArray(4096)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        require(text.length + count <= 1_048_576) { "Server response is too large." }
                        text.append(buffer, 0, count)
                    }
                    text.toString()
                }.orEmpty()
                if (status !in 200..299) {
                    val message = runCatching { JSONObject(body).optString("error_message") }.getOrDefault("")
                    val permission = Regex("^Permission missing: ([A-Z_]+)$").matchEntire(message)?.groupValues?.get(1)
                    throw GrocyFailure(status, permission)
                }
                if (continuation.isActive) continuation.resume(body)
            } catch (error: Exception) {
                // Never expose URL, request header, response bodies, or TLS internals to the UI.
                val safe = when (error) {
                    is GrocyFailure -> error
                    is SSLException -> IllegalStateException("TLS verification failed. Check the server certificate.")
                    else -> IllegalStateException("Cannot reach the server or read its response. Check the address and connection.")
                }
                if (continuation.isActive) continuation.resumeWithException(safe)
            } finally { opened?.disconnect() }
        }
        continuation.invokeOnCancellation { connection.get()?.disconnect(); task.cancel(true) }
    }

    companion object {
        private val executor = Executors.newFixedThreadPool(3) { runnable ->
            Thread(runnable, "grocy-request").apply { isDaemon = true }
        }
    }
}

data class VerifiedIdentity(val account: Account, val systemInfo: String, val currentUser: String)

class GrocyApi(private val transport: GrocyTransport = UrlConnectionGrocyTransport()) {
    suspend fun verify(address: ServerAddress, key: String): VerifiedIdentity {
        require(key.isNotBlank() && key.none { it == '\r' || it == '\n' }) { "Enter a valid API key." }
        val info = transport.get(address, key, "/system/info")
        val version = JSONObject(info).getJSONObject("grocy_version").getString("Version")
        require(version.matches(Regex("[0-9A-Za-z.+_-]{1,64}"))) { "Invalid server version response." }
        val userBody = transport.get(address, key, "/user")
        // Both tested versions return a one-element array despite the schema saying object.
        val users = JSONArray(userBody)
        require(users.length() == 1) { "The server did not identify a single current user." }
        val user = users.getJSONObject(0)
        val id = user.getLong("id")
        val username = user.getString("username")
        require(id > 0 && username.isNotBlank()) { "Invalid current-user response." }
        return VerifiedIdentity(Account(AccountId.of(address, id), address, id, username, version, null), info, userBody)
    }

    suspend fun permissions(address: ServerAddress, key: String, userId: Long): Set<String>? {
        val grants = try { JSONArray(transport.get(address, key, "/users/$userId/permissions")) }
            catch (error: GrocyFailure) {
                if (error.status == 403 && error.permission == "ADMIN") return null
                throw error
            }
        val hierarchy = JSONArray(transport.get(address, key, "/objects/permission_hierarchy"))
        val names = (0 until hierarchy.length()).associate { index ->
            val row = hierarchy.getJSONObject(index)
            row.getLong("id") to row.getString("name")
        }
        return (0 until grants.length()).map { index ->
            val grant = grants.getJSONObject(index)
            require(grant.getLong("user_id") == userId) { "Permissions belong to a different user." }
            requireNotNull(names[grant.getLong("permission_id")]) { "Unknown server permission." }
        }.toSet()
    }

    suspend fun get(address: ServerAddress, key: String, path: String) = transport.get(address, key, path)
}
