package dev.amenhancer.module.hook

import android.content.res.ColorStateList
import android.os.Build
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Color
import dev.amenhancer.module.host.OwnedHostProperty

/**
 * Feeds the glass material's black/white choice into the native mini player's title, artist
 * and playback icons. Each target keeps its native [ColorStateList] (states and alphas) until
 * the mini glass hides or the host replaces the value again.
 */
@RequiresApi(Build.VERSION_CODES.S)
internal class GlassMiniForeground(private val target: View?) {
    private class Label(val view: TextView) {
        val owner = OwnedHostProperty({ view.textColors }, view::setTextColor)
        var native: ColorStateList = view.textColors
        var applied: ColorStateList? = null
    }

    private class Icon(val view: ImageView) {
        // Host phone/tablet binders assign the stateful icon tint through setImageTintList.
        val owner = OwnedHostProperty({ view.imageTintList }, { view.imageTintList = it })
        var native: ColorStateList? = view.imageTintList
        var applied: ColorStateList? = null
    }

    private val labels = target?.let(AppleMusicHostFactory::miniPlayerTextViews).orEmpty().map(::Label)
    private val icons = target?.let(AppleMusicHostFactory::miniPlayerTintIcons).orEmpty().map(::Icon)
    private var lightness: Float? = null

    /** Applies the sampled foreground; null restores every native target color. */
    fun apply(foreground: Color?) {
        if (foreground == null) {
            reset()
            return
        }
        if (target?.isAttachedToWindow != true) return
        val next = if (foreground == Color.White) 100f else 0f
        val changed = next != lightness
        lightness = next
        refresh(changed)
    }

    fun refresh() = refresh(changed = false)

    private fun refresh(changed: Boolean) {
        val currentLightness = lightness ?: return
        labels.forEach { label ->
            val current = label.view.textColors
            if (current === label.applied && !changed) return@forEach
            if (current !== label.applied) label.native = current
            val color = label.native.withLStar(currentLightness)
            label.owner.set(color)
            label.applied = color
        }
        icons.forEach { icon ->
            val current = icon.view.imageTintList
            if (current === icon.applied && !changed) return@forEach
            if (current !== icon.applied) icon.native = current
            // A binder that has not tinted yet still gets an opaque control color.
            val base = icon.native ?: ColorStateList.valueOf(
                if (currentLightness == 100f) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(),
            )
            val color = base.withLStar(currentLightness)
            icon.owner.set(color)
            icon.applied = color
        }
    }

    fun reset() {
        if (lightness == null) return
        lightness = null
        labels.forEach { label ->
            label.owner.close()
            label.applied = null
        }
        icons.forEach { icon ->
            icon.owner.close()
            icon.applied = null
        }
    }
}
