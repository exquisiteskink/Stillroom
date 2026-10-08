package app.stillroom.domain

class ManageShellPreferences(private val repository: ShellPreferencesRepository) {
    fun load(): ShellPreferences = repository.load().normalized()

    fun update(transform: (ShellPreferences) -> ShellPreferences): ShellPreferences {
        val next = transform(load()).normalized()
        repository.save(next)
        return next
    }

    fun setSectionVisible(section: Section, visible: Boolean): ShellPreferences = update { current ->
        if (!visible && (current.visibleSections.size == 1 ||
                    (current.childLanding && section == Section.Household))) {
            current
        } else {
            current.copy(visibleSections = if (visible) current.visibleSections + section
                else current.visibleSections - section)
        }
    }
}
