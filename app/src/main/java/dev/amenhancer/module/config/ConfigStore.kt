package dev.amenhancer.module.config

import android.content.Context
import android.content.SharedPreferences
import android.os.ParcelFileDescriptor
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
    @Volatile
    private var cachedIndex: CachedIndex? = null
    fun settings(): ModuleSettings = settings(ModuleApplication.serviceSnapshot)

    internal fun settings(snapshot: XposedServiceSnapshot): ModuleSettings {
        val preferences = snapshot.preferences ?: legacyPreferences
        return ModuleSettingsSchema.decode(preferences.all)
    }

    internal fun settingsWithCustomLyrics(snapshot: XposedServiceSnapshot): ModuleSettings {
        val preferences = snapshot.preferences ?: legacyPreferences
        val values = preferences.all
        val base = ModuleSettingsSchema.decode(values)
        val pointer = ModuleSettingsSchema.decodeIndexPointer(values)
        val cacheKey = IndexCacheKey(
            pointer = pointer,
            legacyManifest = if (pointer == null) {
                ModuleSettingsSchema.legacyCustomLyricsManifestRaw(values)
            } else {
                null
            },
        )
        val manifest = cachedIndex
            ?.takeIf { it.key == cacheKey }
            ?.manifest
            ?: CustomLyricsIndexRepository.state(values) { fileId ->
                snapshot.openRemoteFile(fileId)?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
            }.let { state ->
                if (state.canCommit) cachedIndex = CachedIndex(cacheKey, state.manifest)
                state.manifest
            }
        return base.copy(customLyricsManifest = manifest)
    }

    /** Current index state (pointer + resolved manifest) for settings-process mutations. */
    internal fun indexState(snapshot: XposedServiceSnapshot): CustomLyricsIndexState {
        val preferences = snapshot.preferences ?: legacyPreferences
        return CustomLyricsIndexRepository.state(preferences.all) { fileId ->
            snapshot.openRemoteFile(fileId)?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
        }
    }

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

    /** Atomic pointer publication; the index file must already be written. */
    internal fun publishIndexPointer(
        pointer: CustomLyricsIndexPointer,
        snapshot: XposedServiceSnapshot = ModuleApplication.serviceSnapshot,
    ): Boolean {
        if (!snapshot.isRemoteFileAvailable || !ModuleApplication.isCurrentSnapshot(snapshot)) {
            return false
        }
        val preferences = snapshot.preferences ?: return false
        return writeValues(
            preferences,
            ModuleSettingsSchema.encodeIndexPointer(pointer),
            synchronous = true,
        )
    }

    companion object {
        private data class IndexCacheKey(
            val pointer: CustomLyricsIndexPointer?,
            val legacyManifest: String?,
        )

        private data class CachedIndex(
            val key: IndexCacheKey,
            val manifest: CustomLyricsManifest,
        )

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

        /**
         * AM++'s own pre-remote configuration file, migrated once into the
         * libxposed "settings" group when the service first binds.  Not to be
         * confused with AMTool 1.2's similarly-named "module_settings" file,
         * which belongs to the separate com.mukapp.applemusictool app and is
         * unreadable here (see ModuleSettingsSchema.AMTOOL_MODIFY_LOCALE_KEY):
         * no AMTool key migration exists.
         */
        private const val LEGACY_PREFERENCES_NAME = "module-settings"
    }
}
