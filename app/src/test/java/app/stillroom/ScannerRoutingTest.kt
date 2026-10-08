package app.stillroom

import app.stillroom.domain.*
import app.stillroom.ui.*
import org.junit.Assert.*
import org.junit.Test

class ScannerRoutingTest {
    @Test fun onlyAnUnambiguousProductAutomaticallyOpensReview() {
        assertEquals(7L, automaticScanProduct(listOf(7L)))
        assertNull(automaticScanProduct(emptyList()))
        assertNull(automaticScanProduct(listOf(7L,8L)))
    }
    @Test fun outcomeBelongsToSubmittedOperationAndNeverInfersSuccessFromIdle() {
        val historical=PendingChange("old","POST","/stock/products/7/add","confirmed",null,false)
        assertNull(scanBookingOutcome(null,listOf(historical)))
        assertEquals("Waiting to sync",scanBookingOutcome("new",listOf(historical)))
        val uncertain=historical.copy(clientOperationId="new",state="needs-review")
        assertEquals("Could not confirm this change. Check changes before trying again.",scanBookingOutcome("new",listOf(historical,uncertain)))
        assertEquals("Saved in Grocy",scanBookingOutcome("new",listOf(uncertain.copy(state="confirmed"))))
    }
    @Test fun unresolvedCreationBlocksRearmingEvenWhenProductPostWasConfirmed() {
        // A confirmed product POST alone does not confirm its separate barcode attachment.
        assertTrue(scanCreationLocked("create-operation",null))
        assertFalse(scanCreationLocked("create-operation",7L))
        assertFalse(scanCreationLocked(null,null))
    }
    @Test fun duplicateFramesDoNotReauthorizePackageAndExplicitRepeatDoes() {
        val code=ScanCode("00123457",ScanFormat.Ean8)
        val session=ScanSession()
        assertEquals(listOf(code),session.offer(listOf(code,code)))
        session.choose(code)
        assertTrue(session.offer(listOf(code)).isEmpty())
        session.scanAnotherIdentical(code)
        assertEquals(listOf(code),session.offer(listOf(code)))
    }
}
