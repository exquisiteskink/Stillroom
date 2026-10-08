package app.stillroom.ui

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * UI state that belongs to one account at a time.
 *
 * Feature work runs inside the account session on `Dispatchers.IO` and publishes results from
 * there. Cancelling it on an account switch is cooperative: a block that is between suspension
 * points (parsing a snapshot, say) still finishes and writes. Without a fence, that write can
 * land after the view model reset its state for the new account, showing the previous
 * account's pantry, list or recipes under the new account until the next refresh, and letting
 * the user act on the previous account's row ids.
 *
 * [reset] advances the generation *before* replacing the state, and [Publisher.publish]
 * re-checks the generation inside the atomic `update` loop, so a stale write either lands
 * before the reset (and is overwritten by it) or is dropped. There is no interleaving in which
 * a previous account's result survives the reset.
 */
class AccountBoundState<T>(initial: T) {
    private val generation = AtomicLong()
    private val mutable = MutableStateFlow(initial)
    val flow: StateFlow<T> = mutable.asStateFlow()
    val value: T get() = mutable.value

    /** Start a new account context. Every earlier [Publisher] stops publishing. */
    fun reset(next: T) { generation.incrementAndGet(); mutable.value = next }

    /** A publisher bound to the current account context. */
    fun publisher() = Publisher(generation.get())

    /** Immediate change for the current account, from a UI event on the main thread. */
    fun update(change: (T) -> T) = mutable.update(change)

    inner class Publisher internal constructor(private val token: Long) {
        val current: Boolean get() = generation.get() == token
        /** Applies [change] only if no [reset] happened since this publisher was created. */
        fun publish(change: (T) -> T): Boolean {
            var applied = false
            mutable.update { state ->
                applied = generation.get() == token
                if (applied) change(state) else state
            }
            return applied
        }
    }
}
