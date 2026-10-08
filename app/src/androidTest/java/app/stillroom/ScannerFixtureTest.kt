package app.stillroom

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.stillroom.data.BarcodeDecoder
import app.stillroom.domain.*
import com.google.android.gms.tasks.Tasks
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ScannerFixtureTest {
    @Test fun bundledDetectorFixtureTargetChoicesAndDuplicateSuppression() {
        val context=InstrumentationRegistry.getInstrumentation().context
        val bitmap=context.assets.open("scanner/multi.png").use(BitmapFactory::decodeStream)
        BarcodeDecoder().use { decoder ->
            val barcodes=Tasks.await(decoder.scan(bitmap),30,TimeUnit.SECONDS)
            val all=decoder.inTarget(barcodes,ScanRegion(45f,35f,855f,665f))
            assertEquals(setOf("00123457","STILLROOM-FIXTURE-QR"),all.map { it.raw }.toSet())
            val grocery=all.single { it.raw=="00123457" }
            assertEquals(ScanFormat.Ean8,grocery.format)
            assertTrue(grocery.publicLookup)
            val qr=all.single { it.format==ScanFormat.Qr }
            assertFalse(qr.publicLookup)
            val topOnly=decoder.inTarget(barcodes,ScanRegion(45f,35f,855f,300f))
            assertEquals(listOf(grocery),topOnly)
            val session=ScanSession()
            assertEquals(2,session.offer(all).size) // Multi-code choice, without automatic booking.
            session.choose(grocery)
            assertEquals(listOf(qr),session.offer(all))
            session.scanAnotherIdentical(grocery)
            assertEquals(2,session.offer(all).size)
            val again=Tasks.await(decoder.scan(bitmap),30,TimeUnit.SECONDS)
            assertEquals(all.map { it.raw }.toSet(),decoder.inTarget(again,ScanRegion(45f,35f,855f,665f)).map { it.raw }.toSet())
        }
        bitmap.recycle()
    }
}
