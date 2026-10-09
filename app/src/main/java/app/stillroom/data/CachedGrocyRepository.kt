package app.stillroom.data

import app.stillroom.domain.ServerAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** No redirects, connection retries, or authenticators: one mutation attempt per claim. */
class MutationTransport(timeoutMillis: Long = 15_000) {
    internal val client = HttpClients.base.newBuilder()
        .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS).build()
    suspend fun request(address: ServerAddress, key: String, method: String, path: String, payload: String = ""): Pair<Int, String> {
        validatePath(path)
        require(key.isNotBlank() && key.none { it == '\r' || it == '\n' })
        require(address.apiBase.startsWith("https://") || address.allowInsecure)
        val request = Request.Builder().url(address.apiBase + path).header("GROCY-API-KEY", key)
            .header("Accept", "application/json").method(method, if (method == "GET") null else payload.toByteArray(Charsets.UTF_8).toRequestBody("application/json".toMediaType())).build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: java.io.IOException) {
                    if (continuation.isActive) continuation.resumeWithException(TlsFailures.wrap(error, "HTTP outcome unavailable."))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            val source = it.body?.source()
                            if (source != null && source.request(1_048_577) && source.buffer.size > 1_048_576) error("Response too large.")
                            it.code to (source?.readUtf8() ?: "")
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (_: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("HTTP outcome unavailable."))
                    }
                }
            })
        }
    }
}

internal fun validatePath(path: String) {
    require(path.startsWith("/") && !path.startsWith("//") && !path.contains("..") && !path.contains("%") && !path.contains("\\") && !path.contains("://") && !path.contains("#"))
}
internal fun jsonValue(payload: String): JsonElement {
    val value = Json.parseToJsonElement(payload)
    require(value is JsonObject || value is JsonArray)
    return value
}
private fun canonical(value: JsonElement): String = when (value) {
    is JsonObject -> value.keys.sorted().joinToString(prefix = "{", postfix = "}") { JsonPrimitive(it).toString() + ":" + canonical(value.getValue(it)) }
    is JsonArray -> value.joinToString(prefix = "[", postfix = "]") { canonical(it) }
    else -> value.toString()
}

data class CachedRead(val payload: String, val stale: Boolean)

