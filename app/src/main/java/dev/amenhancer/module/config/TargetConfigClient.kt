package dev.amenhancer.module.config

import android.content.SharedPreferences
import android.os.ParcelFileDescriptor
import dev.amenhancer.module.hook.ModernXposedRuntime
import dev.amenhancer.module.model.FeatureHealth
import dev.amenhancer.module.model.ModuleSettings

class TargetConfigClient(
    private val preferences: SharedPreferences,
    private val remoteFileOpener: ((String) -> ParcelFileDescriptor)? = null,
) {
    init {
        active = this
    }
    fun settings(): ModuleSettings = ModuleSettingsSchema.decode(preferences.all)

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
}
