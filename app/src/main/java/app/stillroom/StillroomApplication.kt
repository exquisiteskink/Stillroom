package app.stillroom

import android.app.Application
import android.provider.Settings
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.WindowRecomposerFactory
import androidx.compose.ui.platform.WindowRecomposerPolicy
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import app.stillroom.data.AndroidShellPreferencesRepository
import app.stillroom.domain.ManageShellPreferences
import app.stillroom.data.AndroidAccountsRepository

class StillroomApplication : Application() {
    val accounts by lazy { AndroidAccountsRepository(applicationContext) }
    // Keep this version-sensitive hook isolated; Compose is pinned by the app's BOM.
    @OptIn(InternalComposeUiApi::class)
    override fun onCreate() {
        super.onCreate()
        app.stillroom.background.HouseholdWork.schedule(this)
        // Use Compose's window factory so lifecycle management and test clocks remain supported.
        WindowRecomposerPolicy.setFactory(WindowRecomposerFactory { view ->
            val preferences = ManageShellPreferences(AndroidShellPreferencesRepository(view.context))
            val motionScale = object : MotionDurationScale {
                override val scaleFactor: Float
                    get() = if (preferences.load().reducedMotion) 0f else Settings.Global.getFloat(
                        contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
                    )
            }
            view.createLifecycleAwareWindowRecomposer(motionScale)
        })
    }
}