/** Cache holds last valid reads. Only pending operations may ever send a mutation. */
class CachedGrocyRepository(
    private val database: AccountDatabase,
    private val address: ServerAddress,
    private val key: String,
    private val transport: MutationTransport = MutationTransport(),
    /** Test hook that runs after a row is claimed and before [MutationTransport.request]. */
    private val beforeRequest: suspend () -> Unit = {},
) {
    private val drainLock = Mutex()

    suspend fun read(path: String): CachedRead = withContext(Dispatchers.IO) {
        validatePath(path)
        try {
            val (status, payload) = transport.request(address, key, "GET", path)
            if (status in setOf(401,403)) database.put("background","access-denied","true")
            if (status !in 200..299) throw GrocyFailure(status)
            jsonValue(payload)
            currentCoroutineContext().ensureActive()
            database.put(path, "current", payload)
            CachedRead(payload, false)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            if (error is GrocyFailure && error.status in setOf(401, 403)) throw error
            requireCacheAccess()
            database.get(path, "current")?.let { CachedRead(it, true) } ?: throw IllegalStateException("No cached server response is available.")
        }
    }

    internal fun requireCacheAccess() {
        if (database.get("background", "access-denied") == "true") throw GrocyFailure(403)
    }

    internal fun recordDenial(status: Int) {
        if (status in setOf(401, 403)) database.put("background", "access-denied", "true")
    }

    /** A guard must use a fresh observation, never an offline cache fallback. */
    suspend fun readFresh(path: String): JsonElement? = withContext(Dispatchers.IO) {
        validatePath(path)
        val (status, payload) = transport.request(address, key, "GET", path)
        if (status in setOf(401,403)) database.put("background","access-denied","true")
        if (status == 404) return@withContext null
        if (status !in 200..299) throw GrocyFailure(status)
        val value = jsonValue(payload)
        currentCoroutineContext().ensureActive()
        database.put(path, "current", payload)
        value
    }

    suspend fun enqueue(method: String, path: String, payload: String, readPath: String, expectedPayload: String? = null, operationId: String? = null, guarded: Boolean = false): String = withContext(Dispatchers.IO) {
        require(method in setOf("POST", "PUT", "DELETE", "PATCH"))
        validatePath(path); validatePath(readPath); jsonValue(payload)
        expectedPayload?.let { jsonValue(it) }
        val id = operationId?.also { require(UUID.fromString(it).toString() == it) } ?: UUID.randomUUID().toString()
        database.enqueue(OutboxOperation(id, method, path, payload, readPath, expectedPayload, state = if (guarded) "guarded" else "pending"))
        id
    }

    suspend fun drain(guardedOperation: String? = null) = withContext(Dispatchers.IO) {
        drainLock.withLock {
        for (operation in database.operations().filter { it.state == "pending" || (it.state == "guarded" && it.clientOperationId == guardedOperation) }) {
            currentCoroutineContext().ensureActive()
            if (!database.claim(operation.clientOperationId, guarded = operation.state == "guarded")) continue
            var requested = false
            try {
                currentCoroutineContext().ensureActive()
                beforeRequest()
                currentCoroutineContext().ensureActive()
                requested = true
                val (status, response) = transport.request(address, key, operation.method, operation.path, operation.payload)
                if (status in setOf(401,403)) database.put("background","access-denied","true")
                val state = when {
                    status in 200..299 -> {
                        if (response.isNotBlank()) jsonValue(response)
                        "confirmed"
                    }
                    status in setOf(400, 401, 403, 404, 405, 422) -> "failed"
                    else -> "needs-review" // Even a 500 can follow a committed mutation.
                }
                database.finish(operation.clientOperationId, state, if (state == "confirmed") null else failureDetail(status, response), if (state == "confirmed") response else null)
            } catch (error: CancellationException) {
                // Nothing was sent yet, so the row can be tried again. After request() starts, Grocy may have applied it.
                if (!requested) runCatching { database.releaseClaim(operation.clientOperationId) }
                else runCatching { database.finish(operation.clientOperationId, "needs-review", "Interrupted HTTP outcome.") }
                throw error
            } catch (error: Exception) {
                // If the lease closed, its durable in-flight row is recovered on next activation.
                // A certificate failure is still not replayed: the request may not have been sent, but it is not retried as HTTP.
                val detail = if (TlsFailures.isTls(error)) TlsFailures.message(error) else "Unknown HTTP outcome; mutation will not be replayed."
                runCatching { database.finish(operation.clientOperationId, "needs-review", detail) }
            }
        }
        }
    }

    /** "HTTP 400: <Grocy error_message>" so Pending changes and editors can show Grocy's reason. */
    internal fun failureDetail(status: Int, response: String): String {
        val message = runCatching { (Json.parseToJsonElement(response) as? JsonObject)?.get("error_message")?.jsonPrimitive?.contentOrNull }.getOrNull()
        return if (message.isNullOrBlank()) "HTTP $status" else "HTTP $status: ${message.take(500)}"
    }

    internal suspend fun outboxRecords(): List<OutboxOperation> = withContext(Dispatchers.IO) { database.operations() }

    suspend fun reconcile(id: String) = withContext(Dispatchers.IO) {
        val operation = database.operation(id) ?: error("Unknown operation.")
        require(operation.state == "needs-review")
        val observed = read(operation.readPath)
        if (!observed.stale) {
            val matches = operation.expectedPayload?.let { canonical(jsonValue(it)) == canonical(jsonValue(observed.payload)) } ?: false
            database.reconcile(id, observed.payload, matches)
        }
    }
}
