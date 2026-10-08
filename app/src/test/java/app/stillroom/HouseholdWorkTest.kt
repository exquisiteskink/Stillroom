package app.stillroom

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.*
import app.stillroom.background.*
import app.stillroom.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=android.app.Application::class)
class HouseholdWorkTest {
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    @Before fun setup() {
        context.getSharedPreferences("household_reminders",Context.MODE_PRIVATE).edit().clear().commit()
        context.getSystemService(NotificationManager::class.java).cancelAll()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,Configuration.Builder().setExecutor(androidx.work.testing.SynchronousExecutor()).build())
    }
    @After fun close() { WorkManager.getInstance(context).cancelAllWork() }
    private fun due():TodaySnapshot {
        val address=ServerAddress.parse("example.org");val account=Account(AccountId.of(address,4),address,4,"child","4.7.1",setOf("CHORES"))
        return TodaySnapshot(account=account,chores=listOf(buildJsonObject { put("chore_id",1);put("chore_name","My cached chore") }))
    }
    private fun worker(snapshot:TodaySnapshot,widgetsOnly:Boolean=false):HouseholdWorker {
        val w=TestListenableWorkerBuilder<HouseholdWorker>(context).setInputData(workDataOf("widgets_only" to widgetsOnly)).build()
        w.time={java.time.LocalTime.NOON};w.readSnapshot={snapshot};w.publishSnapshot={_,publish->publish();true};return w
    }
    @Test fun quietHoursAndWidgetsOnlyNeverNotifyOrMarkDelivered()=runBlocking {
        val prefs=ReminderPreferences(context);prefs.save(true,"00:00","00:00")
        assertEquals(ListenableWorker.Result.success(),worker(due()).doWork())
        assertEquals(0,context.getSystemService(NotificationManager::class.java).activeNotifications.size)
        assertFalse(prefs.delivered(due().account!!.id.value,LocalDate.now().toString()))
        prefs.save(true,"23:59","00:00")
        assertEquals(ListenableWorker.Result.success(),worker(due(),widgetsOnly=true).doWork())
        assertEquals(0,context.getSystemService(NotificationManager::class.java).activeNotifications.size)
    }
    @Test fun cachedReminderIsOncePerDayAndSupersededSnapshotCannotPublish()=runBlocking {
        val prefs=ReminderPreferences(context);prefs.save(true,"23:59","00:00")
        val snapshot=due();val w=worker(snapshot)
        assertEquals(ListenableWorker.Result.success(),w.doWork())
        assertEquals(1,context.getSystemService(NotificationManager::class.java).activeNotifications.size)
        assertTrue(prefs.delivered(snapshot.account!!.id.value,LocalDate.now().toString()))
        assertEquals(ListenableWorker.Result.success(),worker(snapshot).doWork())
        assertEquals(1,context.getSystemService(NotificationManager::class.java).activeNotifications.size)
        context.getSystemService(NotificationManager::class.java).cancelAll()
        val old=worker(snapshot);old.publishSnapshot={_,_->false};old.doWork()
        assertEquals(0,context.getSystemService(NotificationManager::class.java).activeNotifications.size)
        worker(snapshot.copy(accessDenied=true)).doWork();assertEquals(0,context.getSystemService(NotificationManager::class.java).activeNotifications.size)
    }
    @Test fun schedulingHasOnePeriodicJobAndPreferencesValidate() {
        HouseholdWork.schedule(context);HouseholdWork.schedule(context)
        val work=WorkManager.getInstance(context).getWorkInfosForUniqueWork("stillroom-household-cache").get(5,TimeUnit.SECONDS)
        assertEquals(1,work.size)
        assertTrue(runCatching { ReminderPreferences(context).save(true,"bad","07:00") }.isFailure)
        HouseholdWork.updateSoon(context);HouseholdWork.accountChanged(context)
    }
}
