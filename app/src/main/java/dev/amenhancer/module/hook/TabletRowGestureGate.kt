package dev.amenhancer.module.hook

import android.view.MotionEvent
import android.view.View
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.min

/** Touch tolerance in dp that still counts as part of a capsule handle. */
internal const val ROW_HANDLE_SLOP_DP = 8

/** Visible band of the collapsed sheet, in screen coordinates. Pure Kotlin: unit-testable. */
internal data class RowBandRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val isEmpty: Boolean get() = right <= left || bottom <= top

    fun contains(x: Float, y: Float): Boolean =
        !isEmpty && x >= left && x < right && y >= top && y < bottom
}

/**
 * One capsule handle of the tablet row, in screen coordinates. The glass capsules are
 * stadium shapes — a rounded rectangle whose left and right ends are rounded by half the
 * height — so the bounding box corners are *not* part of the handle: a touch there belongs
 * to the empty row and must pass through like any other whitespace point.
 *
 * [slop] grows the whole shape outwards (bbox by `slop`, end radius by the same amount), so
 * the tolerance keeps the pill's own curvature instead of turning into a rectangle.
 */
internal data class RowCapsuleRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val isEmpty: Boolean get() = right <= left || bottom <= top

    fun contains(x: Float, y: Float, slop: Int = 0): Boolean {
        if (isEmpty) return false
        val pad = slop.coerceAtLeast(0)
        val l = (left - pad).toFloat()
        val t = (top - pad).toFloat()
        val r = (right + pad).toFloat()
        val b = (bottom + pad).toFloat()
        if (x < l || x >= r || y < t || y >= b) return false
        val radius = min(b - t, r - l) / 2f
        val centerX = x.coerceIn(l + radius, r - radius)
        val centerY = (t + b) / 2f
        val dx = x - centerX
        val dy = y - centerY
        return dx * dx + dy * dy <= radius * radius
    }
}

/**
 * Geometry of the tablet row: the collapsed band plus the capsule handles that own a down
 * event. Every other point of the band is empty row — neither a sheet drag handle nor a
 * place the bar may swallow a touch, so the session passes those gestures to the page below.
 */
internal class TabletRowBand(private val handles: List<RowCapsuleRect>, private val slop: Int) {
    /** True when the point starts in the band but outside every capsule handle. */
    fun blocksDrag(x: Float, y: Float, band: RowBandRect): Boolean {
        if (!band.contains(x, y)) return false
        return handles.none { it.contains(x, y, slop) }
    }
}

/**
 * Tablet-row gesture gate.
 *
 * The flat host makes the whole collapsed band draggable: the sheet Behavior captures
 * the gesture inside `player_container` (ViewDragHelper picks the sheet as the top child
 * by *layout* bounds and the Behavior then intercepts), so a transparent sibling laid out
 * under the band is invisible to that pick — and a translated one cannot be picked at all.
 * The gate therefore suppresses the Behavior's own touch entries for gestures that start
 * in the empty band while the tablet glass session owns the collapsed geometry. The same
 * band also decides which down events the session lets through to the page below; a gesture
 * that passed through keeps its native owner because this gate still suppresses the sheet.
 *
 * Fail-open by construction: without a published band, a published rect or a guarded host
 * view, every entry returns the host's own result.
 */
internal object TabletRowGestureGate {
    private val guarded: MutableSet<View> = Collections.newSetFromMap(IdentityHashMap())
    private val latched = IdentityHashMap<Any, Boolean>()
    private val installed: MutableSet<Class<*>> = Collections.newSetFromMap(IdentityHashMap())

    @Volatile private var band: TabletRowBand? = null
    @Volatile private var bandRect: RowBandRect? = null

    /** Called by the tablet session while the sheet rests collapsed. */
    @Synchronized
    fun publish(row: TabletRowBand, rect: RowBandRect?, hosts: List<View>) {
        guarded.clear()
        guarded.addAll(hosts)
        band = row
        bandRect = rect?.takeIf { !it.isEmpty }
    }

    /** Called when the sheet leaves its collapsed rest state or the session ends. */
    @Synchronized
    fun clear() {
        guarded.clear()
        band = null
        bandRect = null
        latched.clear()
    }

    /** True when the point is empty row: inside the collapsed band, outside both capsules. */
    @Synchronized
    fun isRowWhitespace(x: Float, y: Float): Boolean {
        val row = band ?: return false
        val rect = bandRect ?: return false
        return row.blocksDrag(x, y, rect)
    }

    /**
     * Hooks every `(CoordinatorLayout, View, MotionEvent) -> boolean` entry on the
     * behavior type and its superclasses, i.e. material's onInterceptTouchEvent and
     * onTouchEvent as the host renames them.
     */
    fun install(behaviorType: Class<*>?): Boolean {
        val type = behaviorType ?: return false
        if (!installed.add(type)) return true
        val methods = mutableListOf<Method>()
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods.filter(::isTouchEntry).forEach { methods += it }
            current = current.superclass
        }
        if (methods.isEmpty()) return false
        var hooked = false
        methods.forEach { method ->
            runCatching {
                ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching {
                            val behavior = param.thisObject ?: return
                            val child = param.args.getOrNull(1) as? View
                            val event = param.args.getOrNull(2) as? MotionEvent ?: return
                            if (suppressed(behavior, child, event)) param.result = false
                        }
                    }
                })
                hooked = true
            }
        }
        return hooked
    }

    private fun suppressed(behavior: Any, child: View?, event: MotionEvent): Boolean {
        val row = band ?: return false
        val rect = bandRect ?: return false
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            val hit = child != null && guarded.contains(child) && row.blocksDrag(event.rawX, event.rawY, rect)
            synchronized(this) { latched[behavior] = hit }
            return hit
        }
        val hit = synchronized(this) { latched[behavior] } ?: return false
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            synchronized(this) { latched.remove(behavior) }
        }
        return hit
    }

    private fun isTouchEntry(method: Method): Boolean =
        !Modifier.isStatic(method.modifiers) && method.parameterCount == 3 &&
            method.parameterTypes[0].name.endsWith("CoordinatorLayout") &&
            method.parameterTypes[1].name == "android.view.View" &&
            method.parameterTypes[2].name == "android.view.MotionEvent" &&
            method.returnType == Boolean::class.javaPrimitiveType
}
