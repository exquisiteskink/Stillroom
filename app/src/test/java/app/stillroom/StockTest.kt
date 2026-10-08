package app.stillroom

import app.stillroom.domain.*
import app.stillroom.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class StockTest {
    @Test fun deniedAndUnknownGrantsNeverExposeStock() {
        assertFalse(StockAccess.canRead(null))
        assertFalse(StockAccess.canRead(setOf("CHORES")))
        assertTrue(StockAccess.canRead(setOf("ADMIN")))
        assertTrue(StockAccess.canRead(setOf("STOCK")))
        assertFalse(StockAccess.canWrite(setOf("STOCK_CONSUME"), StockAction.Purchase))
        assertTrue(StockAccess.canWrite(setOf("STOCK_CONSUME"), StockAction.Spoilage))
    }
    @Test fun conversionUsesServerFactorAndNumericPayload() {
        val mutation = StockBooking(StockAction.Purchase, 3, BigDecimal("1.25"), BigDecimal("12"), price = BigDecimal("2.50"))
        val payload = mutation.payload()
        assertEquals(BigDecimal("15.00"), payload["amount"]!!.jsonPrimitive.content.toBigDecimal())
        assertFalse(payload["amount"]!!.jsonPrimitive.isString)
        assertEquals("purchase", payload["transaction_type"]!!.jsonPrimitive.content)
        assertEquals("/stock/products/3/add", mutation.path())
    }
    @Test fun allActionsAndInvalidInputs() {
        for (action in StockAction.entries) {
            val booking = StockBooking(action, 1, BigDecimal("2"), location = 1, destination = 2)
            assertTrue(booking.path().startsWith("/stock/products/1/"))
            val body = booking.payload()
            if (action == StockAction.Inventory) assertNotNull(body["new_amount"])
            if (action == StockAction.Spoilage) assertEquals(true, body["spoiled"]!!.jsonPrimitive.boolean)
            if (action == StockAction.Transfer) assertEquals(2, body["location_id_to"]!!.jsonPrimitive.int)
        }
        assertThrows(IllegalArgumentException::class.java) { StockBooking(StockAction.Consume, 1, BigDecimal.ZERO).payload() }
        assertThrows(IllegalArgumentException::class.java) { StockBooking(StockAction.Transfer, 1, BigDecimal.ONE).payload() }
        assertThrows(IllegalArgumentException::class.java) { StockBooking(StockAction.Purchase, 1, BigDecimal.ONE, date = "bad").payload() }
        assertEquals("0", StockBooking(StockAction.Inventory, 1, BigDecimal.ZERO).payload()["new_amount"]!!.jsonPrimitive.content)
    }
}
