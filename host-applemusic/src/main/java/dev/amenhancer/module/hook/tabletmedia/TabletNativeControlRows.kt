package dev.amenhancer.module.hook.tabletmedia

import android.view.View
import android.view.ViewGroup
import dev.amenhancer.module.hook.dualPaneField
import java.lang.reflect.Field
import java.util.IdentityHashMap

/** Mutate the existing host params; generic LayoutParams copies lose native constraints. */
internal class TabletNativeControlRows : AutoCloseable {
    private data class Original(val params: ViewGroup.LayoutParams, val height: Int,
        val paddingTop: Int, val paddingBottom: Int, val top: Int, val bottom: Int,
        val constraints: Map<Field, Int>)
    private val originals = IdentityHashMap<View, Original>()
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
                margin?.bottomMargin ?: 0, if (withAnchors) anchors(params).associateWith { it.getInt(params) } else emptyMap())
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
    }
    private fun restore(view: View) {
        val saved = originals.remove(view) ?: return
        if (view.layoutParams !== saved.params) return
        val p = saved.params
        p.height = saved.height
        (p as? ViewGroup.MarginLayoutParams)?.let { it.topMargin = saved.top; it.bottomMargin = saved.bottom }
        saved.constraints.forEach { (field, value) -> field.setInt(p, value) }
        view.setPadding(view.paddingLeft, saved.paddingTop, view.paddingRight, saved.paddingBottom)
        view.layoutParams = p
    }
    override fun close() { originals.keys.toList().forEach(::restore) }
}
