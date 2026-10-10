package app.stillroom.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.stillroom.background.CookingTimerStore
import app.stillroom.domain.Account
import kotlinx.coroutines.delay

@Composable internal fun CookingTimerPanel(account:Account,recipe:Long,step:Int) {
    val context=LocalContext.current
    val store=remember(context) { CookingTimerStore(context) }
    var minutes by remember { mutableStateOf("5") }
    var timers by remember(account.id,recipe) { mutableStateOf(store.list(account.id,recipe)) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableLongStateOf(0L) }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(!granted)error="Notifications are off. Finished timers remain visible here."
    }
    LaunchedEffect(account.id,recipe) { while(true) { timers=store.list(account.id,recipe);tick++;delay(1000) } }
    Text("Cooking timers",style=MaterialTheme.typography.titleMedium)
    Text("Timers keep running when you leave this screen.")
    if(!store.exactAvailable()) {
        Text("Android may delay timer notifications. Allow precise alarms for timely cooking alerts.")
        QuietButton(onClick={context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,android.net.Uri.parse("package:${context.packageName}")))}) { Text("Allow precise timers") }
    }
    val valid=minutes.toLongOrNull()?.let { it in 1..1440 }==true
    LabeledTextField(minutes,{minutes=it},"Timer minutes",isError=!valid,supportingText="Enter 1 to 1440 minutes.")
    SecondaryButton(onClick={
        try { store.add(account.id,recipe,"Step ${step+1}",minutes.toLong());timers=store.list(account.id,recipe);error=null
            if(Build.VERSION.SDK_INT>=33)permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }catch(e:Exception){error=e.message ?: "Could not start the timer."}
    },enabled=valid) { Text("Start timer for step ${step+1}") }
    error?.let { KitchenError(it) }
    // tick invalidates remaining time without rescheduling an alarm every second.
    tick.let { timers.forEach { timer ->
        val seconds=(store.remaining(timer)+999)/1000
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("${timer.label}: ${if(seconds==0L)"Done" else "${seconds/60}:${(seconds%60).toString().padStart(2,'0')}"}",Modifier.weight(1f))
            QuietButton(onClick={store.dismiss(account.id,timer.id);timers=store.list(account.id,recipe)}) { Text("Dismiss") }
        }
    } }
}
