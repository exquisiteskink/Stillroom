package app.stillroom.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.RestaurantMenu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShoppingBasket
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.stillroom.background.HouseholdWork
import app.stillroom.background.ReminderPreferences
import app.stillroom.domain.Section
import app.stillroom.domain.ThemeChoice
import app.stillroom.fractions.QuantityFormatter
import app.stillroom.fractions.QuantityStyle
import java.time.LocalTime

private fun Section.icon(): ImageVector = when (this) {
    Section.Today -> Icons.Outlined.Today
    Section.Pantry -> Icons.Outlined.Kitchen
    Section.Shop -> Icons.Outlined.ShoppingBasket
    Section.Meals -> Icons.Outlined.RestaurantMenu
    Section.Household -> Icons.Outlined.Home
}

@Composable
fun StillroomShell(state: ShellUiState, model: ShellViewModel, accounts: AccountUiState = AccountUiState(), accountModel: AccountViewModel? = null, stockModel: StockViewModel? = null, shoppingModel: ShoppingViewModel? = null, householdModel: HouseholdViewModel? = null, scannerModel: ScannerViewModel? = null, recipeModel: RecipeViewModel? = null, catalogModel: CatalogViewModel? = null, todayModel: TodayViewModel? = null, pendingChanges: @Composable () -> Unit = {}) {
    val active = accounts.accounts.active
    LaunchedEffect(active?.id) { model.accountChanged(active?.id?.value, active?.restricted == true) }
    val navigationState = if (active?.restricted == true) state.copy(preferences = state.preferences.copy(visibleSections = setOf(Section.Today, Section.Household))) else state
    BackHandler(enabled = state.page != ShellPage.Sections) { model.back() }
    val quantities = remember(state.preferences.quantityStyle) { QuantityFormatter(state.preferences.quantityStyle) }
    androidx.compose.runtime.CompositionLocalProvider(LocalQuantityFormatter provides quantities) {
    Surface(modifier = Modifier.fillMaxSize().testTag("shell"), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val tablet = maxWidth >= 600.dp
            Row(Modifier.fillMaxSize()) {
                if (tablet) SectionSidebar(navigationState, model)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    ShellToolbar(state, model, active?.restricted == true, accountModel != null)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Box(Modifier.weight(1f).fillMaxWidth().imePadding()) {
                        androidx.compose.runtime.key(active?.id) {
                        ShellContent(state, model, accounts, accountModel, stockModel, shoppingModel, householdModel, scannerModel, recipeModel, catalogModel, todayModel, pendingChanges)
                        }
                    }
                    if (!tablet) SectionBottomBar(navigationState, model)
                }
            }
        }
    }
    }
}

@Composable
private fun ShellToolbar(state: ShellUiState, model: ShellViewModel, restricted: Boolean, hasAccounts: Boolean) {
    val backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val title = when (state.page) {
        ShellPage.Sections -> state.selectedSection.name
        ShellPage.Search -> "Search"
        ShellPage.Scanner -> "Scan products"
        ShellPage.Settings -> "Settings"
        ShellPage.Accounts -> "Accounts"
        ShellPage.PendingChanges -> "Pending changes"
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.page != ShellPage.Sections) {
            IconButton(onClick = { backDispatcher?.onBackPressed() ?: model.back() }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
            }
        }
        Text(
            title,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp).testTag("page-title").semantics { heading() },
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (state.page == ShellPage.Sections) {
            if (!restricted) {
            IconButton(onClick = { model.openPage(ShellPage.Search) }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Search, contentDescription = "Open search")
            }
            QuietButton(onClick = { model.openPage(ShellPage.Scanner) }, modifier = Modifier.sizeIn(minHeight = 48.dp).semantics { contentDescription = "Open scanner" }) {
                Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Scan")
            }
            }
            IconButton(onClick = { model.openPage(ShellPage.Settings) }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Settings, contentDescription = "Open settings")
            }
        }
        if (hasAccounts && state.page != ShellPage.Accounts) {
            IconButton(onClick = { model.openPage(ShellPage.Accounts) }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.AccountCircle, contentDescription = "Open accounts")
            }
        }
    }
}

