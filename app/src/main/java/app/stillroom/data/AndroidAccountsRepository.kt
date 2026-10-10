package app.stillroom.data

import android.content.Context
import app.stillroom.domain.*
import app.stillroom.domain.AccountCache
import app.stillroom.domain.AccountId
import app.stillroom.domain.AccountState
import app.stillroom.domain.AccountsRepository
import app.stillroom.domain.ServerAddress
import app.stillroom.domain.retainedVerifiedGrants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class AndroidAccountsRepository(
    private val context: Context,
    private val api: GrocyApi = GrocyApi(),
    private val namespace: String = "accounts",
) : AccountsRepository, app.stillroom.domain.PendingChangesRepository {
    val store = EncryptedAccountStore(context, namespace)
    private val lock = Any()
    private val mutableState = MutableStateFlow(AccountState(store.list().map { it.account }))
    override val state = mutableState.asStateFlow()
    override val preferredAccountId: AccountId? get() = store.preferred()
    private val addonKeys by lazy { EncryptedAccountStore(context,"${namespace}_addons") }
    private val pending = mutableSetOf<Job>()
    private var generation = 0L
    private var session: Session? = null

    private class Session(val account: Account, val cache: AccountDatabase, val grocy: CachedGrocyRepository) {
        val compatibility = GrocyCompatibilityRepository(cache,grocy)
        val buddyReceipts = BarcodeBuddyReceipts(cache)
        val trip = GrocyShoppingTripRepository(account.permissions,cache,grocy)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val shopping = GrocyShoppingRepository(cache, grocy, GrocyStockRepository(account.permissions, grocy) { emptyList() }, account.permissions)
        fun close() { scope.cancel(); cache.close() }
    }

    override suspend fun connect(address: ServerAddress, apiKey: String, permissionVerifier: AccountId?): Account =
        connectVerified(address, apiKey, permissionVerifier, null, null)

    private suspend fun connectVerified(address: ServerAddress, apiKey: String, permissionVerifier: AccountId?, expectedId: AccountId?, preserved: Account?): Account = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()[Job]!!
        val startedGeneration = synchronized(lock) { pending.add(job); generation }
        try {
            val verified = api.verify(address, apiKey)
            require(expectedId == null || expectedId == verified.account.id) { "The key no longer belongs to this saved account." }
            var permissions = api.permissions(address, apiKey, verified.account.userId)
            var verifiedBy: AccountId? = null
            if (permissions == null && permissionVerifier != null) {
                val administrator = synchronized(lock) {
                    val saved = store.read(permissionVerifier)
                    require(saved.account.address.apiBase == address.apiBase && saved.account.permissions?.contains("ADMIN") == true) {
                        "Choose an administrator on the same server."
                    }
                    saved
                }
                permissions = api.permissions(address, administrator.apiKey, verified.account.userId)
                require(permissions != null) { "The selected administrator cannot verify permissions." }
                verifiedBy = administrator.account.id
            }
            val retained = retainedVerifiedGrants(permissions, verifiedBy, expectedId, preserved)
            val account = verified.account.copy(permissions = retained.permissions, permissionVerifier = retained.verifier)
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                job.ensureActive()
                if (generation != startedGeneration) throw CancellationException("Account context changed.")
                // No suspended or remote work is allowed after this commit boundary.
                store.save(SavedAccount(account, apiKey))
                val cache = AccountDatabase(context, account.id, namespace)
                try {
                    cache.put("background","access-denied","false")
                    cache.put("system_info", "current", verified.systemInfo)
                    cache.put("current_user", "current", verified.currentUser)
                    cache.put("permissions", "current", org.json.JSONObject().put("granted", account.permissions?.let { org.json.JSONArray(it.sorted()) } ?: org.json.JSONObject.NULL).toString())
                    store.setPreferred(account.id)
                    session?.close()
                    val next = Session(account, cache, CachedGrocyRepository(cache, account.address, apiKey))
                    session = next
                    next.scope.launch { resumeAccountOperations(next.grocy, next.shopping, next.account.permissions) }
                    mutableState.value = AccountState((state.value.accounts.filterNot { it.id == account.id } + account).sortedBy { it.username }, account)
                } catch (error: Exception) { cache.close(); throw error }
            }
            app.stillroom.background.HouseholdWork.accountChanged(context)
            app.stillroom.background.CookingTimerStore(context).accountChanged(account.id)
            account
        } finally { synchronized(lock) { pending.remove(job) } }
    }

    override suspend fun activate(id: AccountId): Account {
        val saved = withContext(Dispatchers.IO) { synchronized(lock) { store.read(id) } }
        val verifier = saved.account.permissionVerifier?.takeIf { source -> state.value.accounts.any { it.id == source && it.permissions?.contains("ADMIN") == true } }
        return connectVerified(saved.account.address, saved.apiKey, verifier, id, saved.account)
    }

    override fun cancelConnections() = synchronized(lock) {
        generation++
        pending.toList().forEach { it.cancel() }
    }

    override suspend fun logout(id: AccountId) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            cancelConnections()
            if (session?.account?.id == id) { session?.close(); session = null }
            // Closed leases reject even a non-cooperative late writer; deleted caches cannot be resurrected.
            AccountDatabase.delete(context, id, namespace)
            app.stillroom.background.CookingTimerStore(context).removeAccount(id)
            addonKeys.delete(id)
            store.delete(id)
            val remaining = state.value.accounts.filterNot { it.id == id }
            mutableState.value = AccountState(remaining, state.value.active?.takeUnless { it.id == id })
            app.stillroom.background.HouseholdWork.accountChanged(context)
        }
    }


    private suspend fun <T> inSession(block: suspend (Session) -> T): T {
        val work = synchronized(lock) {
            val bound = session ?: error("No account is active.")
            bound.scope.async { block(bound) }
        }
        return try { work.await() } catch (error: CancellationException) { work.cancel(); throw error }
    }

    override suspend fun pendingChanges(): List<app.stillroom.domain.PendingChange> = inSession {
        it.cache.operations().map { row -> app.stillroom.domain.PendingChange(row.clientOperationId, row.method, row.path, row.state, row.detail, row.observedPayload != null) }
    }
    override suspend fun reconcileMutation(id: String) = inSession { it.grocy.reconcile(id) }

    suspend fun <T> withStock(block: suspend (app.stillroom.domain.ManageStock) -> T): T = inSession { bound ->
        val repository = GrocyStockRepository(bound.account.permissions, bound.grocy) {
            bound.cache.operations().map { row -> app.stillroom.domain.PendingChange(row.clientOperationId, row.method, row.path, row.state, row.detail, row.observedPayload != null) }
        }
        block(app.stillroom.domain.ManageStock(repository))
    }
    suspend fun drainStock() = inSession { it.grocy.drain() }

    suspend fun <T> withShopping(block: suspend (app.stillroom.domain.ManageShopping) -> T): T = inSession { bound ->
        block(app.stillroom.domain.ManageShopping(bound.shopping))
    }

    suspend fun <T> withHousehold(block: suspend (app.stillroom.domain.ManageHousehold) -> T): T = inSession { bound ->
        val repository = GrocyHouseholdRepository(bound.account.permissions, bound.account.userId, bound.grocy) {
            bound.cache.operations().map { row -> app.stillroom.domain.PendingChange(row.clientOperationId, row.method, row.path, row.state, row.detail, row.observedPayload != null) }
        }
        block(app.stillroom.domain.ManageHousehold(repository))
    }

    suspend fun <T> withShoppingTrip(block:suspend(ManageShoppingTrip)->T):T = inSession { bound -> block(ManageShoppingTrip(bound.trip)) }
    suspend fun <T> withScanner(block: suspend (app.stillroom.domain.ManageScanner) -> T): T = inSession { bound ->
        val saved = synchronized(lock) { store.read(bound.account.id) }
        val transport = MutationTransport()
        val settings=AddonSettings.parse(bound.cache.get("addon-settings","current"))
        val repository = GrocyScanRepository(bound.account.permissions, bound.cache, bound.grocy, { path ->
            transport.request(bound.account.address, saved.apiKey, "GET", path)
        },publicFallbackEnabled=settings.publicLookup)
        block(app.stillroom.domain.ManageScanner(repository))
    }

    suspend fun <T> withRecipes(block: suspend (app.stillroom.domain.ManageRecipes) -> T): T = inSession { bound ->
        val saved = synchronized(lock) { store.read(bound.account.id) }
        val stock = app.stillroom.domain.ManageStock(GrocyStockRepository(bound.account.permissions,bound.grocy) { emptyList() })
        val repository = GrocyRecipeRepository(bound.account.permissions,bound.cache,bound.grocy,stock,
            app.stillroom.domain.ManageShopping(bound.shopping),files=RecipeFiles(bound.account.address,saved.apiKey)::request)
        block(app.stillroom.domain.ManageRecipes(repository))
    }

    suspend fun <T> withCatalog(block: suspend (app.stillroom.domain.ManageCatalog) -> T): T = inSession { bound ->
        block(app.stillroom.domain.ManageCatalog(GrocyCatalogRepository(bound.account.permissions,bound.cache,bound.grocy)))
    }
    suspend fun cachedToday(): app.stillroom.domain.TodaySnapshot = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val active=session
            if(active!=null)return@synchronized CachedTodayRepository(active.account,active.cache).snapshot()
            val preferred=store.preferred() ?: return@synchronized app.stillroom.domain.TodaySnapshot()
            val saved=runCatching { store.read(preferred) }.getOrNull() ?: return@synchronized app.stillroom.domain.TodaySnapshot()
            // Background cache readers must never recover/claim a foreground writer's outbox.
            val cache=AccountDatabase(context,preferred,namespace,recoverOperations=false)
            try { CachedTodayRepository(saved.account,cache).snapshot() } finally { cache.close() }
        }
    }
    /** Publication is fenced against switch/logout after a background reader took its snapshot. */
    fun publishToday(snapshot:app.stillroom.domain.TodaySnapshot,publish:()->Unit):Boolean = synchronized(lock) {
        val account=snapshot.account
        if(account!=null) {
            if(store.preferred()!=account.id)return@synchronized false
            val current=session?.account ?: runCatching { store.read(account.id).account }.getOrNull()
            if(current!=account)return@synchronized false
        }
        publish();true
    }
    suspend fun refreshToday(): app.stillroom.domain.TodaySnapshot = inSession { bound ->
        val today=CachedTodayRepository(bound.account,bound.cache)
        today.refresh(bound.grocy);today.snapshot()
    }

    suspend fun observeGrocy(force:Boolean=false):CompatibilityObservation = inSession { it.compatibility.poll(force) }
    suspend fun addonSettings():AddonSettings = inSession { AddonSettings.parse(it.cache.get("addon-settings","current")) }
    suspend fun saveAddonSettings(settings:AddonSettings,key:String,clearKey:Boolean=false) = inSession { bound ->
        settings.validated()
        synchronized(lock) {
            check(session===bound) { "Account context changed." }
            require(key.none { it.isISOControl() }) { "Enter a valid BarcodeBuddy API key." }
            if(key.isNotBlank() || clearKey)check(HouseholdAccess.has(bound.account.permissions,"ADMIN")) { "Only administrators can change BarcodeBuddy credentials." }
            if(key.isNotBlank())addonKeys.save(SavedAccount(bound.account,key.trim()))
            else if(clearKey)addonKeys.delete(bound.account.id)
            val configured=!clearKey && (key.isNotBlank() || runCatching { addonKeys.read(bound.account.id) }.isSuccess)
            bound.cache.put("addon-settings","current",settings.copy(barcodeBuddyConfigured=configured).json())
        }
    }
    suspend fun barcodeBuddyMode():String = withBarcodeBuddy { it.mode() }
    suspend fun sendBarcodeBuddy(code:ScanCode):String = inSession { bound ->
        val settings=AddonSettings.parse(bound.cache.get("addon-settings","current"))
        check(HouseholdAccess.has(bound.account.permissions,"ADMIN")) { "BarcodeBuddy scans require an administrator account." }
        check(settings.barcodeBuddyConfigured && settings.barcodeBuddyUrl.isNotBlank()) { "Configure BarcodeBuddy first." }
        val saved=synchronized(lock) { addonKeys.read(bound.account.id) }
        val client=BarcodeBuddyClient(ServerAddress.parse(settings.barcodeBuddyUrl,settings.barcodeBuddyInsecure),saved.apiKey)
        bound.buddyReceipts.submit(code,client::scan) { bound.compatibility.poll(true) }
    }
    suspend fun barcodeBuddyReceipts():List<Pair<String,String>> = inSession { it.buddyReceipts.pending() }
    suspend fun reviewBarcodeBuddyReceipt(id:String) = inSession { it.buddyReceipts.reviewed(id) }
    suspend fun grocyFile(group:String,name:String):ByteArray = inSession { bound ->
        check(when(group) { "productpictures"->StockAccess.canRead(bound.account.permissions);"recipepictures"->RecipeAccess.allowed(bound.account.permissions);"userfiles"->StockAccess.canRead(bound.account.permissions) || HouseholdAccess.has(bound.account.permissions,"MASTER_DATA_EDIT");else->false }) { "File access denied." }
        bound.grocy.requireCacheAccess()
        val saved=synchronized(lock) { store.read(bound.account.id) }
        try { GrocyFiles(bound.account.address,saved.apiKey).read(group,name) } catch(e:GrocyFailure) { bound.grocy.recordDenial(e.status);throw e }
    }
    private suspend fun <T> withBarcodeBuddy(block:suspend(BarcodeBuddyClient)->T):T = inSession { bound ->
        check(HouseholdAccess.has(bound.account.permissions,"ADMIN")) { "BarcodeBuddy requires an administrator account." }
        val settings=AddonSettings.parse(bound.cache.get("addon-settings","current"))
        check(settings.barcodeBuddyConfigured && settings.barcodeBuddyUrl.isNotBlank()) { "Configure BarcodeBuddy first." }
        val saved=synchronized(lock) { addonKeys.read(bound.account.id) }
        block(BarcodeBuddyClient(ServerAddress.parse(settings.barcodeBuddyUrl,settings.barcodeBuddyInsecure),saved.apiKey))
    }
    suspend fun <T> withCustomRecords(block:suspend(ManageCustomRecords)->T):T = inSession { bound ->
        val saved=synchronized(lock) { store.read(bound.account.id) }
        block(ManageCustomRecords(GrocyCustomRecordsRepository(bound.account.permissions,bound.cache,bound.grocy,GrocyFiles(bound.account.address,saved.apiKey))))
    }

    override fun currentCache(): AccountCache = synchronized(lock) { session?.cache ?: error("No account is active.") }

    override fun launchAccountWork(block: suspend (AccountCache) -> Unit): Job = synchronized(lock) {
        val bound = session ?: error("No account is active.")
        bound.scope.launch { block(bound.cache) }
    }
}

internal suspend fun resumeAccountOperations(cache: CachedGrocyRepository, shopping: app.stillroom.domain.ShoppingRepository, grants: Set<String>?) {
    try {
        cache.drain()
        if (app.stillroom.domain.ShoppingAccess.allowed(grants)) shopping.sync()
    } catch (error: CancellationException) {
        throw error
    } catch (_: GrocyFailure) {
        // Automatic activation has no foreground error handler. Keep the durable intent;
        // the denial is recorded by the cache and surfaced on the next explicit refresh.
    }
}
