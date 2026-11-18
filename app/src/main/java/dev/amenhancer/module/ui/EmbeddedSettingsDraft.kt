package dev.amenhancer.module.ui

import dev.amenhancer.module.model.ModuleSettings

/** Dialog-owned settings remain available for retry when persistence fails. */
internal class EmbeddedSettingsDraft(
    initial: ModuleSettings,
    private val persist: (ModuleSettings) -> Boolean,
) {
    var settings: ModuleSettings = initial
        private set

    fun update(next: ModuleSettings): Boolean {
        settings = next
        return save()
    }

    fun updateCellularDataEntry(enabled: Boolean): Boolean =
        update(settings.copy(forceCellularDataEntryEnabled = enabled))

    fun applySavedChange(change: (ModuleSettings) -> ModuleSettings) {
        settings = change(settings)
    }

    fun save(): Boolean = persist(settings)
}
