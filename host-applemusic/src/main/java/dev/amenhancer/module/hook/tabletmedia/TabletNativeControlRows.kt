package dev.amenhancer.module.hook.tabletmedia

import android.view.View
import android.view.ViewGroup
import dev.amenhancer.module.hook.dualPaneField
import java.lang.reflect.Field
import java.util.IdentityHashMap

/** Mutate the existing host params; generic LayoutParams copies lose native constraints. */
internal class TabletNativeControlRows : AutoCloseable {
    private data class Original(val params: ViewGroup.LayoutParams, val top: Int, val bottom: Int,
        val constraints: Map<Field, Int>)
    private val originals = IdentityHashMap<View, Original>()
    private data class Percent(val params: ViewGroup.LayoutParams, val field: Field, val value: Float)
    private val percentages = IdentityHashMap<View, Percent>()
    private data class Start(val params: ViewGroup.LayoutParams, val padding: Int, val guide: Field?, val begin: Int?)
    private val starts = IdentityHashMap<View, Start>()
    /** Remove only the extra native start inset; keep artwork, end and vertical geometry. */
    fun startInset(view: View, guideline: Boolean = false): Boolean {
        val p = view.layoutParams
        val field = if (guideline) dualPaneField(p.javaClass, "guideBegin") ?: if
            (p.javaClass.name == "androidx.constraintlayout.widget.ConstraintLayout\$b") dualPaneField(p.javaClass, "a") else null else null
        if (guideline && field == null) return false
        val saved = starts[view]?.takeIf { it.params === p } ?: run {
            Start(p, view.paddingLeft, field, field?.getInt(p)).also { starts[view] = it }
        }
        var changed = false
        if (guideline) {
            if (saved.guide!!.getInt(p) != 0) { saved.guide.setInt(p, 0); changed = true }
        }
        if (changed) view.layoutParams = p
        val padding = (saved.padding - (16 * view.resources.displayMetrics.density).toInt()).coerceAtLeast(0)
        if (!guideline && view.paddingLeft != padding) {
            view.setPadding(padding, view.paddingTop, view.paddingRight, view.paddingBottom); changed = true
        }
        return changed
    }
    private fun anchors(params: ViewGroup.LayoutParams): List<Field> {
        val fields = listOf("topToTop", "topToBottom", "bottomToTop", "bottomToBottom")
        val obfuscated = listOf("i", "j", "k", "l")
        return fields.mapIndexed { index, name -> checkNotNull(dualPaneField(params.javaClass, name)
            ?: if (params.javaClass.name == "androidx.constraintlayout.widget.ConstraintLayout\$b")
                dualPaneField(params.javaClass, obfuscated[index]) else null) }
    }
    private fun save(view: View): Original {
        val params = view.layoutParams
        originals[view]?.takeIf { it.params !== params }?.let { originals.remove(view) }
        return originals.getOrPut(view) {
            val margin = params as? ViewGroup.MarginLayoutParams
            Original(params, margin?.topMargin ?: 0, margin?.bottomMargin ?: 0,
                anchors(params).associateWith { it.getInt(params) })
        }
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
    fun lyricViewport(view: View, top: Int, bottom: Int): Boolean {
        val saved = save(view)
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
        starts.keys.toList().filter { it !in views }.forEach(::restoreStart)
    }
    private fun restoreStart(view: View) {
        val saved = starts.remove(view) ?: return
        if (view.layoutParams !== saved.params) return
        if (saved.guide != null) saved.guide.setInt(saved.params, checkNotNull(saved.begin))
        view.layoutParams = saved.params
        view.setPadding(saved.padding, view.paddingTop, view.paddingRight, view.paddingBottom)
    }
    private fun restore(view: View) {
        val saved = originals.remove(view) ?: return
        if (view.layoutParams !== saved.params) return
        val p = saved.params
        (p as? ViewGroup.MarginLayoutParams)?.let { it.topMargin = saved.top; it.bottomMargin = saved.bottom }
        saved.constraints.forEach { (field, value) -> field.setInt(p, value) }
        view.layoutParams = p
    }
    private fun restorePercent(view: View) {
        val saved = percentages.remove(view) ?: return
        if (view.layoutParams !== saved.params) return
        saved.field.setFloat(saved.params, saved.value); view.layoutParams = saved.params
    }
    override fun close() {
        originals.keys.toList().forEach(::restore)
        percentages.keys.toList().forEach(::restorePercent)
        starts.keys.toList().forEach(::restoreStart)
    }
}