@Composable
private fun SectionSidebar(state: ShellUiState, model: ShellViewModel) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier.width(240.dp).fillMaxHeight().verticalScroll(rememberScrollState())
                .testTag("navigation-sidebar").padding(16.dp).selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Sections", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp).semantics { heading() })
            Section.entries.filter { it in state.preferences.visibleSections }.forEach { section ->
                NavigationDrawerItem(
                    label = { Text(section.name) },
                    selected = state.selectedSection == section && state.page == ShellPage.Sections,
                    onClick = { model.selectSection(section) },
                    icon = { Icon(section.icon(), contentDescription = null) },
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier.sizeIn(minHeight = 48.dp).semantics {
                        contentDescription = "Go to ${section.name}"
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionBottomBar(state: ShellUiState, model: ShellViewModel) {
    val sections = Section.entries.filter { it in state.preferences.visibleSections }
    if (LocalDensity.current.fontScale >= 1.4f) {
        // Preserve readable labels and hit targets rather than squeezing enlarged text into five slots.
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp)
                .selectableGroup().testTag("navigation-scroll"),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sections.forEach { section ->
                    FilterChip(
                        selected = state.selectedSection == section && state.page == ShellPage.Sections,
                        onClick = { model.selectSection(section) },
                        label = { Text(section.name) },
                        leadingIcon = { Icon(section.icon(), contentDescription = null) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 56.dp).semantics {
                            contentDescription = "Go to ${section.name}"
                            role = Role.Tab
                        },
                    )
                }
            }
        }
    } else {
        NavigationBar(
            modifier = Modifier.testTag("navigation-bottom"),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            windowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        ) {
            sections.forEach { section ->
                NavigationBarItem(
                    selected = state.selectedSection == section && state.page == ShellPage.Sections,
                    onClick = { model.selectSection(section) },
                    icon = { Icon(section.icon(), contentDescription = null) },
                    label = { Text(section.name) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                        contentDescription = "Go to ${section.name}"
                    },
                )
            }
        }
    }
}

@Composable
private fun ShellContent(state: ShellUiState, model: ShellViewModel, accounts: AccountUiState, accountModel: AccountViewModel?, stockModel: StockViewModel?, shoppingModel: ShoppingViewModel?, householdModel: HouseholdViewModel?, scannerModel: ScannerViewModel?, recipeModel: RecipeViewModel?, catalogModel: CatalogViewModel?, todayModel: TodayViewModel?, pendingChanges: @Composable () -> Unit) {
    val pane = @Composable {
        when (state.page) {
            ShellPage.PendingChanges -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { pendingChanges() }
            ShellPage.Settings -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).widthIn(max = 720.dp)) {
                ShellSettings(state, model, accounts.accounts.active?.restricted == true)
            }
            ShellPage.Accounts -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).widthIn(max = 720.dp)) {
                if (accountModel != null) AccountScreen(accounts, accountModel)
            }
            ShellPage.Search -> {
                if (accounts.accounts.active == null || stockModel == null) {
                    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        LabeledTextField(
                            value = state.query, onValueChange = model::setQuery,
                            label = "Search products",
                            modifier = Modifier.fillMaxWidth().testTag("search-entry"),
                            singleLine = true,
                        )
                        if (accounts.accounts.active == null) EmptyState(onConnect = { model.openPage(ShellPage.Accounts) })
                    }
                } else StockScreen(stockModel, accounts.accounts.active.permissions, searchAll = true)
            }
            ShellPage.Scanner -> if (accounts.accounts.active == null) EmptyState(message = "$DisconnectedMessage Scanning will be available after connection.", onConnect = { model.openPage(ShellPage.Accounts) })
                else if (stockModel != null && scannerModel != null) androidx.compose.runtime.key(accounts.accounts.active.id) { ScannerScreen(scannerModel, stockModel, accounts.accounts.active, onReviewChanges = { model.openPage(ShellPage.PendingChanges) }, onExit = model::back) } else if (state.selectedSection == Section.Household && householdModel != null) androidx.compose.runtime.key(accounts.accounts.active.id) { HouseholdHub(householdModel, catalogModel, accounts.accounts.active) } else ConnectedPlaceholder(accounts.accounts.active)
            ShellPage.Sections -> if (accounts.accounts.active == null) EmptyState(onConnect = { model.openPage(ShellPage.Accounts) }) else if (state.selectedSection == Section.Today && todayModel != null) androidx.compose.runtime.key(accounts.accounts.active.id) { TodayScreen(todayModel, accounts.accounts.active, model::selectSection) } else if (state.selectedSection == Section.Meals && recipeModel != null) androidx.compose.runtime.key(accounts.accounts.active.id) { RecipeScreen(recipeModel, accounts.accounts.active) } else if (state.selectedSection == Section.Shop && shoppingModel != null) androidx.compose.runtime.key(accounts.accounts.active.id) { ShoppingScreen(shoppingModel, accounts.accounts.active.permissions) } else if (state.selectedSection == Section.Pantry && stockModel != null) StockScreen(stockModel, accounts.accounts.active.permissions) else if (state.selectedSection == Section.Household && householdModel != null) androidx.compose.runtime.key(accounts.accounts.active.id) { HouseholdHub(householdModel, catalogModel, accounts.accounts.active) } else ConnectedPlaceholder(accounts.accounts.active)
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) { pane() }
    }
}

