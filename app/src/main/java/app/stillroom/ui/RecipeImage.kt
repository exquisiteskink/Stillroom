package app.stillroom.ui

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Pixel budget for a recipe tile in a grid; a tile is far smaller than the header image. */
const val TILE_DECODED_PIXELS: Long = 1_000_000L

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

/**
 * The single display path for picture bytes: downsampled and decoded off the main thread.
 *
 * Keyed on the array instance. The view model keeps one instance per picture name, so a
 * recomposition never re-decodes; a different picture clears the previous image first, so a
 * recipe never briefly shows another recipe's photo while its own decodes.
 */
@Composable
fun rememberDownsampledImage(bytes: ByteArray?, budget: Long = MAX_DECODED_PIXELS): ImageBitmap? {
    val image by produceState<ImageBitmap?>(null, bytes, budget) {
        value = null
        value = bytes?.let { withContext(Dispatchers.Default) { decodeDownsampled(it, budget) } }
    }
    return image
}
