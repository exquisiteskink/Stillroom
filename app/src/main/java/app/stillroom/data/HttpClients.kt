package app.stillroom.data

import okhttp3.OkHttpClient

/**
 * One OkHttp connection pool and dispatcher for the whole process.
 *
 * `OkHttpClient.Builder().build()` creates a new connection pool and dispatcher thread pool
 * every time. The transports below used to be constructed per call (`withScanner`,
 * `withRecipes`, and once per recipe picture in `loadPictures`), so each call left its own
 * pool holding a keep-alive socket for up to five minutes. Deriving with [OkHttpClient.newBuilder]
 * keeps per-use settings (timeouts, DNS policy) while sharing the pool and threads.
 *
 * Sharing a pool is safe across accounts and servers: OkHttp only reuses a connection for an
 * identical address (scheme, host, port, DNS, TLS settings, ...), there is no cookie jar, and
 * the Grocy API key is a per-request header, never connection state.
 */
internal object HttpClients {
    /** No redirects and no silent connection retries: every request is attempted once. */
    val base: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
}
