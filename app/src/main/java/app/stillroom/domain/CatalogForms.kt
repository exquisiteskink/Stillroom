package app.stillroom.domain

import app.stillroom.fractions.QuantityFormatter
import app.stillroom.fractions.QuantityFractions
import java.math.BigDecimal
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.json.*

/**
 * Text-field state of a master-data form mapped to a Grocy payload. Used by the one record
 * editor (Household → Records and Pantry → Add/Edit product).
 *
 * - Create sends every filled field. Product consume/price units default to the stock/purchase
 *   unit, exactly as Grocy's insert triggers do when they are omitted.
 * - Edit sends only fields whose value changed, so concurrent edits of other fields in Grocy's
 *   web app are not overwritten. A blanked number is omitted (Grocy keeps its stored value).
 * - An unedited decimal keeps its stored value even when its display is approximate (`≈⅓`).
 */
class CatalogForm(val entity: CatalogEntity, val original: JsonObject?, private val formatter: QuantityFormatter = QuantityFormatter(), private val locale: Locale = Locale.getDefault()) {
    val fields: List<CatalogField> = CatalogFields.fields(entity)
    val creating: Boolean get() = original == null
    private val defaultedOnCreate = if (entity == CatalogEntity.Products) mapOf("qu_id_consume" to "qu_id_stock", "qu_id_price" to "qu_id_purchase") else emptyMap()

    private fun stored(f: CatalogField): String? = (original?.get(f.name) as? JsonPrimitive)?.contentOrNull

    fun initialText(f: CatalogField): String = when (f.kind) {
        CatalogKind.Decimal -> stored(f)?.toBigDecimalOrNull()?.let { if (it.signum() < 0) it.toPlainString() else formatter.format(it, locale) }.orEmpty()
        CatalogKind.Toggle -> stored(f) ?: if (creating && f.name == "active") "1" else "0"
        else -> stored(f).orEmpty()
    }

    fun initial(): Map<String, String> = fields.associate { it.name to initialText(it) }

    /** True when the field may be left empty in this form. */
    fun optional(f: CatalogField): Boolean = !f.required || (creating && f.name in defaultedOnCreate)

    private fun parseDecimal(f: CatalogField, text: String): BigDecimal? {
        if (text == initialText(f)) stored(f)?.toBigDecimalOrNull()?.let { return it }
        return text.trim().toBigDecimalOrNull() ?: QuantityFractions().parse(text, locale)?.value
    }

    /** Field-level problems keyed by field name. */
    fun errors(texts: Map<String, String>): Map<String, String> = buildMap {
        for (f in fields) {
            val text = texts[f.name].orEmpty().trim()
            if (text.isEmpty()) {
                if (!optional(f) && f.kind != CatalogKind.Toggle) put(f.name, if (f.kind == CatalogKind.Reference) "Choose one." else "Required.")
                continue
            }
            when (f.kind) {
                CatalogKind.Text -> if (text.length > 50000) put(f.name, "Too long.")
                CatalogKind.Whole -> {
                    val n = text.toLongOrNull()
                    if (n == null || !(n >= 0 || (f.name.startsWith("default_best_before_days") && n == -1L))) put(f.name, if (f.name.startsWith("default_best_before_days")) "Enter whole days (0 or more, or -1 for never)." else "Enter a whole number, 0 or more.")
                }
                CatalogKind.Decimal -> {
                    val d = parseDecimal(f, text)
                    if (d == null) put(f.name, "Enter a number like 1.5 or 1½.")
                    else if (d.signum() < 0 || (f.name == "factor" && d.signum() == 0)) put(f.name, if (f.name == "factor") "Enter a factor above 0." else "Enter 0 or more.")
                }
                CatalogKind.Toggle -> if (text !in setOf("0", "1")) put(f.name, "Choose yes or no.")
                CatalogKind.Reference -> if (text.toLongOrNull()?.let { it > 0 } != true) put(f.name, "Choose one.")
            }
        }
        if (entity == CatalogEntity.Conversions) {
            val from = texts["from_qu_id"].orEmpty(); if (from.isNotEmpty() && from == texts["to_qu_id"]) put("to_qu_id", "Choose two different units.")
        }
    }

    private fun value(f: CatalogField, raw: String): JsonElement? {
        val text = if (f.kind == CatalogKind.Text) raw else raw.trim()
        return when (f.kind) {
            CatalogKind.Text -> if (text.isBlank() && f.name != "description") null else JsonPrimitive(text)
            CatalogKind.Whole -> text.toLongOrNull()?.let(::JsonPrimitive)
            CatalogKind.Decimal -> if (text.isEmpty()) null else parseDecimal(f, text)?.let { Json.parseToJsonElement(it.toPlainString()) }
            CatalogKind.Toggle -> JsonPrimitive(if (text == "1") 1 else 0)
            CatalogKind.Reference -> text.toLongOrNull()?.let(::JsonPrimitive) ?: JsonNull
        }
    }