@Composable
private fun SettingToggle(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    supportingText: String? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 64.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = title }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (supportingText != null) Text(supportingText, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun ShellSettings(state: ShellUiState, model: ShellViewModel, restricted: Boolean) {
    QuietButton(onClick = { model.openPage(ShellPage.PendingChanges) }) { Text("Review pending changes") }
    QuietButton(onClick = { model.openPage(ShellPage.Accounts) }) { Text("Manage accounts") }
    val prefs = state.preferences
    Text("Visible sections", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    Text("Choose the sections shown in navigation. Keep at least one visible.", style = MaterialTheme.typography.bodyLarge)
    Section.entries.forEach { section ->
        val checked = section in prefs.visibleSections
        SettingToggle(
            title = "Show ${section.name}", checked = checked,
            enabled = !(checked && prefs.visibleSections.size == 1) && !((prefs.childLanding || restricted) && section == Section.Household),
            supportingText = if ((prefs.childLanding || restricted) && section == Section.Household) "Required while Household opens first." else null,
            onChange = { model.setSectionVisible(section, it) },
        )
    }
    HorizontalDivider()
    SettingToggle("Open Household first", prefs.childLanding,
        supportingText = "Open Household first. This preference does not restrict account access.",
        onChange = model::setChildLanding)
    HorizontalDivider()
    KitchenReminderSettings()
    HorizontalDivider()
    Text("Appearance", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    Column(Modifier.selectableGroup()) {
        ThemeChoice.entries.forEach { theme ->
            Row(Modifier.fillMaxWidth().sizeIn(minHeight = 56.dp)
                .selectable(selected = prefs.theme == theme, role = Role.RadioButton, onClick = { model.setTheme(theme) })
                .semantics { contentDescription = "${theme.name} theme" }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = prefs.theme == theme, onClick = null)
                Text(if (theme == ThemeChoice.System) "Follow system" else theme.name,
                    style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
    SettingToggle("Dynamic color", prefs.dynamicColor, enabled = Build.VERSION.SDK_INT >= 31,
        supportingText = if (Build.VERSION.SDK_INT >= 31) "Use wallpaper colors." else "Available on Android 12 and later.",
        onChange = model::setDynamicColor)
    SettingToggle("Reduced motion", prefs.reducedMotion,
        supportingText = "Remove animations and use a static loading message. System animation settings are also respected.",
        onChange = model::setReducedMotion)
    Text("Show quantities as", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
    Text("Changes only how amounts look. Grocy keeps the exact values, and fields accept 1 1/2, 1½ or 1.5.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column(Modifier.selectableGroup().testTag("quantity-style")) {
        listOf(QuantityStyle.Fractions to "Fractions (1½, ⅓)", QuantityStyle.Decimals to "Decimals (1.5, 0.333)").forEach { (style, label) ->
            Row(Modifier.fillMaxWidth().sizeIn(minHeight = 56.dp)
                .selectable(selected = prefs.quantityStyle == style, role = Role.RadioButton, onClick = { model.setQuantityStyle(style) })
                .semantics { contentDescription = "Show quantities as ${style.name.lowercase()}" }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = prefs.quantityStyle == style, onClick = null)
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

@Composable
private fun KitchenReminderSettings() {
    val context = LocalContext.current
    val prefs = remember { ReminderPreferences(context) }
    var enabled by remember { mutableStateOf(prefs.enabled) }
    var start by remember { mutableStateOf(prefs.from) }
    var end by remember { mutableStateOf(prefs.until) }
    var message by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        message = if (granted) "Reminders are allowed on this phone." else "Turn on notifications in Android settings to hear kitchen reminders."
    }
    val startValid = runCatching { LocalTime.parse(start) }.isSuccess
    val endValid = runCatching { LocalTime.parse(end) }.isSuccess
    val quietHoursValid = startValid && endValid
    Text("Kitchen reminders", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    Text(
        "Check due chores and food to use once a day, outside quiet hours.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SettingToggle("Send reminders", enabled, onChange = { enabled = it })
    LabeledTextField(start, { start = it }, label = "Quiet hours start (HH:mm)", isError = !startValid, supportingText = if (!startValid) "Enter a time as HH:mm." else null, modifier = Modifier.fillMaxWidth())
    LabeledTextField(end, { end = it }, label = "Quiet hours end (HH:mm)", isError = !endValid, supportingText = if (!endValid) "Enter a time as HH:mm." else null, modifier = Modifier.fillMaxWidth())
    PrimaryButton(
        onClick = {
            prefs.save(enabled, start, end)
            HouseholdWork.schedule(context)
            message = "Reminder settings saved."
            if (enabled && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
        enabled = quietHoursValid,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Save reminders") }
    message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}
