package app.stillroom.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.stillroom.data.LocalAppInfoRepository
import app.stillroom.domain.GetAppName
import app.stillroom.domain.ManageShellPreferences
import app.stillroom.domain.Section
import app.stillroom.domain.ShellPreferences
import app.stillroom.domain.ShellPreferencesRepository
import app.stillroom.domain.ThemeChoice
import app.stillroom.fractions.QuantityStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ShellPage { Sections, Search, Scanner, Settings, Accounts, PendingChanges }

data class ShellUiState(
    val preferences: ShellPreferences,
    val selectedSection: Section = preferences.landingSection(),
    val page: ShellPage = ShellPage.Sections,
    val query: String = "",
    val connectedAccountId: String? = null,
)

class ShellViewModel(
    getAppName: GetAppName,
    private val settings: ManageShellPreferences,
) : ViewModel() {
    val appName: String = getAppName()
    private val mutableState = MutableStateFlow(ShellUiState(settings.load()))
    val state: StateFlow<ShellUiState> = mutableState.asStateFlow()
    private val previousPages = ArrayDeque<ShellPage>()

    fun selectSection(section: Section) {
        if (section in state.value.preferences.visibleSections) {
            previousPages.clear()
            mutableState.value = state.value.copy(selectedSection = section, page = ShellPage.Sections)
        }
    }

    /** Server availability can temporarily require Today even when the user hid it. */
    internal fun selectAvailableSection(section:Section) {
        previousPages.clear()
        mutableState.value=state.value.copy(selectedSection=section,page=ShellPage.Sections)
    }

    fun openPage(page: ShellPage) {
        if (page == state.value.page) return
        if (page == ShellPage.Sections) previousPages.clear()
        else previousPages.addLast(state.value.page)
        mutableState.value = state.value.copy(page = page)
    }
    fun back() {
        mutableState.value = state.value.copy(page = previousPages.removeLastOrNull() ?: ShellPage.Sections)
    }
    fun setQuery(query: String) { mutableState.value = state.value.copy(query = query) }
    fun accountChanged(id: String?, restricted: Boolean) {
        if (state.value.connectedAccountId == id) return
        previousPages.clear()
        if (restricted) { applyPreferences(settings.setSectionVisible(Section.Household, true)); applyPreferences(settings.setSectionVisible(Section.Today, true)) }
        mutableState.value = state.value.copy(
            connectedAccountId = id,
            selectedSection = if (restricted) Section.Household else state.value.preferences.landingSection(),
            page = if (id != null) ShellPage.Sections else state.value.page,
            query = "",
        )
    }

    private fun applyPreferences(next: ShellPreferences, useLanding: Boolean = false) {
        val selected = if (useLanding || state.value.selectedSection !in next.visibleSections)
            next.landingSection() else state.value.selectedSection
        mutableState.value = state.value.copy(preferences = next, selectedSection = selected)
    }

    fun setSectionVisible(section: Section, visible: Boolean) {
        applyPreferences(settings.setSectionVisible(section, visible))
    }
    fun setChildLanding(enabled: Boolean) {
        applyPreferences(settings.update { it.copy(childLanding = enabled) }, useLanding = true)
    }
    fun setTheme(theme: ThemeChoice) { applyPreferences(settings.update { it.copy(theme = theme) }) }
    fun setDynamicColor(enabled: Boolean) { applyPreferences(settings.update { it.copy(dynamicColor = enabled) }) }
    fun setReducedMotion(enabled: Boolean) { applyPreferences(settings.update { it.copy(reducedMotion = enabled) }) }
    fun setQuantityStyle(style: QuantityStyle) { applyPreferences(settings.update { it.copy(quantityStyle = style) }) }

    companion object {
        fun factory(repository: ShellPreferencesRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ShellViewModel(GetAppName(LocalAppInfoRepository()), ManageShellPreferences(repository))
            }
        }
    }
}
