package app.stillroom.domain

import app.stillroom.fractions.QuantityFractions
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Grocy userfield type names, from Grocy's `services/UserfieldsService.php` constants.
 * One model for every screen: Shown details renders with it, and the product and
 * Household records editors edit with it.
 */
object UserfieldTypes {
    const val TEXT = "text-single-line"
    const val MULTILINE = "text-multi-line"
    const val INTEGER = "number-integral"
    const val DECIMAL = "number-decimal"
    const val CURRENCY = "number-currency"
    const val DATE = "date"
    const val DATETIME = "datetime"
    const val CHECKBOX = "checkbox"
    const val PRESET_LIST = "preset-list"
    const val PRESET_CHECKLIST = "preset-checklist"
    const val LINK = "link"
    const val LINK_WITH_TITLE = "link-with-title"
    const val FILE = "file"
    const val IMAGE = "image"

    val ALL = listOf(TEXT, MULTILINE, INTEGER, DECIMAL, CURRENCY, DATE, DATETIME, CHECKBOX, PRESET_LIST, PRESET_CHECKLIST, LINK, LINK_WITH_TITLE, FILE, IMAGE)

    /**
     * Earlier Stillroom builds recognised `text`, `numeric` and `date-time`, which are not Grocy
     * types. Grocy accepts any string through its API, so fields created that way can exist; they
     * keep working as the matching real type.
     */
    private val legacy = mapOf("text" to TEXT, "numeric" to DECIMAL, "date-time" to DATETIME)

    fun canonical(type: String): String = legacy[type] ?: type
    fun known(type: String): Boolean = canonical(type) in ALL

    /**
     * Whether Stillroom can change this field's value. Files and images need Grocy's binary file
     * API (`/files/userfiles/…`), which Stillroom does not use; their values are shown and preserved.
     */
    fun editable(type: String): Boolean = known(type) && canonical(type) !in setOf(FILE, IMAGE)

    fun reason(field: UserfieldDefinition): String? = when {
        !known(field.type) -> "${field.label}: type '${field.type}' is not a Grocy userfield type, so it cannot be edited here. Its value is kept."
        !editable(field.type) -> "${field.label}: files and images can be changed in Grocy's web app. The current value is kept."
        else -> null
    }
}

/** Parsing, validation and serialization of userfield values as Grocy stores them (strings). */
object UserfieldValues {
    private val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:.+")
    const val MAX_LENGTH = 50000

    data class Link(val title: String, val link: String)

    /** Preset options from the definition's `config`, one per line. */
    fun options(field: UserfieldDefinition): List<String> =
        field.config.split(Regex("\r\n|\r|\n")).map { it.trim() }.filter { it.isNotEmpty() }

    /** Grocy stores checklist selections comma-separated. */
    fun checklist(value: String): List<String> = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** Selected options in definition order, then any stored values no longer offered (kept, not dropped). */
    fun checklistValue(selected: Collection<String>, options: List<String>): String =
        (options.filter { it in selected } + selected.filter { it !in options }).distinct().joinToString(",")

