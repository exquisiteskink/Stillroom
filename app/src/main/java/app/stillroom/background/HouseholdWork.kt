package app.stillroom.background

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import app.stillroom.MainActivity
import app.stillroom.StillroomApplication
import app.stillroom.domain.*
import java.time.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class ReminderPreferences(context:Context) {
    private val prefs=context.getSharedPreferences("household_reminders",Context.MODE_PRIVATE)
    val enabled get()=prefs.getBoolean("enabled",false)
    val from get()=prefs.getString("from","22:00")!!
    val until get()=prefs.getString("until","07:00")!!
    fun quiet(now:LocalTime)=QuietHours(LocalTime.parse(from),LocalTime.parse(until)).contains(now)
    fun save(enabled:Boolean,from:String,until:String) { LocalTime.parse(from);LocalTime.parse(until);check(prefs.edit().putBoolean("enabled",enabled).putString("from",from).putString("until",until).commit()) }
    fun delivered(account:String,day:String)=prefs.getString("last-$account",null)==day
    fun mark(account:String,day:String) { check(prefs.edit().putString("last-$account",day).commit()) }
}
object HouseholdWork {
    fun schedule(context:Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("stillroom-household-cache",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<HouseholdWorker>(15,TimeUnit.MINUTES).build())
    }
    fun updateSoon(context:Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("stillroom-household-widget-update",ExistingWorkPolicy.REPLACE,OneTimeWorkRequestBuilder<HouseholdWorker>().setInputData(workDataOf("widgets_only" to true)).build())
    }
    fun accountChanged(context:Context) {
        WidgetRenderer.update(context,TodaySnapshot())
        context.getSystemService(NotificationManager::class.java).cancel(501)
        updateSoon(context)
    }
}
class HouseholdWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    internal var time:()->LocalTime={ LocalTime.now() }
    internal var readSnapshot:suspend()->TodaySnapshot={ (applicationContext as StillroomApplication).accounts.cachedToday() }
    internal var publishSnapshot:(TodaySnapshot,()->Unit)->Boolean={ snapshot,block->(applicationContext as StillroomApplication).accounts.publishToday(snapshot,block) }
    override suspend fun doWork():Result {
        return try {
            val prefs=ReminderPreferences(applicationContext)
            val widgetsOnly=inputData.getBoolean("widgets_only",false)
            // No networking or stock/chore mutations in background work.
            val snapshot=readSnapshot()
            publishSnapshot(snapshot) {
                WidgetRenderer.update(applicationContext,snapshot)
                if(!widgetsOnly && prefs.enabled && !prefs.quiet(time()) && !snapshot.accessDenied)deliver(snapshot,prefs)
            }
            Result.success() // A superseded account's work has nothing to publish.
        }catch(e:CancellationException) { throw e }catch(_:Exception) { Result.retry() }
    }
    private fun deliver(snapshot:TodaySnapshot,prefs:ReminderPreferences) {
        val account=snapshot.account ?: return
        val day=LocalDate.now().toString()
        if(prefs.delivered(account.id.value,day))return
        val dueTasks=duePersonalTasks(snapshot,LocalDate.now())
        if(snapshot.chores.isEmpty() && snapshot.expiring.isEmpty() && dueTasks.isEmpty())return
        if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(applicationContext,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        val manager=applicationContext.getSystemService(NotificationManager::class.java)
        if(!manager.areNotificationsEnabled())return
        manager.createNotificationChannel(NotificationChannel("household-due","Household reminders",NotificationManager.IMPORTANCE_DEFAULT))
        val intent=Intent(applicationContext,MainActivity::class.java).putExtra("stillroom_destination","today")
        val click=PendingIntent.getActivity(applicationContext,501,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(applicationContext,"household-due").setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Stillroom · ${account.username}").setContentText("Cached: ${dueTasks.size} due tasks, ${snapshot.chores.size} due chores, ${snapshot.expiring.size} foods due or expired.")
            .setContentIntent(click).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        manager.notify(501,notification);prefs.mark(account.id.value,day)
    }
}
