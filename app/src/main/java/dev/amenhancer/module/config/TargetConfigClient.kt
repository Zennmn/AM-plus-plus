package dev.amenhancer.module.config

import android.content.SharedPreferences
import android.os.ParcelFileDescriptor
import dev.amenhancer.module.hook.ModernXposedRuntime
import dev.amenhancer.module.model.CustomLyricsManifest
import dev.amenhancer.module.model.FeatureHealth
import dev.amenhancer.module.model.ModuleSettings

class TargetConfigClient(
    private val preferences: SharedPreferences,
    private val remoteFileOpener: ((String) -> ParcelFileDescriptor)? = null,
) {
    @Volatile
    private var cachedIndex: CachedIndex? = null

    init {
        active = this
    }
    /** Ordinary feature settings only; never opens the potentially large lyrics index. */
    fun settings(): ModuleSettings = ModuleSettingsSchema.decode(preferences.all)

    /** Background custom-lyrics index read. */
    fun customLyricsManifest(): CustomLyricsManifest {
        val values = preferences.all
        val pointer = ModuleSettingsSchema.decodeIndexPointer(values)
        val key = IndexCacheKey(
            pointer = pointer,
            legacyManifest = if (pointer == null) {
                ModuleSettingsSchema.legacyCustomLyricsManifestRaw(values)
            } else {
                null
            },
        )
        cachedIndex?.takeIf { it.key == key }?.let { return it.manifest }
        val state = CustomLyricsIndexRepository.state(values) { fileId ->
            remoteFileOpener?.invoke(fileId)?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
        }
        if (state.canCommit) cachedIndex = CachedIndex(key, state.manifest)
        return state.manifest
    }

    fun openRemoteFile(name: String): ParcelFileDescriptor? =
        runCatching { remoteFileOpener?.invoke(name) }.getOrNull()

    fun reportHealth(health: FeatureHealth) {
        ModernXposedRuntime.log(
            "${health.feature}: ${health.state} - ${health.message} [${health.targetVersion}]",
        )
    }

    companion object {
        @Volatile
        private var active: TargetConfigClient? = null

        fun currentSettings(): ModuleSettings = active?.settings()
            ?: ModuleSettings(phoneLiquidGlassEnabled = false)
    }

    private data class IndexCacheKey(
        val pointer: CustomLyricsIndexPointer?,
        val legacyManifest: String?,
    )

    private data class CachedIndex(
        val key: IndexCacheKey,
        val manifest: CustomLyricsManifest,
    )
}
