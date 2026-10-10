package dev.amenhancer.module.hook.tabletmedia

import android.graphics.RectF
import android.graphics.drawable.RippleDrawable
import android.widget.ImageView
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/** Keep native press feedback just outside the rendered transport symbol. */
internal class TabletTransportRipple : AutoCloseable {
    private data class Original(val drawable: RippleDrawable, val radius: Int)
    private val originals = IdentityHashMap<ImageView, Original>()

    fun apply(view: ImageView) {
        val background = view.background as? RippleDrawable ?: return
        val icon = view.drawable ?: return
        val bounds = RectF(icon.bounds)
        if (bounds.isEmpty) bounds.set(0f, 0f, icon.intrinsicWidth.toFloat(), icon.intrinsicHeight.toFloat())
        if (bounds.isEmpty) return
        view.imageMatrix.mapRect(bounds)
        val radius = (maxOf(bounds.width(), bounds.height()) / 2f + 2 * view.resources.displayMetrics.density).roundToInt()
        originals[view]?.takeIf { it.drawable !== background }?.let { originals.remove(view) }
        // Radius lives in constant state; isolate this host view from other native buttons.
        val own = background.mutate() as RippleDrawable
        originals.getOrPut(view) { Original(own, own.radius) }
        if (own.radius != radius) own.radius = radius
    }

    fun retain(views: Collection<ImageView>) {
        originals.keys.toList().filter { it !in views }.forEach(::restore)
    }
    private fun restore(view: ImageView) {
        val saved = originals.remove(view) ?: return
        if (view.background === saved.drawable) saved.drawable.radius = saved.radius
    }
    override fun close() { originals.keys.toList().forEach(::restore) }
}
