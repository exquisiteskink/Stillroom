package app.stillroom
import app.stillroom.domain.*
import app.stillroom.ui.ViewfinderInset
import app.stillroom.ui.barcodeViewfinderRegion
import org.junit.Test
import org.junit.Assert.*
class ScannerTest {
    @Test fun leadingZerosAndChecksums() {
        assertEquals("0036000291452", ScanCode("0036000291452", ScanFormat.Ean13).validated().raw)
        assertTrue(validGtin("036000291452"))
        assertTrue(validGtin("96385074"))
        assertFalse(validGtin("036000291453"))
        assertThrows(IllegalArgumentException::class.java) { ScanCode("036000291453", ScanFormat.UpcA).validated() }
        assertEquals("household://shelf/1",ScanCode("household://shelf/1",ScanFormat.Qr).validated().raw)
        assertFalse(ScanCode("household://shelf/1",ScanFormat.Qr).publicLookup)
        assertFalse(ScanCode("0036000291452",ScanFormat.Qr).publicLookup)
    }
    @Test fun duplicateChoiceAndExplicitSamePackageReset() {
        val session=ScanSession()
        val a=ScanCode("0036000291452",ScanFormat.Ean13)
        val b=ScanCode("shelf",ScanFormat.Qr)
        assertEquals(listOf(a,b),session.offer(listOf(a,b,a)))
        session.choose(a)
        assertEquals(listOf(b),session.offer(listOf(a,b)))
        session.scanAnotherIdentical(a)
        assertEquals(listOf(a,b),session.offer(listOf(a,b)))
    }
    @Test fun targetRegionAndUpcExpansion() {
        assertTrue(ScanRegion(.1f,.1f,.9f,.9f).containsCenter(ScanRegion(.3f,.3f,.6f,.6f)))
        assertFalse(ScanRegion(.1f,.1f,.9f,.9f).containsCenter(ScanRegion(0f,0f,.1f,.1f)))
        assertEquals("042100005264",expandUpce("04252614"))
        assertEquals("04252614",ScanCode("04252614",ScanFormat.UpcE).validated().raw)
    }
    @Test fun viewfinderRegionIsTheVisibleCameraWindow() {
        assertEquals(0.1f, ViewfinderInset)
        val window=barcodeViewfinderRegion(800,240)
        assertEquals(ScanRegion(0f,0f,800f,240f), window)
        assertTrue(window.containsCenter(ScanRegion(0f,0f,10f,10f)))
        assertEquals(ScanRegion(0f,0f,0f,0f), barcodeViewfinderRegion(0,240))
    }
}
