package app.stillroom.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.stillroom.domain.AccountCache
import app.stillroom.domain.AccountId
import kotlinx.serialization.json.jsonObject

@Entity(tableName = "cached_rows", primaryKeys = ["resource", "row_id"])
data class CachedRow(val resource: String, @ColumnInfo(name = "row_id") val rowId: String, val payload: String)

@Entity(tableName = "outbox")
data class OutboxOperation(
    @PrimaryKey val clientOperationId: String,
    val method: String,
    val path: String,
    val payload: String,
    val readPath: String,
    val expectedPayload: String?,
    val state: String = "pending",
    val createdAt: Long = System.currentTimeMillis(),
    val observedPayload: String? = null,
    val detail: String? = null,
    val responsePayload: String? = null,
)

@Entity(tableName = "shopping_outbox")
data class ShoppingOperation(
    @PrimaryKey val id: String, val kind: String, val entity: String, val rowId: Long?,
    val baseline: String?, val desired: String, val state: String = "pending",
    val observed: String? = null, val detail: String? = null, val claimOwned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun view() = app.stillroom.domain.ShoppingChange(id, kind, entity, rowId, baseline, desired, state, observed, detail, claimOwned)
}

@Dao
interface AccountDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(row: CachedRow)
    @Query("SELECT payload FROM cached_rows WHERE resource=:resource AND row_id=:rowId") fun get(resource: String, rowId: String): String?
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun enqueue(operation: OutboxOperation): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun shopping(operation: ShoppingOperation)
    @Query("SELECT * FROM shopping_outbox ORDER BY createdAt, id") fun shoppingOperations(): List<ShoppingOperation>
    @Query("SELECT * FROM shopping_outbox WHERE id=:id") fun shoppingOperation(id: String): ShoppingOperation?
    @Query("UPDATE shopping_outbox SET state='needs-review', detail='Interrupted synchronization. Inspect server state; no automatic replay.' WHERE state IN ('preparing','dispatching','finishing')") fun recoverShopping()
    @Query("SELECT * FROM outbox ORDER BY createdAt, clientOperationId") fun operations(): List<OutboxOperation>
    @Query("SELECT * FROM outbox WHERE clientOperationId=:id") fun operation(id: String): OutboxOperation?
    @Query("UPDATE outbox SET state='in-flight' WHERE clientOperationId=:id AND state='pending'") fun claim(id: String): Int
    @Query("UPDATE outbox SET state='in-flight' WHERE clientOperationId=:id AND state='guarded'") fun claimGuarded(id: String): Int
    @Query("UPDATE outbox SET state='confirmed', observedPayload=:payload WHERE clientOperationId=:id AND state='guarded'") fun confirmGuarded(id: String, payload: String)
    @Query("UPDATE outbox SET state=:state, detail=:detail, responsePayload=:responsePayload WHERE clientOperationId=:id AND state IN ('in-flight','needs-review')") fun finish(id: String, state: String, detail: String?, responsePayload: String?)
    @Query("UPDATE outbox SET state='needs-review', detail='Interrupted request; read server state before resolving.' WHERE state='in-flight'") fun recover()
    @Query("UPDATE outbox SET observedPayload=:payload, state=:state WHERE clientOperationId=:id AND state='needs-review'") fun reconcile(id: String, payload: String, state: String)
}

@Database(entities = [CachedRow::class, OutboxOperation::class, ShoppingOperation::class], version = 3, exportSchema = true)
abstract class AccountRoomDatabase : RoomDatabase() { abstract fun rows(): AccountDao }

