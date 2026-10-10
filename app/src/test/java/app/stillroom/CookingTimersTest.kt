package app.stillroom

import android.app.Application
import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.stillroom.background.CookingTimerStore
import app.stillroom.domain.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class CookingTimersTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val account=AccountId.of(ServerAddress.parse("example.org"),4)
    private val other=AccountId.of(ServerAddress.parse("example.org"),5)
    @Before fun clean() { context.getSharedPreferences("cooking_timers",0).edit().clear().commit() }
    @Test fun elapsedDeadlineIgnoresWallClockChangesAndRebootUsesSavedWallDeadline() {
        val timer=CookingTimer("test",account.value,1,"Step 1",100_000,60_000,7)
        assertEquals(50_000L,timer.remaining(999_999,10_000,7))
        assertEquals(30_000L,timer.remaining(70_000,1_000,8))
        assertEquals(0L,timer.remaining(120_000,1_000,8))
        assertEquals(timer,CookingTimer.parse(timer.json()))
    }
    @Test fun timersAndCookingProgressSurviveRepositoryRecreationAndStayAccountScoped() {
        val store=CookingTimerStore(context)
        val timer=store.add(account,3,"Step 2",5)
        store.saveSession(account,CookingSession(3,1,listOf(7,8)))
        val reopened=CookingTimerStore(context)
        assertEquals(timer,reopened.list(account).single())
        assertEquals(CookingSession(3,1,listOf(7,8)),reopened.session(account))
        assertTrue(reopened.list(other).isEmpty());assertNull(reopened.session(other))
    }
    @Test fun dismissAndLogoutCancelSavedTimersAndAccountSwitchReschedulesOnlyActiveTimers() {
        val store=CookingTimerStore(context)
        store.add(account,3,"Step 1",1);store.add(other,4,"Step 1",2)
        store.accountChanged(account)
        assertEquals(1,shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.size)
        store.accountChanged(null)
        assertEquals(0,shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.size)
        store.removeAccount(account)
        assertTrue(store.list(account).isEmpty());assertEquals(1,store.list(other).size)
    }
    @Test fun dueAlarmCanNotifyOnlyOnceAndNeverForAnotherAccount() {
        val store=CookingTimerStore(context);val timer=store.add(account,3,"Step 1",1)
        var posts=0
        assertFalse(store.deliver(timer.id,account) { posts++;true })
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        assertFalse(store.deliver(timer.id,other) { posts++;true })
        assertFalse(store.deliver(timer.id,account) { false })
        assertTrue(store.deliver(timer.id,account) { posts++;true })
        assertFalse(CookingTimerStore(context).deliver(timer.id,account) { posts++;true })
        assertEquals(1,posts)
    }
    @Test fun invalidDurationsAreRejectedWithoutPersistingTimers() {
        val store=CookingTimerStore(context)
        listOf(0L,-1L,1441L).forEach { minutes -> assertTrue(runCatching { store.add(account,1,"Step",minutes) }.isFailure) }
        assertTrue(store.list(account).isEmpty())
    }
}
