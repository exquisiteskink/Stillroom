package app.stillroom.domain

import app.stillroom.fractions.QuantityFormatter
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Base64
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * Settings → Stock → Shown details: which attributes each pantry (stock overview) row shows.
 *
 * Display only. Nothing here writes to Grocy; userfield values are read from the
 * `/objects/products` rows the pantry already loads (Grocy returns them inline as `userfields`).
 */

/** Where a detail renders. Main details keep their fixed place in the row; extras are lines under the name. */
enum class StockDetailSlot { Main, Extra }

/**
 * Built-in attributes, all taken from data the pantry screen already loads
 * (`/stock`, `/objects/products`, `/objects/locations`, `/objects/product_groups`, `/objects/product_barcodes`).
 * [defaultVisible] reproduces the row as it looked before this setting existed: amount, unit and due date.
 */
enum class StockBuiltIn(val id: String, val label: String, val rowLabel: String, val slot: StockDetailSlot, val defaultVisible: Boolean) {
    Amount("amount", "Amount in stock", "Amount", StockDetailSlot.Main, true),
    Unit("unit", "Stock unit", "Unit", StockDetailSlot.Main, true),
    DueDate("due", "Next due date", "Due", StockDetailSlot.Main, true),
    Opened("opened", "Opened amount", "Opened", StockDetailSlot.Extra, false),
    Subproducts("aggregated", "Total including subproducts", "With subproducts", StockDetailSlot.Extra, false),
    Location("location", "Default location", "Default location", StockDetailSlot.Extra, false),
    ProductGroup("product_group", "Product group", "Group", StockDetailSlot.Extra, false),
    Value("value", "Stock value", "Value", StockDetailSlot.Extra, false),
    MinStock("min_stock", "Minimum stock amount", "Min. stock", StockDetailSlot.Extra, false),
    Barcodes("barcodes", "Barcodes", "Barcodes", StockDetailSlot.Extra, false),
    ;
    val key: String get() = "builtin:$id"
}

/** A product userfield definition from `GET /objects/userfields` (entity `products`). */
data class UserfieldDefinition(
    val name: String,
    val caption: String,
    val type: String,
    val showAsColumn: Boolean = false,
    val sortNumber: Int? = null,
) {
    val key: String get() = "userfield:$name"
    val label: String get() = caption.ifBlank { name }

    companion object {
        const val ENTITY = "products"

        /** Product userfields in Grocy's own order (sort number, then caption). Malformed rows are skipped. */
        fun parse(rows: List<JsonObject>): List<UserfieldDefinition> = rows
            .filter { it.str("entity") == ENTITY && it.str("name").isNotBlank() }
            .map {
                UserfieldDefinition(
                    name = it.str("name"), caption = it.str("caption"), type = it.str("type"),
                    showAsColumn = it.str("show_as_column_in_tables").let { v -> v == "1" || v.equals("true", true) },
                    sortNumber = it.str("sort_number").toIntOrNull(),
                )
            }
            .distinctBy { it.name }
            .sortedWith(compareBy<UserfieldDefinition>({ it.sortNumber == null }, { it.sortNumber ?: 0 }, { it.label.lowercase() }))
    }
}

/** One saved choice. The saved list order is the display order of extra details. */
data class StockDetailSetting(val key: String, val visible: Boolean)

/** One attribute as offered in Settings and rendered on rows. */
data class StockDetailItem(
    val key: String,
    val label: String,
    val visible: Boolean,
    val slot: StockDetailSlot,
    val builtIn: StockBuiltIn? = null,
    val field: UserfieldDefinition? = null,
)

/**
 * Reconciles saved choices with what this Grocy account currently offers.
 *
 * - [saved] is null until the user changes something: every attribute uses its default.
 * - [fields] is null when userfield definitions are unknown (not loaded yet, older server, no
 *   access). Saved userfield choices are then kept untouched, so a failed fetch never erases them.
 * - Saved userfields that the server no longer defines are pruned once definitions are known.
 * - Attributes not in [saved] (new built-ins, userfields added on the server) are appended with
 *   their default visibility: built-ins per [StockBuiltIn.defaultVisible], userfields hidden.
 */
class StockDetails(private val saved: List<StockDetailSetting>?, val fields: List<UserfieldDefinition>?) {
    private val available: Map<String, StockDetailItem> = buildMap {
        StockBuiltIn.entries.forEach { put(it.key, StockDetailItem(it.key, it.label, it.defaultVisible, it.slot, builtIn = it)) }
        fields.orEmpty().forEach { put(it.key, StockDetailItem(it.key, it.label, DEFAULT_USERFIELD_VISIBLE, StockDetailSlot.Extra, field = it)) }
    }

