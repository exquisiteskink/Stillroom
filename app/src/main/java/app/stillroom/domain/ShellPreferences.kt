package app.stillroom.domain

import app.stillroom.fractions.QuantityStyle

enum class Section { Today, Pantry, Shop, Meals, Household }
enum class ThemeChoice { System, Light, Dark }

data class ShellPreferences(
    val visibleSections: Set<Section> = Section.entries.toSet(),
    val childLanding: Boolean = false,
    val theme: ThemeChoice = ThemeChoice.System,
    val dynamicColor: Boolean = false,
    val reducedMotion: Boolean = false,
    // Fractions matches what every screen showed before this setting existed.
    val quantityStyle: QuantityStyle = QuantityStyle.Fractions,
) {
    fun normalized(): ShellPreferences = copy(
        visibleSections = when {
            childLanding -> visibleSections + Section.Household
            visibleSections.isEmpty() -> setOf(Section.Today)
            else -> visibleSections
        },
    )

    fun landingSection(): Section = when {
        childLanding -> Section.Household
        Section.Today in visibleSections -> Section.Today
        else -> Section.entries.first { it in visibleSections }
    }
}

interface ShellPreferencesRepository {
    fun load(): ShellPreferences
    fun save(preferences: ShellPreferences)
}
