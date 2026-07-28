package dev.amenhancer.module.config

import android.content.Context
import android.content.SharedPreferences
import dev.amenhancer.module.ModuleApplication
import dev.amenhancer.module.model.ModuleSettings

class ConfigStore(context: Context) {
    private val appContext = context.applicationContext
    private val legacyPreferences = appContext.getSharedPreferences(
        LEGACY_PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    val isRemoteAvailable: Boolean get() = ModuleApplication.remotePreferences != null

    fun settings(): ModuleSettings = ModuleSettingsSchema.decode(
        (ModuleApplication.remotePreferences ?: legacyPreferences).all,
    )

    fun saveSettings(settings: ModuleSettings): Boolean {
        val preferences = ModuleApplication.remotePreferences ?: return false
        return writeValues(preferences, ModuleSettingsSchema.encode(settings), synchronous = false)
    }

    companion object {
        fun migrateLegacyPreferences(context: Context, destination: SharedPreferences) {
            val legacy = context.getSharedPreferences(
                LEGACY_PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            )
            val upgraded = ModuleSettingsSchema.upgrade(destination.all, legacy.all) ?: return
            writeValues(destination, upgraded, synchronous = true)
        }

        private fun writeValues(
            preferences: SharedPreferences,
            values: Map<String, Any>,
            synchronous: Boolean,
        ): Boolean {
            val editor = preferences.edit()
            values.forEach { (key, value) ->
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    else -> error("Unsupported configuration value for $key: ${value.javaClass.name}")
                }
            }
            return if (synchronous) editor.commit() else {
                editor.apply()
                true
            }
        }

        private const val LEGACY_PREFERENCES_NAME = "module-settings"
    }
}
