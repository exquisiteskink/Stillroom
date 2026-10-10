package app.stillroom.background

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.stillroom.MainActivity
import app.stillroom.StillroomApplication
import app.stillroom.domain.*
import kotlinx.coroutines.*

class CookingTimerReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val pending=goAsync()
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            try {
                val accounts=(context.applicationContext as StillroomApplication).accounts
                val store=CookingTimerStore(context)
                val snapshot=accounts.cachedToday()
                accounts.publishToday(snapshot) {
                    val account=snapshot.account
                    if(intent.action!="app.stillroom.COOKING_TIMER")store.accountChanged(account?.id)
                    else if(account!=null && !snapshot.accessDenied && RecipeAccess.allowed(account.permissions)) {
                        val id=intent.data?.host ?: return@publishToday
                        store.deliver(id,account.id) { timer -> post(context,timer) }
                    }
                }
            } catch(_:Exception) { /* Saved deadlines remain available on the next foreground visit. */ }
            finally { pending.finish() }
        }
    }
    private fun post(context:Context,timer:CookingTimer):Boolean {
        if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return false
        val manager=context.getSystemService(NotificationManager::class.java)
        if(!manager.areNotificationsEnabled())return false
        manager.createNotificationChannel(NotificationChannel("cooking-timers","Cooking timers",NotificationManager.IMPORTANCE_HIGH))
        val click=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java)
            .setData(android.net.Uri.parse("stillroom-cook://${timer.id}"))
            .putExtra("stillroom_destination","cooking").putExtra("stillroom_recipe_id",timer.recipe)
            .putExtra("stillroom_cooking_account",timer.account),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(timer.id,701,NotificationCompat.Builder(context,"cooking-timers")
            .setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle("Cooking timer finished")
            .setContentText(timer.label).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(click).setAutoCancel(true).build())
        return true
    }
}
