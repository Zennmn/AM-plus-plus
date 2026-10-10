package dev.amenhancer.module.hook.tabletmedia

import android.view.View
import android.view.ViewGroup
import dev.amenhancer.module.hook.dualPaneField
import java.lang.reflect.Field
import java.util.IdentityHashMap
import android.widget.TextView
import android.widget.ImageView
import android.util.TypedValue

/** Mutate the existing host params; generic LayoutParams copies lose native constraints. */
internal class TabletNativeControlRows : AutoCloseable {
    private data class Original(val params: ViewGroup.LayoutParams, val height: Int,
        val paddingTop: Int, val paddingBottom: Int, val top: Int, val bottom: Int,
        val constraints: Map<Field, Int>, val scale: ImageView.ScaleType?)
    private val originals = IdentityHashMap<View, Original>()
    private data class Percent(val params: ViewGroup.LayoutParams, val field: Field, val value: Float)
    private val percentages = IdentityHashMap<View, Percent>()
    private data class Text(val size: Float, val minimum: Int)
    private val texts = IdentityHashMap<TextView, Text>()
    private fun anchors(params: ViewGroup.LayoutParams): List<Field> {
        val fields = listOf("topToTop", "topToBottom", "bottomToTop", "bottomToBottom")
        val obfuscated = listOf("i", "j", "k", "l")
        return fields.mapIndexed { index, name -> checkNotNull(dualPaneField(params.javaClass, name)
            ?: if (params.javaClass.name == "androidx.constraintlayout.widget.ConstraintLayout\$b")
                dualPaneField(params.javaClass, obfuscated[index]) else null) }
    }
    private fun save(view: View, withAnchors: Boolean): Original {
        val params = view.layoutParams
        originals[view]?.takeIf { it.params !== params }?.let { originals.remove(view) }
        return originals.getOrPut(view) {
            val margin = params as? ViewGroup.MarginLayoutParams
            Original(params, params.height, view.paddingTop, view.paddingBottom, margin?.topMargin ?: 0,
                margin?.bottomMargin ?: 0, if (withAnchors) anchors(params).associateWith { it.getInt(params) } else emptyMap(),
                (view as? ImageView)?.scaleType)
        }
    }
    fun prepareTimes(views: List<View>, margin: Int): Boolean {
        var changed = false
        views.forEach { view ->
            val saved = save(view, false)
            val p = saved.params as? ViewGroup.MarginLayoutParams ?: return@forEach
            val top = minOf(saved.top, margin); val bottom = minOf(saved.bottom, margin)
            if (p.topMargin != top || p.bottomMargin != bottom) {
                p.topMargin = top; p.bottomMargin = bottom; view.layoutParams = p; changed = true
            }
        }
        return changed
    }
    fun percentage(view: View, value: Float): Boolean {
        val p = view.layoutParams
        if (p.height != 0) return false
        val field = dualPaneField(p.javaClass, "matchConstraintPercentHeight") ?: if
            (p.javaClass.name == "androidx.constraintlayout.widget.ConstraintLayout\$b") dualPaneField(p.javaClass, "S") else null
        field ?: return false
        val saved = percentages[view]?.takeIf { it.params === p }
            ?: Percent(p, field, field.getFloat(p)).also { percentages[view] = it }
        if (field.getFloat(p) == value) return false
        saved.field.setFloat(p, value); view.layoutParams = p
        return true
    }
    /** Preserve the metadata row height, so reducing glyphs cannot move the existing cover barrier. */
    fun text(view: TextView, sizeSp: Float, preserveHeight: Boolean): Boolean {
        val saved = texts.getOrPut(view) { Text(view.textSize, view.minimumHeight) }
        @Suppress("DEPRECATION") val size = minOf(saved.size, sizeSp * view.resources.displayMetrics.scaledDensity)
        var changed = false
        if (preserveHeight && view.height > 0 && view.minimumHeight < view.height) {
            view.minimumHeight = view.height; changed = true
        }
        if (kotlin.math.abs(view.textSize - size) > .1f) {
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size); changed = true
        }
        return changed
    }
    fun transport(view: View, top: Int, height: Int, padding: Int): Boolean {
        val saved = save(view, true)
        val p = saved.params as ViewGroup.MarginLayoutParams
        var changed = p.height != height || p.topMargin != top || p.bottomMargin != 0
        val fields = anchors(p)
        fields.forEachIndexed { index, field ->
            val value = if (index == 0) 0 else -1
            if (field.getInt(p) != value) { field.setInt(p, value); changed = true }
        }
        p.height = height; p.topMargin = top; p.bottomMargin = 0
        // Draw the native symbol at its intrinsic size, even in the compact row.
        // FIT_CENTER would shrink it again to fit the row's reduced content height.
        (view as? ImageView)?.let {
            if (it.scaleType != ImageView.ScaleType.CENTER) { it.scaleType = ImageView.ScaleType.CENTER; changed = true }
        }
        val padTop = minOf(saved.paddingTop, padding); val padBottom = minOf(saved.paddingBottom, padding)
        if (view.paddingTop != padTop || view.paddingBottom != padBottom) {
            view.setPadding(view.paddingLeft, padTop, view.paddingRight, padBottom); changed = true
        }
        if (changed) view.layoutParams = p
        return changed
    }
    fun footer(view: View, height: Int, bottom: Int): Boolean {
        val saved = save(view, false)
        val p = saved.params as? ViewGroup.MarginLayoutParams ?: return false
        if (p.height == height && p.bottomMargin == bottom) return false
        p.height = height; p.bottomMargin = bottom; view.layoutParams = p
        return true
    }
    fun lyricViewport(view: View, top: Int, bottom: Int): Boolean {
        val saved = save(view, true)
        val p = saved.params as ViewGroup.MarginLayoutParams
        val fields = anchors(p); val values = listOf(0, -1, -1, 0)
        var changed = p.topMargin != top || p.bottomMargin != bottom
        fields.forEachIndexed { index, field ->
            if (field.getInt(p) != values[index]) { field.setInt(p, values[index]); changed = true }
        }
        p.topMargin = top; p.bottomMargin = bottom
        if (changed) view.layoutParams = p
        return changed
    }
    fun retain(views: Collection<View>) {
        originals.keys.toList().filter { it !in views }.forEach(::restore)
        percentages.keys.toList().filter { it !in views }.forEach(::restorePercent)
        texts.keys.toList().filter { it !in views }.forEach(::restoreText)
    }
    private fun restore(view: View) {
        val saved = originals.remove(view) ?: return
        if (view.layoutParams !== saved.params) return
        val p = saved.params
        p.height = saved.height
        (p as? ViewGroup.MarginLayoutParams)?.let { it.topMargin = saved.top; it.bottomMargin = saved.bottom }
        saved.constraints.forEach { (field, value) -> field.setInt(p, value) }
        saved.scale?.let { (view as? ImageView)?.scaleType = it }
        view.setPadding(view.paddingLeft, saved.paddingTop, view.paddingRight, saved.paddingBottom)
        view.layoutParams = p
    }
    private fun restorePercent(view: View) {
        val saved = percentages.remove(view) ?: return
        if (view.layoutParams !== saved.params) return
        saved.field.setFloat(saved.params, saved.value); view.layoutParams = saved.params
    }
    private fun restoreText(view: TextView) {
        val saved = texts.remove(view) ?: return
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, saved.size); view.minimumHeight = saved.minimum
    }
    override fun close() {
        originals.keys.toList().forEach(::restore)
        percentages.keys.toList().forEach(::restorePercent)
        texts.keys.toList().forEach(::restoreText)
    }
}
