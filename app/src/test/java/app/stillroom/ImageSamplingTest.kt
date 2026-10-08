package app.stillroom

import app.stillroom.ui.decodeSampleSize
import org.junit.Assert.*
import org.junit.Test

/**
 * `decodeSampleSize` is pure integer arithmetic, so it is testable on the host JVM without
 * `BitmapFactory` — unlike the decode call sites it serves.
 */
class ImageSamplingTest {

    @Test fun imagesAlreadyUnderBudgetAreNotDownsampled() {
        assertEquals(1, decodeSampleSize(1000, 1000))
    }

    @Test fun oversizedImagesAreReducedUntilTheyFit() {
        // 4000x3000 is 12MP. Sample 2 gives 2000x1500 = 3MP, under the 4MP budget, so the
        // loop must stop there rather than going to 4.
        assertEquals(2, decodeSampleSize(4000, 3000))
    }

    @Test fun anImageExactlyAtBudgetIsNotDownsampled() {
        // 16MP -> sample 2 -> 4MP, which equals the budget and so must stop.
        assertEquals(2, decodeSampleSize(4000, 4000))
    }

    @Test fun resultIsAlwaysAPowerOfTwoAndAlwaysFitsTheBudget() {
        val cases = listOf(1 to 1, 512 to 512, 4000 to 3000, 4000 to 4000, 8000 to 6000, 12000 to 9000)
        for ((width, height) in cases) {
            val sample = decodeSampleSize(width, height)
            assertTrue("sample must be positive for ${width}x$height", sample >= 1)
            assertEquals(
                "sample must be a power of two for ${width}x$height",
                0,
                Integer.bitCount(sample) - 1,
            )
            // BitmapFactory rounds each dimension down to a whole sample step.
            val decoded = ((width + sample - 1) / sample).toLong() * ((height + sample - 1) / sample)
            assertTrue(
                "decoded $decoded pixels for ${width}x$height at sample $sample exceeds the budget",
                decoded <= 4_000_000L,
            )
        }
    }

    @Test fun invalidDimensionsFallBackToNoSampling() {
        assertEquals(1, decodeSampleSize(0, 500))
        assertEquals(1, decodeSampleSize(500, -1))
    }
}
