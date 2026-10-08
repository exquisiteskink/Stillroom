package app.stillroom

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import app.stillroom.data.AccountDatabase
import app.stillroom.data.AndroidAccountsRepository
import app.stillroom.data.EncryptedAccountStore
import app.stillroom.data.GrocyApi
import app.stillroom.data.GrocyFailure
import app.stillroom.data.GrocyTransport
import app.stillroom.domain.AccountId
import app.stillroom.domain.ServerAddress
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

object LiveFixtures {
    fun read(context: Context): List<JSONObject> = JSONObject(File(context.filesDir, "stage3-fixtures.json").readText())
        .getJSONArray("fixtures").let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
}

/** Every scenario runs against BOTH pinned containers, with real HTTP and Android storage. */
@RunWith(Parameterized::class)
class AccountIntegrationTest(private val index: Int) {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture get() = LiveFixtures.read(context)[index]
    private val namespace = "stage3_tests_$index"
    private fun repository(api: GrocyApi = GrocyApi()) = AndroidAccountsRepository(context, api, namespace)
    private val address get() = ServerAddress.parse(fixture.getString("base_url"), true)

    private suspend fun clear(repository: AndroidAccountsRepository) {
        repository.state.value.accounts.toList().forEach { repository.logout(it.id) }
    }

    @Test fun realParentAndChildIdentityGrantsAndForbiddenCallMatchStage1() = runBlocking {
        val repo = repository()
        clear(repo)
        try {
            val parent = repo.connect(address, fixture.getString("parent_key"))
            assertEquals(fixture.getLong("parent_user_id"), parent.userId)
            assertEquals(fixture.getString("version"), parent.version)
            assertEquals(setOf("ADMIN"), parent.permissions)
            assertFalse(parent.restricted)
            val child = repo.connect(address, fixture.getString("child_key"), parent.id)
            assertEquals(fixture.getLong("child_user_id"), child.userId)
            assertEquals(setOf("CHORES", "CHORE_TRACK_EXECUTION"), child.permissions)
            assertEquals(parent.id, child.permissionVerifier)
            assertTrue(child.restricted)
            assertFalse(child.permissions!!.contains("CHORE_UNDO_EXECUTION"))
            val denial = try {
                GrocyApi().get(address, fixture.getString("child_key"), "/users")
                error("Child request unexpectedly succeeded.")
            } catch (error: GrocyFailure) { error }
            assertEquals(403, denial.status)
            assertEquals("USERS_READ", denial.permission)
        } finally { clear(repo) }
    }

    @Test fun childWithoutAdministratorNeverInventsPermissions() = runBlocking {
        val repo = repository()
        clear(repo)
        try {
            val child = repo.connect(address, fixture.getString("child_key"))
            assertTrue(child.restricted)
            assertNull(child.permissions)
            assertNull(child.permissionVerifier)
        } finally { clear(repo) }
    }

