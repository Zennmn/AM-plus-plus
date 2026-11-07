package dev.amenhancer.module.ui

/** Colours for native host entry views; Compose settings use MiuixTheme. */
internal data class EmbeddedSettingsColors(
    val isDark: Boolean,
    val primary: Int,
    val onPrimary: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val disabledText: Int,
)

internal object EmbeddedSettingsPalette {
    val Light = EmbeddedSettingsColors(
        isDark = false,
        primary = 0xFFC91D40.toInt(),
        onPrimary = 0xFFFFFFFF.toInt(),
        onSurface = 0xFF1C1C1E.toInt(),
        onSurfaceVariant = 0xFF55555D.toInt(),
        disabledText = 0xFF696971.toInt(),
    )

    val Dark = EmbeddedSettingsColors(
        isDark = true,
        primary = 0xFFFF6B83.toInt(),
        onPrimary = 0xFF2B0710.toInt(),
        onSurface = 0xFFF5F5F7.toInt(),
        onSurfaceVariant = 0xFFB7B7BF.toInt(),
        disabledText = 0xFF9999A2.toInt(),
    )

    /** Only the host Activity's effective configuration should be passed here. */
    fun forUiMode(uiMode: Int): EmbeddedSettingsColors =
        if (uiMode and 0x30 == 0x20) Dark else Light
}
