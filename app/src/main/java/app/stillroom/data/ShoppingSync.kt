package app.stillroom.data

import app.stillroom.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.util.UUID

/** Durable guard/claim/write phases. Unique Grocy claims serialize participating clients. */
internal class ShoppingSync(private val db: AccountDatabase, private val cache: CachedGrocyRepository, private val stock: StockRepository) {
    private fun auxiliary(id: String, phase: String) = UUID.nameUUIDFromBytes("$id/$phase".toByteArray(Charsets.UTF_8)).toString()
    private fun rowPath(op: ShoppingOperation) = "/objects/${op.entity}/${op.rowId}"
    private fun baseline(op: ShoppingOperation) = Json.parseToJsonElement(op.baseline!!).jsonObject
    private fun normalize(row: JsonElement?): JsonElement? {
        val value = row as? JsonObject ?: return row
        return JsonObject(value.mapNotNull { (key, item) ->
            if (key == "userfields" && (item == JsonNull || item == JsonObject(emptyMap()))) null
            else key to if (key in setOf("id", "amount", "done", "qu_id", "shopping_list_id", "product_id") && item != JsonNull) {
                (item as? JsonPrimitive)?.content?.toBigDecimalOrNull()?.let { Json.parseToJsonElement(it.stripTrailingZeros().toPlainString()) } ?: item
            } else item
        }.toMap())
    }
    private fun sameRow(left: JsonElement?, right: JsonElement?) = normalize(left) == normalize(right)
    private fun claimPath(op: ShoppingOperation) = "/objects/userobjects/${ShoppingClaims.id(op.entity, baseline(op))}"
    private fun update(op: ShoppingOperation, state: String, observed: JsonElement? = null, detail: String? = null) {
        db.saveShopping((db.shoppingOperation(op.id) ?: op).copy(state = state, observed = observed?.toString(), detail = detail))
    }
    suspend fun drain() {
        for (saved in db.shoppingOperations().filter { it.state in setOf("pending", "purchased", "needs-review") }) {
            currentCoroutineContext().ensureActive()
            try {
                when (saved.state) {
                    "pending" -> prepare(saved)
                    "purchased" -> finishPurchase(saved)
                    "needs-review" -> reconcile(saved)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val latest = db.shoppingOperation(saved.id)!!
                if (latest.state != "pending") update(latest, "needs-review", detail = "Synchronization interrupted. Inspect server state; this request will not be replayed.")
                if (error is GrocyFailure && error.status in setOf(401, 403)) throw error
                // Fresh GET failed before any claim: pending can safely wait for connectivity.
            }
        }
    }
    private suspend fun prepare(op: ShoppingOperation) {
        val fresh = if (op.rowId != null) cache.readFresh(rowPath(op)) else cache.readFresh("/objects/${op.entity}")
        if (op.rowId != null && !sameRow(fresh, baseline(op))) { conflict(op, fresh, "The server item changed. Review both versions."); return }
        if (op.kind == "purchase" && fresh?.jsonObject?.shoppingText("done") == "1") { conflict(op, fresh, "This item is already completed."); return }
        update(op, "preparing")
        if (op.rowId != null && !acquire(op)) return
        // Check again after acquiring the distributed claim, closing the two-client preflight race.
        if (op.rowId != null) {
            val guarded = cache.readFresh(rowPath(op))
            if (!sameRow(guarded, baseline(op))) { conflict(op, guarded, "The server item changed while synchronization was starting."); release(op); return }
        }
        if (op.kind == "purchase") purchase(op) else write(op)
    }
    private suspend fun ensureEntity(op: ShoppingOperation) {
        val path = "/objects/userentities/${ShoppingClaims.ENTITY_ID}"
        var entity = cache.readFresh(path)
        if (entity == null) {
            cache.enqueue("POST", "/objects/userentities", buildJsonObject {
                put("id", ShoppingClaims.ENTITY_ID); put("name", ShoppingClaims.ENTITY_NAME)
                put("caption", "Stillroom shopping synchronization"); put("show_in_sidebar_menu", 0)
            }.toString(), path, operationId = auxiliary(op.id, "entity"))
            cache.drain(); entity = cache.readFresh(path)
        }
        check(entity?.jsonObject?.shoppingText("name") == ShoppingClaims.ENTITY_NAME) { "Safe shopping synchronization is unavailable." }
    }
    private suspend fun acquire(op: ShoppingOperation, receipt: Boolean = false): Boolean {
        ensureEntity(op)
        val id = auxiliary(op.id, if (receipt) "receipt" else "claim")
        val serverId = if (receipt) ShoppingClaims.purchaseId(baseline(op)) else ShoppingClaims.id(op.entity, baseline(op))
        cache.enqueue("POST", "/objects/userobjects", buildJsonObject {
            put("id", serverId); put("userentity_id", ShoppingClaims.ENTITY_ID)
        }.toString(), "/objects/userobjects/$serverId", operationId = id)
        cache.drain()
        val claim = db.operation(id)!!
        if (claim.state != "confirmed") {
            if (claim.state == "failed") conflict(op, cache.readFresh(rowPath(op)), "Another client or an interrupted purchase owns this item. No write was sent.")
            else update(op, "needs-review", detail = "Claim outcome unknown. No item write or purchase was sent.")
            return false
        }
        val acknowledged = Json.parseToJsonElement(claim.responsePayload!!).jsonObject.shoppingText("created_object_id")
        check(acknowledged == serverId.toString())
        if (!receipt) db.saveShopping(db.shoppingOperation(op.id)!!.copy(claimOwned = true))
        return true
    }
    private suspend fun write(op: ShoppingOperation) {
        update(op, "dispatching")
        val path = if (op.rowId == null) "/objects/${op.entity}" else rowPath(op)
        cache.enqueue(when (op.kind) { "add" -> "POST"; "edit" -> "PUT"; else -> "DELETE" }, path, op.desired, path, operationId = op.id, guarded = true)
        cache.drain(guardedOperation = op.id)
        verifyWrite(op)
    }
    private fun matches(row: JsonElement?, desired: String): Boolean {
        val expected = Json.parseToJsonElement(desired).jsonObject
        val actual = row as? JsonObject ?: return false
        return expected.all { (key, value) ->
            val observed = actual[key]
            if (key in setOf("amount", "done", "qu_id", "shopping_list_id", "product_id", "location_id")) {
                if (value == JsonNull) observed == null || observed == JsonNull else (observed as? JsonPrimitive)?.content?.toBigDecimalOrNull()?.compareTo(value.jsonPrimitive.content.toBigDecimal()) == 0
            } else observed == value || (key == "note" && value.jsonPrimitive.content.isEmpty() && observed == JsonNull)
        }
    }
    private suspend fun verifyWrite(op: ShoppingOperation) {
        val operation = db.operation(op.id) ?: return
        if (operation.state !in setOf("confirmed", "needs-review")) { update(op, if (operation.state == "failed") "failed" else "needs-review", detail = operation.detail); release(op); return }
        val path = if (op.rowId != null) rowPath(op) else {
            val ack = operation.responsePayload ?: run { update(op, "needs-review", detail = "Add outcome unknown. Inspect Grocy; the add will not be replayed."); return }
            val createdId = Json.parseToJsonElement(ack).jsonObject.shoppingText("created_object_id").toLong().also { require(it > 0) }
            "/objects/${op.entity}/$createdId"
        }
        val fresh = cache.readFresh(path)
        val applied = if (op.kind == "delete") fresh == null else matches(fresh, op.desired)
        if (applied) {
            if (operation.state == "needs-review") db.reconcile(op.id, fresh?.toString() ?: "{}", true)
            update(op, "confirmed", fresh); release(op)
        } else { conflict(op, fresh, "The server state differs from the intended change. Nothing will be resent."); release(op) }
    }
    private suspend fun purchase(op: ShoppingOperation) {
        if (!acquire(op, receipt = true)) {
            if (db.shoppingOperation(op.id)!!.state == "conflict") release(op)
            return
        }
        val desired = Json.parseToJsonElement(op.desired).jsonObject
        val marker = "[Stillroom purchase ${op.id}]"
        val booking = StockBooking(StockAction.Purchase, desired.shoppingText("product_id").toLong(), desired.shoppingDecimal("amount")!!,
            location = desired.shoppingText("location_id").toLongOrNull(), date = desired.shoppingText("best_before_date").ifBlank { null },
            price = desired.shoppingDecimal("price"), note = desired.shoppingText("note") + "\n" + marker,
            store = desired.shoppingText("shopping_location_id").toLongOrNull())
        update(op, "dispatching")
        stock.book(booking, op.id, guarded = true); cache.drain(guardedOperation = op.id)
        val dispatched = db.operation(op.id)!!
        if (dispatched.state == "confirmed") { update(op, "purchased"); finishPurchase(db.shoppingOperation(op.id)!!) }
        else update(op, if (dispatched.state == "failed") "failed" else "needs-review", detail = "Purchase ${dispatched.state}. The purchase will not be replayed; inspect stock and journal.")
    }
    private suspend fun finishPurchase(op: ShoppingOperation) {
        val row = cache.readFresh(rowPath(op))
        val expected = JsonObject(baseline(op) + ("done" to JsonPrimitive(1)))
        val completionId = auxiliary(op.id, "complete")
        if (sameRow(row, expected)) {
            when (db.operation(completionId)?.state) {
                "needs-review" -> db.reconcile(completionId, row.toString(), true)
                "guarded" -> db.confirmGuarded(completionId, row.toString())
            }
            update(op, "confirmed", row); release(op); return
        }
        val completion = db.operation(completionId)
        if (!sameRow(row, baseline(op))) { conflict(op, row, "Stock was purchased, but the list item changed. Stock will not be purchased again."); release(op); return }
        if (completion != null && completion.state != "guarded") {
            conflict(op, row, "Stock was purchased; list completion needs review. Do not purchase again."); release(op); return
        }
        update(op, "finishing")
        cache.enqueue("PUT", rowPath(op), "{\"done\":1}", rowPath(op), operationId = completionId, guarded = true)
        cache.drain(guardedOperation = completionId)
        if (db.operation(completionId)!!.state == "confirmed") {
            val observed = cache.readFresh(rowPath(op))
            if (sameRow(observed, expected)) update(op, "confirmed", observed) else conflict(op, observed, "Stock was purchased; the list changed after completion.")
        } else update(op, "needs-review", detail = "Stock was purchased. List completion needs review; stock will not be purchased again.")
        release(op)
        // The distinct purchase receipt remains; the temporary row claim can be released.
    }
    private fun recoverOwnership(op: ShoppingOperation): ShoppingOperation {
        val claim = db.operation(auxiliary(op.id, "claim"))
        if (!op.claimOwned && claim?.state == "confirmed" && db.operation(auxiliary(op.id, "release"))?.state != "confirmed") {
            val ack = claim.responsePayload?.let { Json.parseToJsonElement(it).jsonObject.shoppingText("created_object_id") }
            if (ack == ShoppingClaims.id(op.entity, baseline(op)).toString()) db.saveShopping(op.copy(claimOwned = true))
        }
        return db.shoppingOperation(op.id)!!
    }
    private suspend fun reconcile(op: ShoppingOperation) {
        val recovered = if (op.rowId != null) recoverOwnership(op) else op
        var primary = db.operation(op.id)
        if (primary?.state == "guarded") {
            val fresh = cache.readFresh(if (op.rowId == null) "/objects/${op.entity}" else rowPath(op))
            if (op.rowId != null && !sameRow(fresh, baseline(op))) { conflict(op, fresh, "The server changed before queued dispatch. No item write or purchase was sent."); release(recovered); return }
            if (op.rowId != null && !recovered.claimOwned) { update(op, "needs-review", detail = "Queued dispatch has no proven claim ownership. No write or purchase was sent."); return }
            cache.drain(guardedOperation = op.id); primary = db.operation(op.id)
        }
        if (primary == null && recovered.claimOwned) {
            val fresh = cache.readFresh(rowPath(op))
            if (!sameRow(fresh, baseline(op))) { conflict(op, fresh, "The server changed during the interruption."); release(recovered); return }
            // Absence of a durable dispatch record proves no item write was attempted.
            if (op.kind == "purchase") purchase(recovered) else write(recovered)
            return
        }
        if (op.kind != "purchase") { if (primary != null) verifyWrite(op); return }
        if (primary?.state == "confirmed") { update(op, "purchased"); finishPurchase(db.shoppingOperation(op.id)!!); return }
        if (primary?.state == "needs-review") {
            val desired = Json.parseToJsonElement(op.desired).jsonObject
            val logs = cache.readFresh("/objects/stock_log")!!.jsonArray.filter {
                val row = it.jsonObject
                row.shoppingText("note").contains("[Stillroom purchase ${op.id}]") && row.shoppingText("product_id") == desired.shoppingText("product_id") && row.shoppingText("transaction_type") == "purchase" && row.shoppingText("undone") != "1" && row.shoppingDecimal("amount")?.compareTo(desired.shoppingDecimal("amount")) == 0
            }
            if (logs.size == 1) { db.reconcile(op.id, logs.single().toString(), true); update(op, "purchased"); finishPurchase(db.shoppingOperation(op.id)!!) }
        }
    }
    private fun conflict(op: ShoppingOperation, observed: JsonElement?, message: String) = update(op, "conflict", observed ?: JsonNull, message)
    private suspend fun release(op: ShoppingOperation) {
        val latest = db.shoppingOperation(op.id)!!
        if (!latest.claimOwned || db.operation(op.id)?.state == "needs-review" || db.operation(auxiliary(op.id, "complete"))?.state == "needs-review") return
        val id = auxiliary(op.id, "release")
        cache.enqueue("DELETE", claimPath(op), "{}", claimPath(op), operationId = id); cache.drain()
        if (db.operation(id)!!.state == "confirmed" || cache.readFresh(claimPath(op)) == null) {
            if (db.operation(id)!!.state == "needs-review") db.reconcile(id, "{}", true)
            db.saveShopping(db.shoppingOperation(op.id)!!.copy(claimOwned = false))
        } else update(op, "needs-review", detail = "The item write completed, but its synchronization claim needs review. No mutation will be replayed.")
    }
    suspend fun discard(id: String) {
        val saved = db.shoppingOperation(id) ?: error("Unknown shopping operation.")
        val op = if (saved.rowId != null) recoverOwnership(saved) else saved
        require(op.state in setOf("pending", "conflict", "failed", "needs-review"))
        // Never unlock a purchase that could already have committed or whose claim is uncertain.
        if (op.kind == "purchase" && db.operation(id) != null) {
            release(op)
            update(op, "discarded", detail = "Kept server state. Purchase receipt retained; no repeat purchase.")
        } else {
            release(op); update(op, "discarded", detail = "Kept server state; local intent was retained in history.")
        }
    }
}