    @Test fun encryptedKeysSurviveReloadCachesAreIsolatedAndLogoutRevokesLeases() = runBlocking {
        val repo = repository()
        clear(repo)
        try {
            val parent = repo.connect(address, fixture.getString("parent_key"))
            val parentCache = repo.currentCache()
            parentCache.put("private_fixture", "same-row", "synthetic parent-only row")
            val child = repo.connect(address, fixture.getString("child_key"), parent.id)
            val childCache = repo.currentCache()
            assertNull(childCache.get("private_fixture", "same-row"))
            assertEquals(child.userId, JSONObject(childCache.get("current_user", "current")!!.let { org.json.JSONArray(it).getJSONObject(0).toString() }).getLong("id"))
            assertThrows(IllegalStateException::class.java) { parentCache.get("private_fixture", "same-row") }
            childCache.put("private_fixture", "same-row", "synthetic child-only row")
            assertNotEquals(AccountDatabase.name(parent.id, namespace), AccountDatabase.name(child.id, namespace))
            val store = EncryptedAccountStore(context, namespace)
            val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            listOf(parent, child).forEach { account ->
                val blob = store.encryptedFile(account.id).readBytes().toString(Charsets.ISO_8859_1)
                assertFalse("Stored ciphertext must not contain either API key", blob.contains(fixture.getString("parent_key")) || blob.contains(fixture.getString("child_key")))
                assertTrue(keystore.containsAlias(store.alias(account.id)))
            }
            assertTrue("Reload must decrypt the correct key", store.read(parent.id).apiKey == fixture.getString("parent_key"))
            assertTrue("Reload must decrypt the correct key", store.read(child.id).apiKey == fixture.getString("child_key"))
            assertFalse(store.read(child.id).toString().contains(fixture.getString("child_key")))
            // Swapping encrypted records cannot cross identities: AES-GCM binds each record to its account ID.
            val original = store.encryptedFile(child.id).readBytes()
            store.encryptedFile(child.id).writeBytes(store.encryptedFile(parent.id).readBytes())
            assertTrue(runCatching { store.read(child.id) }.isFailure)
            store.encryptedFile(child.id).writeBytes(original)
            repo.activate(parent.id)
            assertEquals("synthetic parent-only row", repo.currentCache().get("private_fixture", "same-row"))
            repo.activate(child.id)
            assertEquals("synthetic child-only row", repo.currentCache().get("private_fixture", "same-row"))
            val latestChildCache = repo.currentCache()
            repo.logout(parent.id)
            assertEquals(child.id, repo.state.value.active?.id)
            assertEquals("synthetic child-only row", latestChildCache.get("private_fixture", "same-row"))
            assertFalse(context.getDatabasePath(AccountDatabase.name(parent.id, namespace)).exists())
            assertFalse(store.encryptedFile(parent.id).exists())
            assertFalse(keystore.containsAlias(store.alias(parent.id)))
            assertTrue(store.encryptedFile(child.id).exists())
            repo.logout(child.id)
            assertThrows(IllegalStateException::class.java) { latestChildCache.put("private_fixture", "same-row", "late row") }
            assertFalse(context.getDatabasePath(AccountDatabase.name(child.id, namespace)).exists())
            assertFalse(store.encryptedFile(child.id).exists())
            assertFalse(keystore.containsAlias(store.alias(child.id)))
            // A new login may reuse the same server/user ID, but logout erased all earlier rows.
            repo.connect(address, fixture.getString("parent_key"))
            assertNull(repo.currentCache().get("private_fixture", "same-row"))
        } finally { clear(repo) }
    }

    @Test fun logoutCancelsAccountWorkAndRejectsNonCooperativeLateWrites() = runBlocking {
        val repo = repository()
        clear(repo)
        try {
            val child = repo.connect(address, fixture.getString("child_key"))
            val started = CompletableDeferred<Unit>()
            val lateWriteRejected = CompletableDeferred<Boolean>()
            val work = repo.launchAccountWork { cache ->
                started.complete(Unit)
                try { delay(Long.MAX_VALUE) }
                finally {
                    // Simulate a worker that ignores cancellation; its expired cache still cannot be used.
                    withContext(NonCancellable) {
                        delay(50)
                        lateWriteRejected.complete(runCatching { cache.put("late", "row", "synthetic") }.isFailure)
                    }
                }
            }
            started.await()
            repo.logout(child.id)
            work.join()
            assertTrue(work.isCancelled)
            assertTrue(lateWriteRejected.await())
            assertFalse(context.getDatabasePath(AccountDatabase.name(child.id, namespace)).exists())
        } finally { clear(repo) }
    }

    @Test fun rejectedKeyNeverCreatesAnAccountOrCache() = runBlocking {
        val repo = repository()
        clear(repo)
        try {
            val error = try { repo.connect(address, "stillroom-invalid-test-key"); error("Invalid key was accepted.") }
                catch (failure: GrocyFailure) { failure }
            assertEquals(401, error.status)
            assertTrue(repo.state.value.accounts.isEmpty())
            assertNull(repo.state.value.active)
            assertTrue(repo.store.list().isEmpty())
        } finally { clear(repo) }
    }

    @Test fun logoutCancelsPendingVerificationBeforeItCanCommit() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val transport = app.stillroom.data.UrlConnectionGrocyTransport()
        var delayed = false
        val api = GrocyApi(object : GrocyTransport {
            override suspend fun get(address: ServerAddress, key: String, path: String): String {
                if (delayed && path == "/system/info") { entered.complete(Unit); delay(Long.MAX_VALUE) }
                return transport.get(address, key, path)
            }
        })
        val repo = repository(api)
        clear(repo)
        try {
            val parent = repo.connect(address, fixture.getString("parent_key"))
            delayed = true
            val pending = async(Dispatchers.IO) { repo.connect(address, fixture.getString("child_key"), parent.id) }
            entered.await()
            repo.logout(parent.id)
            assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
            assertTrue(repo.state.value.accounts.isEmpty())
            assertTrue(repo.store.list().isEmpty())
        } finally { clear(repo) }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "fixture {0}") fun fixtures() = listOf(arrayOf(0), arrayOf(1))
    }
}
