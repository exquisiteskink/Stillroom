package app.stillroom

import app.stillroom.data.LocalAppInfoRepository
import app.stillroom.domain.AppInfoRepository
import app.stillroom.domain.GetAppName
import app.stillroom.domain.ManageShellPreferences
import app.stillroom.domain.Section
import app.stillroom.domain.ShellPreferences
import app.stillroom.domain.ShellPreferencesRepository
import app.stillroom.domain.ThemeChoice
import app.stillroom.ui.ShellPage
import app.stillroom.ui.ShellViewModel
import org.junit.Assert.*
import org.junit.Test

class ShellTest {
    private class Preferences(var value: ShellPreferences = ShellPreferences()) : ShellPreferencesRepository {
        override fun load() = value
        override fun save(preferences: ShellPreferences) { value = preferences }
    }
    private fun model(repository: Preferences = Preferences()) =
        ShellViewModel(GetAppName(LocalAppInfoRepository()), ManageShellPreferences(repository))

    @Test fun shellResolvesIdentityThroughAllLayers() {
        assertEquals("Stillroom", model().appName)
    }

    @Test fun shellUsesInjectedRepository() {
        var reads = 0
        val repository = AppInfoRepository { reads++; "Fixture identity" }
        val model = ShellViewModel(GetAppName(repository), ManageShellPreferences(Preferences()))
        assertEquals("Fixture identity", model.appName)
        assertEquals(1, reads)
    }

    @Test fun defaultLandingIsTodayAndChildLandingIsHouseholdOnNewModel() {
        val preferences = Preferences()
        val model = model(preferences)
        assertFalse(model.state.value.preferences.childLanding)
        assertEquals(Section.Today, model.state.value.selectedSection)
        model.setChildLanding(true)
        assertEquals(Section.Household, model.state.value.selectedSection)
        assertEquals(Section.Household, model(preferences).state.value.selectedSection)
        model.setChildLanding(false)
        assertEquals(Section.Today, model.state.value.selectedSection)
    }

    @Test fun cannotHideLastSectionOrChildLandingAndHiddenSelectionFallsBack() {
        val preferences = Preferences(ShellPreferences(visibleSections = setOf(Section.Today, Section.Pantry)))
        val model = model(preferences)
        model.setSectionVisible(Section.Today, false)
        assertEquals(Section.Pantry, model.state.value.selectedSection)
        model.setSectionVisible(Section.Pantry, false)
        assertEquals(setOf(Section.Pantry), preferences.value.visibleSections)
        model.setChildLanding(true)
        model.setSectionVisible(Section.Household, false)
        assertTrue(Section.Household in preferences.value.visibleSections)
        model.selectSection(Section.Today)
        assertEquals(Section.Household, model.state.value.selectedSection)
    }

    @Test fun appearanceAndVisibilityPersistAndBackKeepsSelectedDestination() {
        val preferences = Preferences()
        val model = model(preferences)
        model.setTheme(ThemeChoice.Dark)
        model.setDynamicColor(true)
        model.setReducedMotion(true)
        model.setSectionVisible(Section.Meals, false)
        model.selectSection(Section.Shop)
        model.openPage(ShellPage.Search)
        model.setQuery("pantry")
        model.back()
        assertEquals(ShellPage.Sections, model.state.value.page)
        assertEquals(Section.Shop, model.state.value.selectedSection)
        assertEquals("pantry", model.state.value.query)
        assertEquals(preferences.value, model(preferences).state.value.preferences)
    }

    @Test fun invalidEmptyStoredVisibilityRecoversToUsableLanding() {
        assertEquals(setOf(Section.Today), ManageShellPreferences(Preferences(
            ShellPreferences(visibleSections = emptySet())
        )).load().visibleSections)
    }

    @Test fun reviewChangesReturnsToTheTaskThatOpenedIt() {
        val model = model()
        model.openPage(ShellPage.Scanner)
        model.openPage(ShellPage.PendingChanges)
        model.back()
        assertEquals(ShellPage.Scanner, model.state.value.page)
        model.back()
        assertEquals(ShellPage.Sections, model.state.value.page)
        model.openPage(ShellPage.Settings)
        model.openPage(ShellPage.PendingChanges)
        model.back()
        assertEquals(ShellPage.Settings, model.state.value.page)
    }

    @Test fun connectedChildLandsOnHouseholdAndAccountSwitchClearsSearch() {
        val model = model(Preferences(ShellPreferences(visibleSections = setOf(Section.Today, Section.Pantry))))
        model.selectSection(Section.Pantry)
        model.openPage(ShellPage.Search)
        model.setQuery("synthetic parent query")
        model.accountChanged("verified-child", restricted = true)
        assertEquals(Section.Household, model.state.value.selectedSection)
        assertEquals(ShellPage.Sections, model.state.value.page)
        assertEquals("", model.state.value.query)
        assertTrue(Section.Household in model.state.value.preferences.visibleSections)
        model.accountChanged("verified-parent", restricted = false)
        assertEquals(Section.Today, model.state.value.selectedSection)
    }
}
