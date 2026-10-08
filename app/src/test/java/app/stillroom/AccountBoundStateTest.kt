package app.stillroom

import app.stillroom.ui.AccountBoundState
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature view models publish from session work on IO threads while the account collector
 * resets state on the main thread. A previous account's result must never survive the reset.
 */
class AccountBoundStateTest {
    @Test fun publishAfterSwitchIsDropped() {
        val state = AccountBoundState("account A: empty")
        val accountA = state.publisher()
        assertTrue(accountA.publish { "account A: pantry" })
        state.reset("account B: empty")
        assertFalse(accountA.current)
        assertFalse(accountA.publish { "account A: late pantry" })
        assertEquals("account B: empty", state.value)
        val accountB = state.publisher()
        assertTrue(accountB.publish { "account B: pantry" })
        assertEquals("account B: pantry", state.value)
    }

    @Test fun aRacingLateResultNeverSurvivesTheSwitch() {
        repeat(500) { round ->
            val state = AccountBoundState("A")
            val stale = state.publisher()
            val started = CountDownLatch(1)
            val writer = thread {
                started.countDown()
                repeat(200) { stale.publish { "A-result" } }
            }
            started.await()
            state.reset("B")
            writer.join()
            assertEquals("round $round", "B", state.value)
        }
    }

    @Test fun uiEventsUpdateTheCurrentAccountDirectly() {
        val state = AccountBoundState(0)
        state.update { it + 1 }
        assertEquals(1, state.value)
    }
}
