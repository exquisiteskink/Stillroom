package app.stillroom.ui

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Decode [bytes] downsampled to [budget] pixels.
 *
 * Every previous call site decoded at full resolution. A 12MP phone photo is ~48MB of
 * ARGB_8888, and Grocy accepts uploads up to 5MB, so that is the realistic worst case rather
 * than a theoretical one. Returns null when the bytes are not a decodable image, matching
 * `BitmapFactory`'s own contract.
 */
fun decodeDownsampled(bytes: ByteArray, budget: Long = MAX_DECODED_PIXELS): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = decodeSampleSize(bounds.outWidth, bounds.outHeight, budget)
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}
