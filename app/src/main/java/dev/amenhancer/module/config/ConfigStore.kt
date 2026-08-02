package dev.amenhancer.module.config

import android.content.Context
import android.content.SharedPreferences
import dev.amenhancer.module.ModuleApplication
import dev.amenhancer.module.XposedServiceSnapshot
import dev.amenhancer.module.model.CustomLyricsManifest
import dev.amenhancer.module.model.LyricsFontManifest
import dev.amenhancer.module.model.ModuleSettings

class ConfigStore(context: Context) {
    private val appContext = context.applicationContext
    private val legacyPreferences = appContext.getSharedPreferences(
        LEGACY_PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    fun settings(): ModuleSettings = settings(ModuleApplication.serviceSnapshot)

    internal fun settings(snapshot: XposedServiceSnapshot): ModuleSettings =
        ModuleSettingsSchema.decode((snapshot.preferences ?: legacyPreferences).all)

    fun saveSettings(settings: ModuleSettings): Boolean {
        val preferences = ModuleApplication.serviceSnapshot.preferences ?: return false
        return writeValues(
            preferences,
            ModuleSettingsSchema.encodeOrdinarySettings(settings),
            synchronous = false,
        )
    }

    internal fun saveFontManifest(
        manifest: LyricsFontManifest,
        snapshot: XposedServiceSnapshot = ModuleApplication.serviceSnapshot,
    ): Boolean {
        if (!snapshot.isRemoteFileAvailable || !ModuleApplication.isCurrentSnapshot(snapshot)) {
            return false
        }
        val preferences = snapshot.preferences ?: return false
        return writeValues(
            preferences,
            ModuleSettingsSchema.encodeFontManifest(manifest),
            synchronous = true,
        )
    }

    internal fun saveCustomLyricsManifest(
        manifest: CustomLyricsManifest,
        snapshot: XposedServiceSnapshot = ModuleApplication.serviceSnapshot,
    ): Boolean {
        if (!snapshot.isRemoteFileAvailable || !ModuleApplication.isCurrentSnapshot(snapshot)) {
            return false
        }
        val preferences = snapshot.preferences ?: return false
        return writeValues(
            preferences,
            ModuleSettingsSchema.encodeCustomLyricsManifest(manifest),
            synchronous = true,
        )
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
                    is Long -> editor.putLong(key, value)
                    is String -> editor.putString(key, value)
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