    private fun same(a: JsonElement?, b: JsonElement?): Boolean {
        val x = (a as? JsonPrimitive)?.contentOrNull; val y = (b as? JsonPrimitive)?.contentOrNull
        if (x.isNullOrEmpty() && y.isNullOrEmpty()) return true
        val dx = x?.toBigDecimalOrNull(); val dy = y?.toBigDecimalOrNull()
        return if (dx != null && dy != null) dx.compareTo(dy) == 0 else x == y
    }

    /** The Grocy payload. Throws [IllegalArgumentException] when [errors] is not empty. */
    fun payload(texts: Map<String, String>): JsonObject {
        val problems = errors(texts)
        require(problems.isEmpty()) { problems.values.first() }
        return buildJsonObject {
            for (f in fields) {
                val v = value(f, texts[f.name].orEmpty())
                if (creating) {
                    val filled = v ?: JsonNull
                    if (filled != JsonNull) put(f.name, filled)
                    else defaultedOnCreate[f.name]?.let { source -> value(fields.first { it.name == source }, texts[source].orEmpty())?.takeIf { it != JsonNull }?.let { put(f.name, it) } }
                } else {
                    if (texts[f.name].orEmpty() == initialText(f)) continue // untouched: keep whatever Grocy has
                    if (v == null) continue // blank number or text that may not be cleared: keep the stored value
                    if (v == JsonNull && !optional(f)) continue
                    if (!same(v, original!![f.name])) put(f.name, v)
                }
            }
        }
    }
}

/** Keeps create-time unit defaults valid after a partial create points the editor at the new product id. */
object CatalogCreateHandoff {
    fun seedUnits(texts: Map<String, String>): Map<String, String> {
        val next = texts.toMutableMap()
        val stock = texts["qu_id_stock"].orEmpty()
        val purchase = texts["qu_id_purchase"].orEmpty()
        if (next["qu_id_consume"].isNullOrBlank() && stock.isNotBlank()) next["qu_id_consume"] = stock
        if (next["qu_id_price"].isNullOrBlank() && purchase.isNotBlank()) next["qu_id_price"] = purchase
        return next
    }
}

/** Product follow-up writes: a purchase → stock conversion factor and new barcodes. */
data class ProductExtras(val purchaseToStockFactor: BigDecimal? = null, val newBarcodes: List<String> = emptyList()) {
    val isEmpty: Boolean get() = purchaseToStockFactor == null && newBarcodes.isEmpty()
}

object ProductExtrasForm {
    /**
     * The factor Grocy currently uses to turn one purchase unit into stock units for this product:
     * a product-specific conversion, else a global one, else 1 (what Grocy's insert trigger creates).
     */
    fun currentFactor(conversions: List<JsonObject>, productId: Long?, purchase: Long?, stock: Long?): BigDecimal {
        if (purchase == null || stock == null || purchase == stock) return BigDecimal.ONE
        fun match(product: Long?) = conversions.firstOrNull { it.catalogId("from_qu_id") == purchase && it.catalogId("to_qu_id") == stock && it.catalogId("product_id") == product }
        return ((productId?.let { match(it) } ?: match(null))?.catalogText("factor")?.toBigDecimalOrNull())?.takeIf { it.signum() > 0 } ?: BigDecimal.ONE
    }

    fun parseBarcodes(text: String): List<String> = text.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }

    /** Problems keyed by "factor" / "barcodes". */
    fun errors(factorText: String, purchase: Long?, stock: Long?, barcodes: List<String>, existingBarcodes: Collection<String>, locale: Locale = Locale.getDefault()): Map<String, String> = buildMap {
        if (purchase != null && stock != null && purchase != stock && factorText.isNotBlank()) {
            val f = factorText.trim().toBigDecimalOrNull() ?: QuantityFractions().parse(factorText, locale)?.value
            if (f == null || f.signum() <= 0) put("factor", "Enter how many stock units one purchase unit holds, above 0.")
        }
        val duplicate = barcodes.groupBy { it }.filter { it.value.size > 1 }.keys.firstOrNull()
        val taken = barcodes.firstOrNull { it in existingBarcodes }
        when {
            duplicate != null -> put("barcodes", "Barcode $duplicate is listed twice.")
            taken != null -> put("barcodes", "Barcode $taken already belongs to a product in Grocy.")
            barcodes.any { it.length > 200 } -> put("barcodes", "A barcode is too long.")
        }
    }

    /** Extras to send: the factor only when it differs from what Grocy already uses. */
    fun build(factorText: String, current: BigDecimal, purchase: Long?, stock: Long?, barcodes: List<String>, locale: Locale = Locale.getDefault()): ProductExtras {
        val factor = if (purchase != null && stock != null && purchase != stock && factorText.isNotBlank())
            (factorText.trim().toBigDecimalOrNull() ?: QuantityFractions().parse(factorText, locale)?.value)?.takeIf { it.compareTo(current) != 0 }
        else null
        return ProductExtras(factor, barcodes)
    }
}

