package dev.amenhancer.module.hook.tabletmedia

// Adapted from Kifranei/AM-plus-plus-media-plugin, 9e3f72b (GPL-3.0).

import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.widget.ImageView
import java.util.IdentityHashMap

/** Scale the symbol, keeping the native hit target, image matrix and playback animation. */
internal class TabletTransportStyle {
    private class Symbol(drawable: Drawable, private val factor: Float) : DrawableWrapper(drawable) {
        override fun draw(canvas: Canvas) {
            val save = canvas.save()
            canvas.scale(factor, factor, bounds.exactCenterX(), bounds.exactCenterY())
            super.draw(canvas)
            canvas.restoreToCount(save)
        }
    }
    private data class Original(val drawable: Drawable, val symbol: Symbol)
    private val originals = IdentityHashMap<ImageView, Original>()

    fun apply(previous: ImageView?, play: ImageView?, next: ImageView?) {
        val active = setOfNotNull(previous, play, next)
        originals.keys.toList().filter { it !in active }.forEach(::restore)
        active.forEach { view ->
            val current = view.drawable ?: return@forEach
            if (originals[view]?.symbol === current) return@forEach
            val symbol = Symbol(current, if (view === play) .94f else .9f)
            originals[view] = Original(current, symbol)
            view.setImageDrawable(symbol)
            // ImageView clears the former drawable's callback while replacing it.
            // Reconnect animated/stateful native drawables to the wrapper afterward.
            current.callback = symbol
        }
    }

    private fun restore(view: ImageView) {
        val original = originals.remove(view) ?: return
        if (view.drawable === original.symbol) view.setImageDrawable(original.drawable)
        else if (original.drawable.callback === original.symbol) original.drawable.callback = null
    }
    fun restore() { originals.keys.toList().forEach(::restore) }
}