    /** Settings to persist after a change: known entries in saved order, retained unknowns, then new defaults. */
    val persisted: List<StockDetailSetting> = buildList {
        val seen = mutableSetOf<String>()
        saved.orEmpty().forEach { setting ->
            val keep = setting.key in available || (fields == null && setting.key.startsWith(USERFIELD_PREFIX))
            if (keep && seen.add(setting.key)) add(setting)
        }
        available.values.forEach { if (seen.add(it.key)) add(StockDetailSetting(it.key, it.visible)) }
    }

    /** Every attribute available now, in display order, with its effective visibility. */
    val items: List<StockDetailItem> = persisted.mapNotNull { setting -> available[setting.key]?.copy(visible = setting.visible) }

    val main: List<StockDetailItem> get() = items.filter { it.slot == StockDetailSlot.Main }
    val extras: List<StockDetailItem> get() = items.filter { it.slot == StockDetailSlot.Extra }
    val visibleExtras: List<StockDetailItem> get() = extras.filter { it.visible }

    fun shows(builtIn: StockBuiltIn) = items.first { it.builtIn == builtIn }.visible

    /** True when the effective choices equal the defaults (nothing customized that is still offered). */
    val isDefault: Boolean get() = items.all { it.visible == available.getValue(it.key).visible } &&
        extras.map { it.key } == StockDetails(null, fields).extras.map { it.key }

    fun toggle(key: String, visible: Boolean): List<StockDetailSetting> =
        persisted.map { if (it.key == key) it.copy(visible = visible) else it }

    /** Swaps an extra detail with its previous (up) or next (down) extra. Main details do not move. */
    fun move(key: String, up: Boolean): List<StockDetailSetting> {
        val order = extras.map { it.key }
        val index = order.indexOf(key)
        val target = if (up) index - 1 else index + 1
        if (index < 0 || target !in order.indices) return persisted
        val other = order[target]
        val list = persisted.toMutableList()
        val a = list.indexOfFirst { it.key == key }
        val b = list.indexOfFirst { it.key == other }
        list[a] = list[b].also { list[b] = list[a] }
        return list
    }

    companion object {
        const val USERFIELD_PREFIX = "userfield:"
        /**
         * Userfields start hidden, even when Grocy marks them "show as column in tables": showing
         * them by default would change existing pantry rows the moment this version is installed.
         * Settings points out which fields Grocy shows as columns so they are easy to switch on.
         */
        const val DEFAULT_USERFIELD_VISIBLE = false
    }
}

/** Per-account persistence of [StockDetailSetting] lists. Device-local UI preference, no secrets. */
interface StockDetailsStore {
    fun load(accountId: String): List<StockDetailSetting>?
    fun save(accountId: String, settings: List<StockDetailSetting>)
    fun clear(accountId: String)
}

class InMemoryStockDetailsStore : StockDetailsStore {
    private val values = mutableMapOf<String, List<StockDetailSetting>>()
    override fun load(accountId: String) = values[accountId]
    override fun save(accountId: String, settings: List<StockDetailSetting>) { values[accountId] = settings.toList() }
    override fun clear(accountId: String) { values.remove(accountId) }
}

object StockDetailsCodec {
    fun encode(settings: List<StockDetailSetting>): String = JsonArray(settings.map {
        JsonObject(mapOf("key" to JsonPrimitive(it.key), "visible" to JsonPrimitive(it.visible)))
    }).toString()

    /** Unreadable data falls back to defaults rather than failing the pantry. */
    fun decode(raw: String?): List<StockDetailSetting>? {
        if (raw.isNullOrBlank()) return null
        val array = runCatching { Json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return null
        return array.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val key = row.str("key").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            StockDetailSetting(key, row.str("visible") == "true")
        }
    }
}

/** Renders a userfield value by its Grocy type. Returns null when there is nothing to show. */
object UserfieldText {
    private val dateOut = { locale: Locale -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    private val dateTimeOut = { locale: Locale -> DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale) }

    fun render(type: String, raw: JsonElement?, quantities: QuantityFormatter, locale: Locale): String? {
        val value = (raw as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (value.isEmpty()) return null
        return when (type) {
            "text-single-line" -> value
            "text-multi-line" -> value.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" · ").take(MAX_TEXT).let { if (it.length == MAX_TEXT) "$it…" else it }
            "number-integral", "number-decimal" -> value.toBigDecimalOrNull()?.let { quantities.format(it, locale) } ?: value
            "number-currency" -> value.toBigDecimalOrNull()?.let { money(it, locale) } ?: value
            "date" -> runCatching { LocalDate.parse(value.take(10)).format(dateOut(locale)) }.getOrDefault(value)
            "datetime" -> runCatching { LocalDateTime.parse(value.replace(' ', 'T')).format(dateTimeOut(locale)) }.getOrElse {
                runCatching { LocalDate.parse(value.take(10)).format(dateOut(locale)) }.getOrDefault(value)
            }
            "checkbox" -> if (value == "1" || value.equals("true", true)) "Yes" else "No"
            "preset-list" -> value
            "preset-checklist" -> value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { null }
            "link" -> value
            "link-with-title" -> runCatching { Json.parseToJsonElement(value) as JsonObject }.getOrNull()
                ?.let { it.str("title").ifBlank { it.str("link") }.ifBlank { null } } ?: value
            "file" -> "File: ${userfileName(value)}"
            "image" -> "Image: ${userfileName(value)}"
            else -> value
        }
    }

