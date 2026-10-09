package app.stillroom

import app.stillroom.data.MutationTransport
import app.stillroom.data.OpenFoodFactsLookup
import app.stillroom.data.RecipeFiles
import app.stillroom.data.RecipeUrlReader
import app.stillroom.domain.ServerAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Transports are constructed per call (scanner, recipes, each recipe picture). Each one must
 * reuse the process connection pool and dispatcher instead of creating its own.
 */
class HttpClientsTest {
    private val address = ServerAddress.parse("https://grocy.example")

    @Test fun everyTransportSharesOneConnectionPoolAndDispatcher() {
        val clients = listOf(
            MutationTransport().client, MutationTransport(250).client,
            RecipeFiles(address, "synthetic").client, RecipeFiles(address, "synthetic").client,
            RecipeUrlReader().client, OpenFoodFactsLookup().client,
        )
        clients.forEach {
            assertSame(clients.first().connectionPool, it.connectionPool)
            assertSame(clients.first().dispatcher, it.dispatcher)
        }
    }

    @Test fun sharedClientsKeepTheSingleAttemptPolicyAndTheirOwnTimeouts() {
        listOf(MutationTransport().client, RecipeFiles(address, "k").client, RecipeUrlReader().client, OpenFoodFactsLookup().client).forEach {
            assertFalse(it.retryOnConnectionFailure)
            assertFalse(it.followRedirects)
            assertFalse(it.followSslRedirects)
        }
        assertEquals(250, MutationTransport(250).client.callTimeoutMillis)
        assertEquals(15_000, RecipeFiles(address, "k").client.callTimeoutMillis)
    }
}
