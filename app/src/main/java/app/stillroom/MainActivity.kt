package app.stillroom

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import app.stillroom.data.AndroidShellPreferencesRepository
import app.stillroom.domain.ThemeChoice
import app.stillroom.ui.ShellViewModel
import app.stillroom.ui.StillroomShell
import app.stillroom.ui.StillroomTheme
import app.stillroom.ui.AccountViewModel
import app.stillroom.domain.ManageAccounts

class MainActivity : ComponentActivity() {
    private val model: ShellViewModel by viewModels {
        ShellViewModel.factory(AndroidShellPreferencesRepository(applicationContext))
    }
    private val accounts: AccountViewModel by viewModels {
        AccountViewModel.factory(ManageAccounts((application as StillroomApplication).accounts))
    }

    private val stock: app.stillroom.ui.StockViewModel by viewModels {
        object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return app.stillroom.ui.StockViewModel((application as StillroomApplication).accounts, app.stillroom.data.AndroidStockDetailsStore(application)) as T
            }
        }
    }

    private val shopping: app.stillroom.ui.ShoppingViewModel by viewModels {
        object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return app.stillroom.ui.ShoppingViewModel((application as StillroomApplication).accounts) as T
            }
        }
    }

    private val household: app.stillroom.ui.HouseholdViewModel by viewModels {
        object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return app.stillroom.ui.HouseholdViewModel((application as StillroomApplication).accounts) as T
            }
        }
    }

    private val scanner: app.stillroom.ui.ScannerViewModel by viewModels {
        object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return app.stillroom.ui.ScannerViewModel((application as StillroomApplication).accounts) as T
            }
        }
    }

    private val recipes: app.stillroom.ui.RecipeViewModel by viewModels {
        object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return app.stillroom.ui.RecipeViewModel((application as StillroomApplication).accounts) as T
            }
        }
    }
    private val catalog: app.stillroom.ui.CatalogViewModel by viewModels {
        object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return app.stillroom.ui.CatalogViewModel((application as StillroomApplication).accounts) as T
            }
        }
    }
    private val addons: app.stillroom.ui.AddonViewModel by viewModels {
        object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return app.stillroom.ui.AddonViewModel((application as StillroomApplication).accounts) as T
            }
        }
    }
    private val today: app.stillroom.ui.TodayViewModel by viewModels {
        object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return app.stillroom.ui.TodayViewModel((application as StillroomApplication).accounts) as T
            }
        }
    }
    private var pendingDestination by androidx.compose.runtime.mutableStateOf<String?>(null)
    private var pendingCooking:Pair<String,Long>?=null
    private fun receiveRecipe(intent: android.content.Intent?) {
        intent?.getStringExtra("stillroom_destination")?.takeIf { it in setOf("chores","shopping","scan","today","tasks") }?.let { pendingDestination=it }
        if(intent?.getStringExtra("stillroom_destination")=="cooking") {
            val id=intent.getLongExtra("stillroom_recipe_id",0)
            val owner=intent.getStringExtra("stillroom_cooking_account")
            if(id>0 && owner!=null) { pendingCooking=owner to id;pendingDestination="cooking" }
        }
        if(intent?.action==android.content.Intent.ACTION_SEND && intent.type=="text/plain") {
            intent.getStringExtra(android.content.Intent.EXTRA_TEXT)?.takeIf { it.length<=4096 }?.trim()?.let { text ->
                val url=Regex("https?://[^\\s]+").find(text)?.value ?: return
                recipes.share(url);pendingDestination="meals"
            }
        }
    }
    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent);setIntent(intent);receiveRecipe(intent) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if(savedInstanceState==null)receiveRecipe(intent)
        enableEdgeToEdge()
        setContent {
            val state by model.state.collectAsState()
            val accountState by accounts.state.collectAsState()
            LaunchedEffect(accountState.accounts.active?.id,pendingDestination) {
                val active=accountState.accounts.active
                val target=pendingDestination
                if(active!=null && target!=null) {
                    model.accountChanged(active.id.value,active.restricted)
                    val section=when(target) {
                        "chores"->app.stillroom.domain.Section.Household
                        "shopping"->app.stillroom.domain.Section.Shop
                        "meals"->app.stillroom.domain.Section.Meals
                        else->app.stillroom.domain.Section.Today
                    }
                    if(target=="cooking") {
                        pendingCooking?.takeIf { it.first==active.id.value && app.stillroom.domain.RecipeAccess.allowed(active.permissions) }?.let { recipes.openCooking(it.second);model.setSectionVisible(app.stillroom.domain.Section.Meals,true);model.selectSection(app.stillroom.domain.Section.Meals) }
                        pendingCooking=null
                    }
                    else if(target=="scan")model.openPage(app.stillroom.ui.ShellPage.Scanner)
                    else { model.setSectionVisible(section,true);model.selectSection(section) }
                    pendingDestination=null
                }
            }
            val settings = state.preferences
            val dark = when (settings.theme) {
                ThemeChoice.System -> isSystemInDarkTheme()
                ThemeChoice.Light -> false
                ThemeChoice.Dark -> true
            }
            SideEffect {
                val bars = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            val systemReducedMotion = Settings.Global.getFloat(
                contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            ) == 0f
            StillroomTheme(settings.theme, settings.dynamicColor, settings.reducedMotion || systemReducedMotion) {
                StillroomShell(state, model, accountState, accounts, stock, shopping, household, scanner, recipes, catalog, today, addons) {
                    app.stillroom.ui.PendingChangesScreen((application as StillroomApplication).accounts)
                }
            }
        }
    }
}
