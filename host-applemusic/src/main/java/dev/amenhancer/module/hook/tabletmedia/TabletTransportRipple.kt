package dev.amenhancer.module.hook.tabletmedia

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.RippleDrawable
import android.widget.ImageView
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/** Keep native press feedback just outside the rendered transport symbol. */
internal class TabletTransportRipple : AutoCloseable {
    private data class Original(val drawable: RippleDrawable, val applied: RippleDrawable)
    private val originals = IdentityHashMap<ImageView, Original>()
    private val dimColor = ColorStateList.valueOf(Color.argb(51, 255, 255, 255))

    fun apply(view: ImageView) {
        val background = view.background as? RippleDrawable ?: return
        val icon = view.drawable ?: return
        val bounds = RectF(icon.bounds)
        if (bounds.isEmpty) bounds.set(0f, 0f, icon.intrinsicWidth.toFloat(), icon.intrinsicHeight.toFloat())
        if (bounds.isEmpty) return
        view.imageMatrix.mapRect(bounds)
        val radius = (maxOf(bounds.width(), bounds.height()) / 2f + 8 * view.resources.displayMetrics.density).roundToInt()
        val saved = originals[view]?.takeIf { it.applied === background } ?: run {
            // Keep the native drawable intact so all host colors restore without private APIs.
            val applied = background.constantState?.newDrawable(view.resources)?.mutate() as? RippleDrawable ?: return
            applied.setColor(dimColor)
            val padding = intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
            view.background = applied
            view.setPadding(padding[0], padding[1], padding[2], padding[3])
            Original(background, applied).also { originals[view] = it }
        }
        val own = saved.applied
        if (own.radius != radius) own.radius = radius
    }

    fun retain(views: Collection<ImageView>) {
        originals.keys.toList().filter { it !in views }.forEach(::restore)
    }
    private fun restore(view: ImageView) {
        val saved = originals.remove(view) ?: return
        if (view.background === saved.applied) {
            val padding = intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
            view.background = saved.drawable
            view.setPadding(padding[0], padding[1], padding[2], padding[3])
        }
    }
    override fun close() { originals.keys.toList().forEach(::restore) }
}
