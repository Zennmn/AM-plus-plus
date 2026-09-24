package dev.amenhancer.module.hook

import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import dev.amenhancer.module.ModuleConstants
import java.util.Collections
import java.util.WeakHashMap

/** Routes a collapsed tablet mini gesture to the visible native mini subtree. */
internal object TabletMiniTouchRouter {
    private val roots: MutableSet<ViewGroup> = Collections.newSetFromMap(WeakHashMap())
    private val targets = WeakHashMap<ViewGroup, View>()
    private var installed = false
    private var playerRootId = 0
    private var sheetId = 0
    private var miniId = 0

    fun install(flatRoot: ViewGroup) {
        val resources = flatRoot.resources
        playerRootId = resources.getIdentifier("player_root", "id", ModuleConstants.TARGET_PACKAGE)
        sheetId = resources.getIdentifier("player_sheet_container", "id", ModuleConstants.TARGET_PACKAGE)
        miniId = resources.getIdentifier("mini_player", "id", ModuleConstants.TARGET_PACKAGE)
        check(playerRootId != 0 && sheetId != 0 && miniId != 0) { "tablet mini touch resources missing" }
        roots.add(flatRoot)
        if (installed) return
        val method = ViewGroup::class.java.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
        ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val playerRoot = param.thisObject as? ViewGroup ?: return
                if (playerRoot.id != playerRootId) return
                val event = param.args[0] as? MotionEvent ?: return
                if (event.actionMasked != MotionEvent.ACTION_DOWN && targets[playerRoot] == null) return
                val flatRoot = generateSequence(playerRoot.parent as? View) { it.parent as? View }
                    .filterIsInstance<ViewGroup>().firstOrNull { it in roots } ?: return
                if (event.actionMasked == MotionEvent.ACTION_DOWN && !TabletModeQualifier.isEligible(flatRoot.context)) return
                val target = if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    targets.remove(playerRoot)
                    val sheet = flatRoot.findViewById<View>(sheetId) ?: return
                    if (sheet.height <= 0 || sheet.top <= sheet.height / 2) return
                    val mini = playerRoot.findViewById<View>(miniId) ?: return
                    if (!mini.isShown || mini.parent !== playerRoot) return
                    val bounds = Rect()
                    if (!mini.getGlobalVisibleRect(bounds) || !bounds.contains(event.rawX.toInt(), event.rawY.toInt())) return
                    mini.also { targets[playerRoot] = it }
                } else targets[playerRoot] ?: return

                val location = IntArray(2).also(target::getLocationOnScreen)
                val forwarded = MotionEvent.obtain(event)
                val handled = try {
                    forwarded.setLocation(event.rawX - location[0], event.rawY - location[1])
                    target.dispatchTouchEvent(forwarded)
                } finally { forwarded.recycle() }
                if (event.actionMasked == MotionEvent.ACTION_DOWN && !handled ||
                    event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    targets.remove(playerRoot)
                }
                param.result = handled
            }
        })
        installed = true
    }
}
