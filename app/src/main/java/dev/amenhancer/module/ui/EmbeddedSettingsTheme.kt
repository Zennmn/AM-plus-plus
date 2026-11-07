package dev.amenhancer.module.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.amenhancer.module.R

internal fun embeddedPalette(activity: Activity): EmbeddedSettingsColors =
    EmbeddedSettingsPalette.forUiMode(activity.resources.configuration.uiMode)

private class EmbeddedThemeBindings {
    val updates = mutableListOf<(View, EmbeddedSettingsColors) -> Unit>()
}

/** Bind colours to the existing view, so changing theme never recreates editable state. */
internal fun <T : View> T.bindEmbeddedTheme(
    activity: Activity,
    update: T.(EmbeddedSettingsColors) -> Unit,
): T {
    val bindings = (getTag(R.id.ampp_embedded_theme_binding) as? EmbeddedThemeBindings)
        ?: EmbeddedThemeBindings().also { setTag(R.id.ampp_embedded_theme_binding, it) }
    bindings.updates += { view, colors ->
        @Suppress("UNCHECKED_CAST")
        (view as T).update(colors)
    }
    update(embeddedPalette(activity))
    return this
}

internal fun TextView.setEmbeddedTextColor(activity: Activity, color: (EmbeddedSettingsColors) -> Int) {
    bindEmbeddedTheme(activity) { colors ->
        setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(colors.disabledText, color(colors)),
        ))
    }
}

internal fun View.bindEmbeddedClickFeedback(
    activity: Activity,
    cornerRadiusDp: Int = 12,
) {
    stateListAnimator = null
    val ripple = RippleDrawable(
        ColorStateList.valueOf(Color.TRANSPARENT),
        null,
        EmbeddedG3Drawable().apply {
            setColor(Color.WHITE)
            cornerRadius = cornerRadiusDp * activity.resources.displayMetrics.density
        },
    )
    foreground = ripple
    bindEmbeddedTheme(activity) { colors ->
        ripple.setColor(ColorStateList.valueOf(if (colors.isDark) 0x24FFFFFF else 0x14000000))
    }
}

internal fun embeddedThemedContext(activity: Activity): ContextThemeWrapper {
    val theme = if (embeddedPalette(activity).isDark) android.R.style.Theme_Material else android.R.style.Theme_Material_Light
    return ContextThemeWrapper(activity, theme)
}

/** The fallback entry stays a native View. The module dialog has its own Compose theme. */
private fun applyEmbeddedTheme(root: View, colors: EmbeddedSettingsColors) {
    val bindings = root.getTag(R.id.ampp_embedded_theme_binding) as? EmbeddedThemeBindings
    bindings?.updates?.forEach { it(root, colors) }
    if (root is ViewGroup) repeat(root.childCount) { applyEmbeddedTheme(root.getChildAt(it), colors) }
}

internal fun EmbeddedSettingsHost.refreshEmbeddedTheme(activity: Activity) {
    val colors = embeddedPalette(activity)
    buttonReference?.get()?.let { applyEmbeddedTheme(it, colors) }
    settingsOptionReference?.get()?.let { applyEmbeddedTheme(it, colors) }
}
