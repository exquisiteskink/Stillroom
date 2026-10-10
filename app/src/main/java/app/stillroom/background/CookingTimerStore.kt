package app.stillroom.background

import android.app.*
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import app.stillroom.domain.*
import kotlinx.serialization.json.*
import java.util.UUID

/** Account-scoped local cooking aids. No Grocy calls or stock writes. */
class CookingTimerStore(private val context:Context) {
    private val prefs=context.getSharedPreferences("cooking_timers",Context.MODE_PRIVATE)
    private val alarms get()=context.getSystemService(AlarmManager::class.java)
    private val boot get()=Settings.Global.getInt(context.contentResolver,Settings.Global.BOOT_COUNT,0)
    fun all():List<CookingTimer> = synchronized(lock) {
        runCatching { Json.parseToJsonElement(prefs.getString("timers","[]")!!).jsonArray.map { CookingTimer.parse(it.jsonObject) } }.getOrDefault(emptyList())
    }
    fun list(account:AccountId,recipe:Long?=null)=all().filter { it.account==account.value && (recipe==null || it.recipe==recipe) }
    fun remaining(timer:CookingTimer)=timer.remaining(System.currentTimeMillis(),SystemClock.elapsedRealtime(),boot)
    fun exactAvailable()=Build.VERSION.SDK_INT<31 || alarms.canScheduleExactAlarms()
    private fun write(rows:List<CookingTimer>) { check(prefs.edit().putString("timers",JsonArray(rows.map { it.json() }).toString()).commit()) }
    fun add(account:AccountId,recipe:Long,label:String,minutes:Long):CookingTimer = synchronized(lock) {
        require(recipe>0 && minutes in 1..1440 && label.length<=200)
        val rows=all();check(rows.count { it.account==account.value }<20) { "Dismiss a timer before adding another." }
        val timer=CookingTimer(UUID.randomUUID().toString(),account.value,recipe,label,System.currentTimeMillis()+minutes*60_000,SystemClock.elapsedRealtime()+minutes*60_000,boot)
        write(rows+timer);schedule(timer);timer
    }
    private fun intent(timer:CookingTimer)=PendingIntent.getBroadcast(context,0,
        Intent(context,CookingTimerReceiver::class.java).setAction("app.stillroom.COOKING_TIMER")
            .setData(Uri.parse("stillroom-timer://${timer.id}")),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun schedule(timer:CookingTimer) {
        if(timer.notified)return
        val due=SystemClock.elapsedRealtime()+remaining(timer).coerceAtLeast(1000)
        val pending=intent(timer)
        if(exactAvailable())try { alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,due,pending);return }catch(_:SecurityException) { }
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,due,pending)
    }
    fun accountChanged(account:AccountId?) = synchronized(lock) {
        all().forEach { timer ->
            alarms.cancel(intent(timer));context.getSystemService(NotificationManager::class.java).cancel(timer.id,701)
            if(timer.account==account?.value)schedule(timer)
        }
    }
    fun dismiss(account:AccountId,id:String) = synchronized(lock) {
        val timer=all().firstOrNull { it.id==id && it.account==account.value } ?: return@synchronized
        alarms.cancel(intent(timer));context.getSystemService(NotificationManager::class.java).cancel(timer.id,701)
        write(all().filterNot { it.id==id })
    }
    fun removeAccount(account:AccountId)=synchronized(lock) {
        list(account).forEach { dismiss(account,it.id) }
        check(prefs.edit().remove("session-${account.value}").commit())
    }
    fun session(account:AccountId):CookingSession?=synchronized(lock) {
        prefs.getString("session-${account.value}",null)?.let { runCatching { CookingSession.parse(Json.parseToJsonElement(it).jsonObject) }.getOrNull() }
    }
    fun saveSession(account:AccountId,session:CookingSession)=synchronized(lock) {
        require(session.recipe>0 && session.step>=0)
        check(prefs.edit().putString("session-${account.value}",session.json().toString()).commit())
    }
    fun finishSession(account:AccountId)=synchronized(lock) { check(prefs.edit().remove("session-${account.value}").commit()) }
    /** Run notification publication while locked: dismiss and duplicate alarms cannot race it. */
    fun deliver(id:String,account:AccountId,post:(CookingTimer)->Boolean):Boolean=synchronized(lock) {
        val timer=all().firstOrNull { it.id==id && it.account==account.value && !it.notified } ?: return@synchronized false
        if(remaining(timer)>0) { schedule(timer);return@synchronized false }
        if(!post(timer))return@synchronized false
        write(all().map { if(it.id==id)it.copy(notified=true) else it });true
    }
    companion object { private val lock=Any() }
}
