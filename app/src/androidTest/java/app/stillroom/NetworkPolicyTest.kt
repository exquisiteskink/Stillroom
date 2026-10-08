package app.stillroom

import app.stillroom.data.GrocyFailure
import app.stillroom.data.UrlConnectionGrocyTransport
import app.stillroom.domain.ServerAddress
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class NetworkPolicyTest {
    @Test fun redirectDoesNotForwardAnApiKeyToAnotherOrigin(): Unit = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { source ->
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { destination ->
                destination.soTimeout = 300
                val serving = async(Dispatchers.IO) {
                    source.accept().use { socket ->
                        socket.soTimeout = 3000
                        val input = socket.getInputStream().bufferedReader()
                        while (input.readLine()?.isNotEmpty() == true) { }
                        socket.getOutputStream().write("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${destination.localPort}/api/system/info\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    }
                }
                val failure = try {
                    UrlConnectionGrocyTransport().get(ServerAddress.parse("http://127.0.0.1:${source.localPort}", true), "synthetic-test-credential", "/system/info")
                    error("Redirect was followed.")
                } catch (error: GrocyFailure) { error }
                assertEquals(302, failure.status)
                serving.await()
                assertThrows(SocketTimeoutException::class.java) { destination.accept().close() }
            }
        }
    }

    @Test fun cancellingARequestClosesItsSocket() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val entered = CompletableDeferred<Unit>()
            val serving = async(Dispatchers.IO) {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val reader = socket.getInputStream().bufferedReader()
                    while (reader.readLine()?.isNotEmpty() == true) { }
                    entered.complete(Unit)
                    reader.read()
                }
            }
            val request = async(Dispatchers.IO) {
                UrlConnectionGrocyTransport().get(ServerAddress.parse("http://127.0.0.1:${server.localPort}", true), "synthetic-test-credential", "/system/info")
            }
            withTimeout(5000) {
                entered.await()
                request.cancelAndJoin()
                assertEquals(-1, serving.await())
            }
            assertTrue(request.isCancelled)
        }
    }
}