/** Physical DB per account. Synchronized lease methods reject work after switch/logout. */
class AccountDatabase(context: Context, id: AccountId, namespace: String = "accounts", recoverOperations: Boolean = true) : AccountCache {
    private var closed = false
    private val room = Room.databaseBuilder(context.applicationContext, AccountRoomDatabase::class.java, name(id, namespace))
        .addMigrations(object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS outbox (clientOperationId TEXT NOT NULL PRIMARY KEY, method TEXT NOT NULL, path TEXT NOT NULL, payload TEXT NOT NULL, readPath TEXT NOT NULL, expectedPayload TEXT, state TEXT NOT NULL, createdAt INTEGER NOT NULL, observedPayload TEXT, detail TEXT)")
            }
        }, object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE outbox ADD COLUMN responsePayload TEXT")
                db.execSQL("CREATE TABLE shopping_outbox (id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, entity TEXT NOT NULL, rowId INTEGER, baseline TEXT, desired TEXT NOT NULL, state TEXT NOT NULL, observed TEXT, detail TEXT, claimOwned INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
            }
        }).build()
    init { if (recoverOperations) { room.rows().recover(); room.rows().recoverShopping() } }
    private fun checkOpen() = check(!closed) { "This account cache is closed." }
    @Synchronized override fun put(resource: String, rowId: String, payload: String) { checkOpen(); room.rows().put(CachedRow(resource, rowId, payload)) }
    @Synchronized override fun get(resource: String, rowId: String): String? { checkOpen(); return room.rows().get(resource, rowId) }
    @Synchronized fun enqueue(operation: OutboxOperation) { checkOpen(); val previous = room.rows().operation(operation.clientOperationId)
        require(previous == null || (previous.method == operation.method && previous.path == operation.path && previous.payload == operation.payload && previous.readPath == operation.readPath && previous.expectedPayload == operation.expectedPayload)) { "Operation identity cannot be reused for a different request." }
        room.rows().enqueue(operation)
    }
    @Synchronized fun operations(): List<OutboxOperation> { checkOpen(); return room.rows().operations() }
    @Synchronized fun operation(id: String): OutboxOperation? { checkOpen(); return room.rows().operation(id) }
    @Synchronized fun claim(id: String, guarded: Boolean = false): Boolean { checkOpen(); return (if (guarded) room.rows().claimGuarded(id) else room.rows().claim(id)) == 1 }
    @Synchronized fun confirmGuarded(id: String, payload: String) { checkOpen(); room.rows().confirmGuarded(id, payload) }
    @Synchronized fun finish(id: String, state: String, detail: String? = null, responsePayload: String? = null) { checkOpen(); room.rows().finish(id, state, detail, responsePayload) }
    @Synchronized fun reconcile(id: String, payload: String, confirmed: Boolean) {
        checkOpen(); room.rows().reconcile(id, payload, if (confirmed) "confirmed" else "needs-review")
    }
    @Synchronized fun shoppingOperations(): List<ShoppingOperation> { checkOpen(); return room.rows().shoppingOperations() }
    @Synchronized fun shoppingOperation(id: String): ShoppingOperation? { checkOpen(); return room.rows().shoppingOperation(id) }
    @Synchronized fun saveShopping(operation: ShoppingOperation) { checkOpen(); room.rows().shopping(operation) }
    @Synchronized fun queueShopping(operation: ShoppingOperation): String {
        checkOpen()
        val sameRow = room.rows().shoppingOperations().filter { it.entity == operation.entity && it.rowId != null && it.rowId == operation.rowId && it.baseline?.let { value -> kotlinx.serialization.json.Json.parseToJsonElement(value).jsonObject["row_created_timestamp"] } == operation.baseline?.let { value -> kotlinx.serialization.json.Json.parseToJsonElement(value).jsonObject["row_created_timestamp"] } }
        if (operation.kind == "purchase") sameRow.firstOrNull { it.kind == "purchase" && it.state != "discarded" }?.let {
            require(it.desired == operation.desired) { "This item already has a reviewed purchase. Create a new item for another purchase." }
            return it.id
        }
        val active = sameRow.firstOrNull { it.state !in setOf("confirmed", "discarded", "failed") }
        if (active != null) {
            require(active.state == "pending" && active.kind in setOf("edit", "delete") && operation.kind in setOf("edit", "delete")) { "Resolve the existing change for this item first." }
            room.rows().shopping(active.copy(kind = operation.kind, desired = operation.desired)); return active.id
        }
        room.rows().shopping(operation); return operation.id
    }
    @Synchronized fun close() { if (!closed) { closed = true; room.close() } }
    companion object {
        fun name(id: AccountId, namespace: String = "accounts") = "${namespace}_${id.value}.db"
        fun delete(context: Context, id: AccountId, namespace: String = "accounts") { context.deleteDatabase(name(id, namespace)) }
    }
}