/** Ids of the writes that follow a record save; deterministic so a resync never duplicates them. */
object CatalogFollowUps {
    fun id(base: String, suffix: String): String = UUID.nameUUIDFromBytes("$base:$suffix".toByteArray()).toString()
    const val CUSTOM = "custom"
    const val CONVERSION = "conversion"
    fun barcode(index: Int) = "barcode:$index"
}

/** Result of a record save, after sending. Only [Saved] closes the editor. */
sealed interface CatalogSaveOutcome {
    data class Saved(val id: Long?) : CatalogSaveOutcome
    data class Failed(val message: String) : CatalogSaveOutcome
    /** The record was written but some follow-up (custom fields, conversion, barcode) was not. */
    data class Partial(val id: Long, val message: String) : CatalogSaveOutcome
    /** Not confirmed either way (in flight, or unknown HTTP outcome); visible in Pending changes. */
    data class Unconfirmed(val message: String) : CatalogSaveOutcome
}

data class OutboxView(val state: String, val detail: String? = null, val response: String? = null)

object CatalogOutcomes {
    /**
     * [main] is the record write (null when only follow-ups were sent, e.g. a userfield-only edit).
     * [followUps] pairs a readable label with each expected follow-up's outbox row (null if not queued yet).
     */
    fun evaluate(main: OutboxView?, knownId: Long?, followUps: List<Pair<String, OutboxView?>>): CatalogSaveOutcome {
        if (main != null) when (main.state) {
            "failed" -> return CatalogSaveOutcome.Failed(CatalogErrors.friendly(main.detail))
            "needs-review" -> return CatalogSaveOutcome.Unconfirmed(CatalogErrors.UNCONFIRMED)
            "confirmed" -> Unit
            else -> return CatalogSaveOutcome.Unconfirmed("Saving… the change has not been confirmed yet. It is listed in Settings → Pending changes.")
        }
        val id = knownId ?: main?.response?.let { runCatching { Json.parseToJsonElement(it).jsonObject.catalogId("created_object_id") }.getOrNull() }
        val problems = followUps.mapNotNull { (label, row) ->
            when (row?.state) {
                "confirmed" -> null
                "failed" -> "$label: ${CatalogErrors.friendly(row.detail)}"
                "needs-review" -> "$label: not confirmed (see Pending changes)"
                else -> "$label: not sent yet"
            }
        }
        if (problems.isEmpty()) return CatalogSaveOutcome.Saved(id)
        val message = "Saved, but not everything was stored. " + problems.joinToString("; ") + ". Your entries are still in the form; save again to retry."
        return if (id != null) CatalogSaveOutcome.Partial(id, message) else CatalogSaveOutcome.Unconfirmed(message)
    }
}

object CatalogErrors {
    const val OFFLINE = "Could not reach Grocy. Nothing was saved; your entries are still in the form. Try again when connected."
    const val UNCONFIRMED = "Grocy did not confirm whether this was saved. Check Settings → Pending changes before trying again; nothing is resent automatically."

    /** Grocy's message made readable; unknown messages are shown as Grocy sent them. */
    fun friendly(detail: String?): String {
        val text = detail.orEmpty()
        return when {
            text.contains("UNIQUE constraint failed: products.name", true) -> "A product with this name already exists in Grocy."
            text.contains("UNIQUE constraint failed: product_barcodes.barcode", true) -> "This barcode already belongs to a product in Grocy."
            text.contains("MASTER_DATA_EDIT") || text.contains("HTTP 403") -> "Your Grocy user may not change master data (needs the MASTER_DATA_EDIT permission)."
            text.contains("HTTP 401") -> "Grocy rejected the API key. Reconnect the account."
            text.contains("qu_id_stock can only be changed", true) || text.contains("qu_id_stock cannot be changed", true) ->
                "Grocy only allows changing the stock unit of a product that has been in stock when a conversion from the old to the new unit exists."
            text.contains("QU conversion already exists", true) -> "Grocy already has this unit conversion."
            text == "Failed requirement." || text.isBlank() -> "Check the highlighted fields."
            else -> Regex("^HTTP (\\d+): (.+)$", RegexOption.DOT_MATCHES_ALL).find(text)?.let { "Grocy rejected the change: ${it.groupValues[2]}" }
                ?: if (text.startsWith("HTTP ")) "Grocy rejected the change ($text)." else text
        }
    }
}
