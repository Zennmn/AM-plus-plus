package dev.amenhancer.module.config

import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.model.ModuleSettings

internal object ModuleSettingsSchema {
    fun decode(values: Map<String, *>): ModuleSettings = ModuleSettings(
        dualPaneEnabled = values.boolean(KEY_DUAL_PANE, default = true),
        disableEditorialVideoOnTablet = values.boolean(
            KEY_DISABLE_EDITORIAL_VIDEO_ON_TABLET,
            default = true,
        ),
        phoneLiquidGlassEnabled = values.boolean(
            KEY_PHONE_LIQUID_GLASS,
            default = false,
        ),
        futureBlurEnabled = values.boolean(KEY_FUTURE_BLUR, default = true),
        schemaVersion = values.number(KEY_SCHEMA_VERSION)
            ?: ModuleConstants.CONFIG_SCHEMA_VERSION,
    )

    fun encode(settings: ModuleSettings): Map<String, Any> = linkedMapOf(
        KEY_DUAL_PANE to settings.dualPaneEnabled,
        KEY_DISABLE_EDITORIAL_VIDEO_ON_TABLET to settings.disableEditorialVideoOnTablet,
        KEY_PHONE_LIQUID_GLASS to settings.phoneLiquidGlassEnabled,
        KEY_FUTURE_BLUR to settings.futureBlurEnabled,
        KEY_SCHEMA_VERSION to ModuleConstants.CONFIG_SCHEMA_VERSION,
    )

    fun upgrade(
        storedValues: Map<String, *>,
        legacyValues: Map<String, *>,
    ): Map<String, Any>? {
        val storedVersion = storedValues.number(KEY_SCHEMA_VERSION)
        if (storedVersion != null && storedVersion >= ModuleConstants.CONFIG_SCHEMA_VERSION) return null
        val source = if (storedValues.hasSettingValue()) storedValues else legacyValues
        return encode(decode(source))
    }

    private fun Map<String, *>.boolean(key: String, default: Boolean): Boolean =
        this[key] as? Boolean ?: default

    private fun Map<String, *>.number(key: String): Int? = (this[key] as? Number)?.toInt()

    private fun Map<String, *>.hasSettingValue(): Boolean = settingKeys.any(::containsKey)

    private val settingKeys = setOf(
        KEY_DUAL_PANE,
        KEY_DISABLE_EDITORIAL_VIDEO_ON_TABLET,
        KEY_PHONE_LIQUID_GLASS,
        KEY_FUTURE_BLUR,
    )

    private const val KEY_DUAL_PANE = "dual_pane_enabled"
    private const val KEY_DISABLE_EDITORIAL_VIDEO_ON_TABLET =
        "disable_editorial_video_on_tablet"
    private const val KEY_PHONE_LIQUID_GLASS = "phone_liquid_glass_enabled"
    private const val KEY_FUTURE_BLUR = "future_blur_enabled"
    private const val KEY_SCHEMA_VERSION = "schema_version"
}