    /** Grocy stores files as `<random>_<base64 file name>`; show the file name, or the raw value if it is not in that form. */
    fun userfileName(value: String): String {
        val encoded = value.substringAfter('_', "")
        if (encoded.isEmpty()) return value
        return runCatching { String(Base64.getDecoder().decode(encoded), Charsets.UTF_8) }.getOrNull()?.takeIf { it.isNotBlank() } ?: value
    }

    fun money(value: BigDecimal, locale: Locale): String {
        val text = value.setScale(2, RoundingMode.HALF_UP).toPlainString()
        val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        return if (separator == '.') text else text.replace('.', separator)
    }

    fun typeLabel(type: String): String = when (type) {
        "text-single-line" -> "Text"
        "text-multi-line" -> "Multi-line text"
        "number-integral" -> "Whole number"
        "number-decimal" -> "Decimal number"
        "number-currency" -> "Currency"
        "date" -> "Date"
        "datetime" -> "Date and time"
        "checkbox" -> "Checkbox"
        "preset-list" -> "Choice list"
        "preset-checklist" -> "Checklist"
        "link" -> "Link"
        "link-with-title" -> "Link with title"
        "file" -> "File"
        "image" -> "Image"
        else -> type.ifBlank { "Unknown type" }
    }

    private const val MAX_TEXT = 120
}

/** Lookups for one render pass, built once per list instead of once per row. */
class StockRowLookups(
    stock: List<JsonObject>,
    locations: List<JsonObject>,
    productGroups: List<JsonObject>,
    barcodes: List<JsonObject>,
) {
    /** First row per product, matching the previous `stock.find { … }` lookup. */
    val stockByProduct: Map<String, JsonObject> = buildMap { stock.forEach { putIfAbsent(it.str("product_id"), it) } }
    private val locationNames = locations.associate { it.str("id") to it.str("name") }
    private val groupNames = productGroups.associate { it.str("id") to it.str("name") }
    private val barcodesByProduct = barcodes.groupBy({ it.str("product_id") }, { it.str("barcode") })

    fun location(id: String) = locationNames[id].orEmpty()
    fun group(id: String) = groupNames[id].orEmpty()
    fun barcodes(productId: String) = barcodesByProduct[productId].orEmpty().filter { it.isNotBlank() }
}

/**
 * The visible extra lines for one pantry row, as "Label: value", in the user's order.
 * Attributes with no value for this product (zero opened amount, no group, empty userfield…) are skipped.
 */
fun stockRowExtras(
    details: StockDetails,
    product: JsonObject,
    lookups: StockRowLookups,
    quantities: QuantityFormatter,
    locale: Locale,
): List<String> {
    val id = product.str("id")
    val row = lookups.stockByProduct[id]
    val userfields = product["userfields"] as? JsonObject
    return details.visibleExtras.mapNotNull { item ->
        val text = item.field?.let { UserfieldText.render(it.type, userfields?.get(it.name), quantities, locale) } ?: when (item.builtIn) {
            StockBuiltIn.Opened -> row?.decimalOrNull("amount_opened")?.takeIf { it.signum() > 0 }?.let { quantities.format(it, locale) }
            StockBuiltIn.Subproducts -> row?.takeIf { it.str("is_aggregated_amount") == "1" || it.str("is_aggregated_amount") == "true" }
                ?.decimalOrNull("amount_aggregated")?.let { quantities.format(it, locale) }
            StockBuiltIn.Location -> lookups.location(product.str("location_id")).ifBlank { null }
            StockBuiltIn.ProductGroup -> lookups.group(product.str("product_group_id")).ifBlank { null }
            StockBuiltIn.Value -> row?.decimalOrNull("value")?.let { UserfieldText.money(it, locale) }
            StockBuiltIn.MinStock -> product.decimalOrNull("min_stock_amount")?.takeIf { it.signum() > 0 }?.let { quantities.format(it, locale) }
            StockBuiltIn.Barcodes -> lookups.barcodes(id).joinToString(", ").ifBlank { null }
            else -> null
        }
        text?.let { "${item.builtIn?.rowLabel ?: item.label}: $it" }
    }
}

private fun JsonObject.str(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.decimalOrNull(key: String): BigDecimal? = str(key).toBigDecimalOrNull()
