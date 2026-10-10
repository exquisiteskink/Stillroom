package app.stillroom.domain

/** Values remain strings: UPC/EAN leading zeros are significant. */
enum class ScanFormat { Ean13, Ean8, UpcA, UpcE, Qr, Other, Manual }
data class ScanCode(val raw: String, val format: ScanFormat) {
    fun validated(): ScanCode {
        require(raw.isNotBlank() && raw.length <= 512 && raw.none { it.isISOControl() }) { "Enter a barcode without control characters (maximum 512 characters)." }
        val valid = when (format) {
            ScanFormat.Ean13 -> raw.length == 13 && validGtin(raw)
            ScanFormat.Ean8 -> raw.length == 8 && validGtin(raw)
            ScanFormat.UpcA -> raw.length == 12 && validGtin(raw)
            ScanFormat.UpcE -> expandUpce(raw)?.let(::validGtin) == true
            ScanFormat.Manual -> if (raw.all { it in '0'..'9' } && raw.length in setOf(8,12,13,14)) validGtin(raw) else true
            else -> true
        }
        require(valid) { "UPC/EAN checksum is invalid. Check the printed digits." }
        return this
    }
    // QR and other arbitrary household codes are never sent to a public lookup provider.
    val publicLookup: Boolean get() = format in setOf(ScanFormat.Ean13,ScanFormat.Ean8,ScanFormat.UpcA,ScanFormat.UpcE,ScanFormat.Manual) &&
        (if (format == ScanFormat.UpcE) expandUpce(raw)?.let(::validGtin) == true else validGtin(raw))
    val publicCode: String get() = if (format == ScanFormat.UpcE) expandUpce(raw)!! else raw
}
fun validGtin(code: String): Boolean {
    if (code.length !in setOf(8,12,13,14) || code.any { it !in '0'..'9' }) return false
    val sum = code.dropLast(1).reversed().mapIndexed { index,c -> (c-'0') * if(index%2==0) 3 else 1 }.sum()
    return (10-sum%10)%10 == code.last()-'0'
}
fun expandUpce(code: String): String? {
    if (code.length != 8 || code.any { it !in '0'..'9' } || code.first() !in "01") return null
    val digits=code.substring(1,7)
    val body=when(digits.last()) {
        '0','1','2' -> digits.take(2)+digits.last()+"0000"+digits.substring(2,5)
        '3' -> digits.take(3)+"00000"+digits.substring(3,5)
        '4' -> digits.take(4)+"00000"+digits[4]
        else -> digits.take(5)+"0000"+digits.last()
    }
    return code.first()+body+code.last()
}
data class ScanRegion(val left: Float,val top: Float,val right: Float,val bottom: Float) {
    fun containsCenter(bounds: ScanRegion): Boolean = (bounds.left+bounds.right)/2 in left..right && (bounds.top+bounds.bottom)/2 in top..bottom
}
class ScanSession {
    private val seen=mutableSetOf<String>()
    fun offer(codes: List<ScanCode>): List<ScanCode> = codes.filter { runCatching { it.validated() }.isSuccess && it.raw !in seen }.distinctBy { it.raw }
    fun choose(code: ScanCode) { code.validated();seen.add(code.raw) }
    fun scanAnotherIdentical(code: ScanCode) { seen.remove(code.raw) }
}
data class ScanSuggestion(val code: ScanCode,val source: String,val name: String = "",val description: String = "",val productIds: List<Long> = emptyList(),val stale: Boolean = false,val barcodes:List<BarcodeMetadata> = emptyList(),val defaults:LookupProductDefaults=LookupProductDefaults(),val grocycode:Grocycode?=null)
data class ScanReview(val code: ScanCode,val name: String,val description: String,val unit: Long,val location: Long,val purchaseUnit:Long=unit,val purchaseToStockFactor:String?=null)
interface ScanRepository {
    suspend fun lookup(code: ScanCode): ScanSuggestion
    suspend fun choices(): Pair<List<Pair<Long,String>>,List<Pair<Long,String>>>
    suspend fun create(review: ScanReview): String
    suspend fun attach(productId: Long, code: ScanCode): String
    suspend fun sync()
    suspend fun createdProduct(operation: String): Long?
    suspend fun operations(): List<PendingChange>
}
class ManageScanner(private val repository: ScanRepository) {
    suspend fun lookup(code: ScanCode)=repository.lookup(code.validated())
    suspend fun choices()=repository.choices()
    suspend fun create(review: ScanReview)=repository.create(review)
    suspend fun attach(productId: Long, code: ScanCode)=repository.attach(productId, code.validated())
    suspend fun sync()=repository.sync()
    suspend fun createdProduct(operation: String)=repository.createdProduct(operation)
    suspend fun operations()=repository.operations()
}
