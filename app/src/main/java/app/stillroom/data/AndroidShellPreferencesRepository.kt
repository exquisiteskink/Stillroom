package app.stillroom.data

import android.content.Context
import app.stillroom.domain.Section
import app.stillroom.domain.ShellPreferences
import app.stillroom.domain.ShellPreferencesRepository
import app.stillroom.domain.ThemeChoice

/** Device-local UI preferences only. Never store accounts or secrets here. */
class AndroidShellPreferencesRepository(context: Context) : ShellPreferencesRepository {
    private val storage = context.applicationContext.getSharedPreferences("shell_preferences", Context.MODE_PRIVATE)

    override fun load(): ShellPreferences {
        val storedSections = storage.getStringSet("visible_sections", null)
        return ShellPreferences(
            visibleSections = storedSections?.mapNotNull { name ->
                Section.entries.find { it.name == name }
            }?.toSet() ?: Section.entries.toSet(),
            childLanding = storage.getBoolean("child_landing", false),
            theme = ThemeChoice.entries.find { it.name == storage.getString("theme", null) }
                ?: ThemeChoice.System,
            dynamicColor = storage.getBoolean("dynamic_color", false),
            reducedMotion = storage.getBoolean("reduced_motion", false),
        ).normalized()
    }

    override fun save(preferences: ShellPreferences) {
        storage.edit()
            .putStringSet("visible_sections", preferences.visibleSections.map { it.name }.toSet())
            .putBoolean("child_landing", preferences.childLanding)
            .putString("theme", preferences.theme.name)
            .putBoolean("dynamic_color", preferences.dynamicColor)
            .putBoolean("reduced_motion", preferences.reducedMotion)
            .apply()
    }
}