    /** Grocy stores link-with-title as JSON `{"title":…,"link":…}`. A plain string is treated as the link. */
    fun link(value: String): Link {
        if (value.isBlank()) return Link("", "")
        val json = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(value) as? JsonObject }.getOrNull()
            ?: return Link("", value)
        fun text(key: String) = (json[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return Link(text("title"), text("link"))
    }

    fun linkValue(title: String, link: String): String =
        if (title.isBlank() && link.isBlank()) "" else buildJsonObject { put("title", title.trim()); put("link", link.trim()) }.toString()

    /** Editor start value: the stored value, or on create the definition default ("now" for dates). */
    fun initial(field: UserfieldDefinition, stored: String?, creating: Boolean, now: LocalDateTime = LocalDateTime.now()): String = when {
        stored != null -> stored
        !creating -> ""
        field.canonicalType == UserfieldTypes.DATE && field.defaultValue == "now" -> now.toLocalDate().toString()
        field.canonicalType == UserfieldTypes.DATETIME && field.defaultValue == "now" -> now.withNano(0).format(dateTime)
        else -> field.defaultValue
    }

    /** The string to store. Throws [IllegalArgumentException] with a readable message when invalid. */
    fun normalize(field: UserfieldDefinition, value: String, locale: Locale = Locale.getDefault()): String {
        val type = field.canonicalType
        if (!UserfieldTypes.editable(field.type)) return value
        val text = if (type == UserfieldTypes.MULTILINE || type == UserfieldTypes.LINK_WITH_TITLE) value else value.trim()
        require(text.length <= MAX_LENGTH) { "${field.label} is too long." }
        if (text.isBlank()) {
            require(!field.inputRequired || type == UserfieldTypes.CHECKBOX) { "${field.label} is required." }
            return if (type == UserfieldTypes.CHECKBOX) "0" else ""
        }
        return when (type) {
            UserfieldTypes.TEXT -> { require('\n' !in text && '\r' !in text) { "${field.label} must be a single line." }; text }
            UserfieldTypes.MULTILINE -> text
            UserfieldTypes.INTEGER -> requireNotNull(text.toLongOrNull()) { "${field.label}: enter a whole number." }.toString()
            UserfieldTypes.DECIMAL, UserfieldTypes.CURRENCY -> requireNotNull(decimal(text, locale)) { "${field.label}: enter a number like 1.5." }.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()
            UserfieldTypes.DATE -> requireNotNull(runCatching { LocalDate.parse(text) }.getOrNull()) { "${field.label}: use YYYY-MM-DD." }.toString()
            UserfieldTypes.DATETIME -> requireNotNull(dateTime(text)) { "${field.label}: use YYYY-MM-DD HH:mm." }.format(dateTime)
            UserfieldTypes.CHECKBOX -> { require(text in setOf("0", "1")) { "${field.label}: choose yes or no." }; text }
            UserfieldTypes.PRESET_LIST -> { val options = options(field); require(options.isEmpty() || text in options) { "${field.label}: choose one of the options." }; text }
            UserfieldTypes.PRESET_CHECKLIST -> checklist(text).joinToString(",").also { require(!field.inputRequired || it.isNotEmpty()) { "${field.label} is required." } }
            UserfieldTypes.LINK -> { require(scheme.matches(text)) { "${field.label}: enter a full link such as https://example.org." }; text }
            UserfieldTypes.LINK_WITH_TITLE -> {
                val link = link(text)
                require(link.link.isBlank() || scheme.matches(link.link.trim())) { "${field.label}: enter a full link such as https://example.org." }
                require(link.link.isNotBlank() || link.title.isBlank()) { "${field.label}: add the link for this title." }
                require(!field.inputRequired || link.link.isNotBlank()) { "${field.label} is required." }
                linkValue(link.title, link.link)
            }
            else -> text
        }
    }

    /** A readable problem with [value], or null when it can be saved. */
    fun error(field: UserfieldDefinition, value: String, locale: Locale = Locale.getDefault()): String? =
        runCatching { normalize(field, value, locale) }.exceptionOrNull()?.let { it.message ?: "${field.label} is not valid." }

    /**
     * Only the values that change, like Grocy's own web form (it sends only edited inputs).
     * Read-only types (file, image, unknown) are never sent, so their stored value is kept.
     */
    fun changes(fields: List<UserfieldDefinition>, original: JsonObject?, edited: Map<String, String>, locale: Locale = Locale.getDefault()): JsonObject = buildJsonObject {
        for (field in fields) {
            if (!UserfieldTypes.editable(field.type)) continue
            val value = edited[field.name] ?: continue
            val stored = (original?.get(field.name) as? JsonPrimitive)?.contentOrNull.orEmpty()
            val next = normalize(field, value, locale)
            val unchanged = next == stored || (next.isEmpty() && stored.isEmpty()) ||
                (field.canonicalType == UserfieldTypes.CHECKBOX && next == "0" && stored.isEmpty() && original != null) ||
                (field.canonicalType in setOf(UserfieldTypes.DECIMAL, UserfieldTypes.CURRENCY, UserfieldTypes.INTEGER) &&
                    stored.toBigDecimalOrNull()?.let { s -> next.toBigDecimalOrNull()?.compareTo(s) == 0 } == true)
            if (!unchanged && !(original == null && next.isEmpty())) put(field.name, next)
        }
    }

    private fun decimal(text: String, locale: Locale): BigDecimal? =
        text.toBigDecimalOrNull() ?: QuantityFractions().parse(text, locale)?.value

    private fun dateTime(text: String): LocalDateTime? {
        val t = text.replace('T', ' ')
        return runCatching { LocalDateTime.parse(t, dateTime) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(t, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) }.getOrNull()
            ?: runCatching { LocalDate.parse(t).atStartOfDay() }.getOrNull()
    }
}
