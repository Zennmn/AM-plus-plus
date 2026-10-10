package dev.amenhancer.module.hook.tabletmedia

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.widget.ImageView
import java.util.IdentityHashMap

/** Reuse native selectors/tints without stealing the retained host view's drawable callback. */
internal class TabletNativeActionStyle {
    private data class Source(val drawable: Drawable?, val background: Drawable?)
    private val sources = IdentityHashMap<ImageView, Source>()

    fun bind(target: ImageView, native: ImageView?) {
        native ?: return
        val source = Source(native.drawable, native.background)
        if (sources[target] != source) {
            source.drawable?.constantState?.newDrawable(native.resources)?.mutate()?.let(target::setImageDrawable)
            target.background = source.background?.constantState?.newDrawable(native.resources)?.mutate()
            sources[target] = source
        }
        target.imageTintList = native.imageTintList ?: ColorStateList.valueOf(Color.WHITE)
        target.imageTintMode = native.imageTintMode ?: PorterDuff.Mode.SRC_IN
        target.rotationY = native.rotationY
    }
    fun clear() { sources.clear() }
}
