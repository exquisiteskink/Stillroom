package app.stillroom.data

import app.stillroom.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Account-bound, read-only observer. Never drains an outbox or invokes a lookup/print action. */
class GrocyCompatibilityRepository(private val db:AccountDatabase,private val cache:CachedGrocyRepository,private val clock:()->Long={System.nanoTime()/1_000_000}) {
    private val lock=Mutex()
    private var lastMetadata:Long?=null
    private var lastSweep:Long?=null
    suspend fun poll(force:Boolean=false):CompatibilityObservation = lock.withLock {
        withContext(Dispatchers.IO) {
            val now=clock()
            var metadataStale=false
            if(force || lastMetadata?.let { now-it>=60_000 }!=false) {
                for(path in listOf("/system/config","/openapi/specification")) {
                    try { metadataStale=cache.read(path).stale || metadataStale }
                    catch(e:CancellationException){throw e}
                    catch(e:GrocyFailure){throw e}
                    catch(_:Exception){metadataStale=true}
                }
                if(!metadataStale)lastMetadata=now
            }
            val capabilities=cache.capabilities()
            val token=if("/system/db-changed-time" in capabilities.paths) {
                try { cache.readFresh("/system/db-changed-time")?.jsonObject?.get("changed_time")?.jsonPrimitive?.contentOrNull }
                catch(e:CancellationException){throw e}
                catch(e:GrocyFailure){if(e.status in setOf(401,403))throw e else null}
                catch(_:Exception){null}
            } else null
            val previous=db.get("compatibility","change-token")
            // Without a usable token, refresh on each foreground interval instead of freezing data.
            val changed=force || token==null || token!=previous || lastSweep?.let { now-it>=120_000 }!=false
            var stale=metadataStale
            if(changed)for(path in db.cachedResources().filter { refreshableGrocyPath(it) && capabilities.allows(it) }) {
                try { stale=cache.read(path).stale || stale }
                catch(e:CancellationException){throw e}
                catch(e:GrocyFailure){if(e.status in setOf(401,403))throw e else stale=true}
                catch(_:Exception){stale=true}
            }
            if(changed && !stale)lastSweep=now
            if(changed && !stale && token!=null)db.put("compatibility","change-token",token)
            CompatibilityObservation(capabilities,changed,stale,token)
        }
    }
}
data class CompatibilityObservation(val capabilities:ServerCapabilities=ServerCapabilities(),val changed:Boolean=false,val stale:Boolean=false,val changedTime:String?=null)
