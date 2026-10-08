package app.stillroom.ui

/**
 * Largest decoded pixel budget for an in-app photo.
 *
 * The recipe header renders at 220dp and the stock thumbnail far smaller, so 4MP is already
 * more detail than any surface in this app can display. It also caps worst-case decode memory
 * near 16MB instead of the ~48MB a 12MP phone photo costs at full resolution.
 */
const val MAX_DECODED_PIXELS: Long = 4_000_000L

/**
 * Power-of-two sample size that brings `width * height` under [budget].
 *
 * `BitmapFactory` rounds a non-power-of-two `inSampleSize` down, so only powers of two are
 * meaningful here. Returns 1 when the image already fits, and never returns 0, which
 * `BitmapFactory` rejects.
 */
fun decodeSampleSize(width: Int, height: Int, budget: Long = MAX_DECODED_PIXELS): Int {
    if (width <= 0 || height <= 0) return 1
    var sample = 1
    var pixels = width.toLong() * height.toLong()
    while (pixels > budget) {
        sample *= 2
        pixels /= 4
    }
    return sample
}
